package com.aluco.server.alerting;

import com.aluco.server.common.TelemetryMessage;
import com.aluco.server.device.DeviceRepository;
import com.aluco.server.push.LivePush;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Alert path of the sink fan-out (spec 7.3.6):
 *  - for each data point, find rules that are enabled, metric-matching and
 *    in scope (deviceKey null or equal), then evaluate the state machine
 *  - FIRED    -> persist FIRING event + pushAlert
 *  - RESOLVED -> mark RESOLVED + pushAlert
 * At startup, FIRING events are re-seeded into the evaluator so a restart
 * does not re-fire alerts that are already open.
 */
@Component
public class AlertingEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertingEngine.class);

    private final AlertRuleServiceImpl ruleService;
    private final AlertEventServiceImpl eventService;
    private final DefaultRuleEvaluator evaluator;
    private final DeviceRepository deviceRepository;
    private final LivePush livePush;
    private final Timer ruleEvalTimer;

    public AlertingEngine(AlertRuleServiceImpl ruleService,
                          AlertEventServiceImpl eventService,
                          DefaultRuleEvaluator evaluator,
                          DeviceRepository deviceRepository,
                          LivePush livePush,
                          MeterRegistry registry) {
        this.ruleService = ruleService;
        this.eventService = eventService;
        this.evaluator = evaluator;
        this.deviceRepository = deviceRepository;
        this.livePush = livePush;
        this.ruleEvalTimer = registry.timer("aluco.rule.eval");
    }

    /** Restart recovery: re-seed FIRING (ruleId, deviceKey) pairs (spec 7.3.6). */
    @PostConstruct
    void recoverFiringStates() {
        int recovered = 0;
        for (AlertEvent e : eventService.listFiring()) {
            String deviceKey = deviceRepository.findById(e.getDeviceId())
                    .map(d -> d.getDeviceKey()).orElse(null);
            if (deviceKey != null) {
                evaluator.restore(e.getRuleId(), deviceKey);
                recovered++;
            }
        }
        if (recovered > 0) {
            log.info("recovered {} FIRING (rule, device) states", recovered);
        }
    }

    public void onTelemetry(TelemetryMessage msg) {
        for (Map.Entry<String, Double> point : msg.metrics().entrySet()) {
            String metric = point.getKey();
            double value = point.getValue();
            for (AlertRule rule : ruleService.listEnabled()) {
                if (!rule.getMetric().equals(metric) || !rule.appliesTo(msg.deviceKey())) {
                    continue;
                }
                long start = System.nanoTime();
                EvaluationResult result = evaluator.evaluate(rule, msg.deviceKey(), value, msg.ts());
                ruleEvalTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);

                switch (result.transition()) {
                    case FIRED -> {
                        AlertEvent event = eventService.fireReturning(rule, msg.deviceKey(),
                                value, msg.ts());
                        livePush.pushAlert(event);
                    }
                    case RESOLVED -> {
                        eventService.resolve(rule, msg.deviceKey(), msg.ts());
                        // resolve() updates the row; push a lightweight resolution event
                        AlertEvent resolved = new AlertEvent(rule.getId(), 0L, value,
                                java.time.Instant.ofEpochMilli(msg.ts()));
                        resolved.setRuleName(rule.getName());
                        resolved.setDeviceKey(msg.deviceKey());
                        resolved.resolve(java.time.Instant.ofEpochMilli(msg.ts()));
                        livePush.pushAlert(resolved);
                    }
                    case NONE -> { /* dedup or quiet: nothing to do */ }
                }
            }
        }
    }
}
