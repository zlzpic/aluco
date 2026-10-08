package com.aluco.server.alerting;

import com.aluco.server.common.Page;

public interface AlertEventService {
    void fire(AlertRule rule, String deviceKey, double value, long ts);
    /** Flip the open FIRING event to RESOLVED and return it (null if none was open). */
    AlertEvent resolve(AlertRule rule, String deviceKey, long ts);
    void ack(long eventId);
    Page<AlertEvent> query(String status, String deviceKey, int page, int size);
    /** Persist and return the new FIRING event (needed for WS push). */
    AlertEvent fireReturning(AlertRule rule, String deviceKey, double value, long ts);
    /** Get event by ID for API rendering (spec 4.2 #4). */
    AlertEvent getById(long id);
}
