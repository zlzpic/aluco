package com.aluco.server.alerting;

import com.aluco.server.common.BizException;
import com.aluco.server.common.Page;
import com.aluco.server.device.DeviceRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Enabled rules are cached in memory and rebuilt on any rule mutation
 * (spec 7.3.6: rule cache invalidated on create/update/enable/delete).
 */
@Service
public class AlertRuleServiceImpl implements AlertRuleService {

    private final AlertRuleRepository ruleRepository;
    private final AlertEventRepository eventRepository;
    private final DeviceRepository deviceRepository;
    private final DefaultRuleEvaluator evaluator;

    private volatile List<AlertRule> enabledCache = List.of();

    public AlertRuleServiceImpl(AlertRuleRepository ruleRepository,
                                AlertEventRepository eventRepository,
                                DeviceRepository deviceRepository,
                                DefaultRuleEvaluator evaluator) {
        this.ruleRepository = ruleRepository;
        this.eventRepository = eventRepository;
        this.deviceRepository = deviceRepository;
        this.evaluator = evaluator;
        refreshCache();
    }

    @Override
    public AlertRule create(CreateRuleRequest req) {
        if (req.deviceKey() != null && !deviceRepository.existsByDeviceKey(req.deviceKey())) {
            throw BizException.notFound("DEVICE_NOT_FOUND", "no such device: " + req.deviceKey());
        }
        AlertRule saved = ruleRepository.save(new AlertRule(
                req.name(), req.metric(), req.op(), req.threshold(), req.deviceKey()));
        refreshCache();
        return saved;
    }

    @Override
    public void setEnabled(long ruleId, boolean enabled) {
        AlertRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> BizException.notFound("RULE_NOT_FOUND", "no such rule: " + ruleId));
        rule.setEnabled(enabled);
        ruleRepository.save(rule);
        if (!enabled) {
            // disabling stops future evaluations; forget prior firing state
            evaluator.evictRule(ruleId);
        }
        refreshCache();
    }

    @Override
    @Transactional
    public void delete(long ruleId) {
        if (!ruleRepository.existsById(ruleId)) {
            throw BizException.notFound("RULE_NOT_FOUND", "no such rule: " + ruleId);
        }
        // FIRING events of this rule become RESOLVED (spec 5.5 #11)
        eventRepository.resolveAllFiringOfRule(ruleId, Instant.now());
        ruleRepository.deleteById(ruleId);
        evaluator.evictRule(ruleId);
        refreshCache();
    }

    @Override
    public Page<AlertRule> page(int page, int size) {
        var p = ruleRepository.findAll(PageRequest.of(Math.max(0, page - 1), size));
        return Page.of(p.getContent(), p.getTotalElements(), page, size);
    }

    /** Hot-path read: returns the in-memory cache, never hits the DB. */
    @Override
    public List<AlertRule> listEnabled() {
        return enabledCache;
    }

    private void refreshCache() {
        enabledCache = List.copyOf(ruleRepository.findByEnabledTrue());
    }
}
