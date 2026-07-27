package com.aluco.server.alerting;

import com.aluco.server.common.Page;

public interface AlertEventService {
    void fire(AlertRule rule, String deviceKey, double value, long ts);
    void resolve(AlertRule rule, String deviceKey, long ts);
    void ack(long eventId);
    Page<AlertEvent> query(String status, String deviceKey, int page, int size);
}
