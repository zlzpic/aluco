package com.aluco.server.alerting;

import com.aluco.server.common.Page;

import java.util.List;

public interface AlertRuleService {
    AlertRule create(CreateRuleRequest req);
    void setEnabled(long ruleId, boolean enabled);
    void delete(long ruleId);
    Page<AlertRule> page(int page, int size);
    List<AlertRule> listEnabled();
}
