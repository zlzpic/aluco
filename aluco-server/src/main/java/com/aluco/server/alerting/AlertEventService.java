package com.aluco.server.alerting;

import com.aluco.server.common.Page;

import java.util.List;

public interface AlertEventService {
    void fire(AlertRule rule, String deviceKey, double value, long ts);
    void resolve(AlertRule rule, String deviceKey, long ts);
    void ack(long eventId);
    Page<AlertEvent> query(String status, String deviceKey, int page, int size);
    /** Persist and return the new FIRING event (needed for WS push). */
    AlertEvent fireReturning(AlertRule rule, String deviceKey, double value, long ts);
    /** Find the active FIRING event for a (rule, device) pair (spec 4.2 #2). */
    AlertEvent findFiringEvent(Long ruleId, String deviceKey);
    /** Get event by ID for API rendering (spec 4.2 #4). */
    AlertEvent getById(long id);
}
