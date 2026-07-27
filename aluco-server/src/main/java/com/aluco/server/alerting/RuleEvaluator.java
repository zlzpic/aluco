package com.aluco.server.alerting;

public interface RuleEvaluator {
    EvaluationResult evaluate(AlertRule rule, String deviceKey, double value, long ts);
}
