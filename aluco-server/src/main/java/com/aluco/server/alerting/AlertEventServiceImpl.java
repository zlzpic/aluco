package com.aluco.server.alerting;

import com.aluco.server.common.BizException;
import com.aluco.server.common.Page;
import com.aluco.server.device.Device;
import com.aluco.server.device.DeviceRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class AlertEventServiceImpl implements AlertEventService {

    private final AlertEventRepository eventRepository;
    private final DeviceRepository deviceRepository;

    public AlertEventServiceImpl(AlertEventRepository eventRepository,
                                 DeviceRepository deviceRepository,
                                 MeterRegistry registry) {
        this.eventRepository = eventRepository;
        this.deviceRepository = deviceRepository;
        // aluco.alerts.firing gauge (spec 7.4.3)
        Gauge.builder("aluco.alerts.firing", eventRepository,
                        r -> r.countByStatus(AlertEvent.Status.FIRING))
                .register(registry);
    }

    @Override
    public void fire(AlertRule rule, String deviceKey, double value, long ts) {
        fireReturning(rule, deviceKey, value, ts); // event pushed by AlertingEngine
    }

    /** Impl-class extra: same as fire() but hands the persisted event back for WS push. */
    @Transactional
    public AlertEvent fireReturning(AlertRule rule, String deviceKey, double value, long ts) {
        Device device = deviceRepository.findByDeviceKey(deviceKey)
                .orElseThrow(() -> BizException.notFound("DEVICE_NOT_FOUND",
                        "no such device: " + deviceKey));
        AlertEvent event = new AlertEvent(rule.getId(), device.getId(), value, Instant.ofEpochMilli(ts));
        event.setRuleName(rule.getName());
        event.setDeviceKey(deviceKey);
        return eventRepository.save(event);
    }

    @Override
    @Transactional
    public void resolve(AlertRule rule, String deviceKey, long ts) {
        deviceRepository.findByDeviceKey(deviceKey).ifPresent(device -> {
            List<AlertEvent> firing = eventRepository.findByRuleIdAndStatus(
                    rule.getId(), AlertEvent.Status.FIRING);
            Instant at = Instant.ofEpochMilli(ts);
            firing.stream()
                    .filter(e -> e.getDeviceId().equals(device.getId()))
                    .forEach(e -> {
                        e.resolve(at);
                        e.setRuleName(rule.getName());
                        e.setDeviceKey(deviceKey);
                        eventRepository.save(e);
                    });
        });
    }

    @Override
    public void ack(long eventId) {
        AlertEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> BizException.notFound("ALERT_NOT_FOUND",
                        "no such alert event: " + eventId));
        if (event.getStatus() != AlertEvent.Status.FIRING) {
            throw BizException.conflict("ALERT_NOT_FIRING",
                    "only FIRING events can be acked (current: " + event.getStatus() + ")");
        }
        event.ack(Instant.now());
        eventRepository.save(event);
        enrich(List.of(event));
    }

    @Override
    public Page<AlertEvent> query(String status, String deviceKey, int page, int size) {
        AlertEvent.Status st = (status == null || status.isBlank())
                ? null : AlertEvent.Status.valueOf(status);
        Long deviceId = null;
        if (deviceKey != null && !deviceKey.isBlank()) {
            deviceId = deviceRepository.findByDeviceKey(deviceKey)
                    .map(Device::getId)
                    .orElseThrow(() -> BizException.notFound("DEVICE_NOT_FOUND",
                            "no such device: " + deviceKey));
        }
        var p = eventRepository.search(st, deviceId,
                PageRequest.of(Math.max(0, page - 1), size));
        enrich(p.getContent());
        return Page.of(p.getContent(), p.getTotalElements(), page, size);
    }

    /** Fill snapshot ruleName/deviceKey for API rendering (v2 spec 4.2 #3, 4.4.1). */
    private void enrich(List<AlertEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        // Use snapshot columns instead of joining device table (v2 spec 4.4.1)
        events.forEach(e -> {
            if (e.getDeviceKey() == null || e.getDeviceKey().isBlank()) {
                e.setDeviceKey("deleted");
            }
        });
    }

    /** Restart recovery source (spec 7.3.6): all currently FIRING events. */
    public List<AlertEvent> listFiring() {
        return eventRepository.findByStatus(AlertEvent.Status.FIRING);
    }

    @Override
    public AlertEvent getById(long id) {
        return eventRepository.findById(id).orElse(null);
    }

    /** Find FIRING event for (ruleId, deviceKey) pair - used by RESOLVED push (spec 4.2 #2). */
    @Transactional
    public AlertEvent findFiringEvent(Long ruleId, String deviceKey) {
        // Find by ruleId, filter by deviceKey (device_id lookup)
        List<AlertEvent> events = eventRepository.findByRuleIdAndStatus(
                ruleId, AlertEvent.Status.FIRING);
        for (AlertEvent e : events) {
            String dk = deviceRepository.findById(e.getDeviceId())
                    .map(Device::getDeviceKey).orElse(null);
            if (deviceKey.equals(dk)) {
                e.setRuleName(null);  // Will be filled by caller
                e.setDeviceKey(deviceKey);
                return e;
            }
        }
        return null;
    }

   /* public Function<Long, String> unusedGuard() {
        throw new UnsupportedOperationException();
    }*/
}
