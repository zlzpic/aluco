package com.aluco.server.alerting;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * (ruleId, deviceKey) -> NORMAL/FIRING state machine (spec 7.3.6):
 *  - NORMAL + violated      -> FIRED
 *  - FIRING + not violated  -> RESOLVED
 *  - FIRING + violated      -> NONE (dedup: no repeated events while firing)
 *  - NORMAL + not violated  -> NONE
 * After a restart, AlertingEngine re-seeds FIRING pairs from the DB via
 * restore() so recovered instances do not re-fire (spec 7.3.6).
 */
@Component
public class DefaultRuleEvaluator implements RuleEvaluator {

    private enum State { NORMAL, FIRING }

    private record Key(Long ruleId, String deviceKey) {}

    private final Map<Key, State> states = new ConcurrentHashMap<>();

    @Override
    public EvaluationResult evaluate(AlertRule rule, String deviceKey, double value, long ts) {
        Key key = new Key(rule.getId(), deviceKey);
        State current = states.getOrDefault(key, State.NORMAL);
        boolean violated = rule.violatedBy(value);

        if (current == State.NORMAL && violated) {
            states.put(key, State.FIRING);
            return EvaluationResult.FIRED;
        }
        if (current == State.FIRING && !violated) {
            states.put(key, State.NORMAL);
            return EvaluationResult.RESOLVED;
        }
        return EvaluationResult.NONE;
    }

    /** Re-seed a FIRING pair at startup (restart recovery, spec 7.3.6). */
    public void restore(Long ruleId, String deviceKey) {
        states.put(new Key(ruleId, deviceKey), State.FIRING);
    }

    /** Forget all states of a deleted/disabled rule so it can fire fresh later. */
    public void evictRule(Long ruleId) {
        states.keySet().removeIf(k -> k.ruleId().equals(ruleId));
    }

    /** Test hook. */
    public int stateCount() {
        return states.size();
    }
}
