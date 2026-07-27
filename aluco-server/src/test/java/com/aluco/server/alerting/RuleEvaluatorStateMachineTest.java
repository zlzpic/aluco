package com.aluco.server.alerting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** All branches of the (rule x device) alert state machine (spec 7.3.6 / 7.4.5). */
class RuleEvaluatorStateMachineTest {

    private DefaultRuleEvaluator evaluator;
    private AlertRule rule;

    @BeforeEach
    void setUp() {
        evaluator = new DefaultRuleEvaluator();
        rule = new AlertRule("temp high", "temp", AlertRule.Op.GT, 30.0, null);
        ReflectionTestUtils.setField(rule, "id", 1L);
    }

    @Test
    void normalPlusViolatedFires() {
        assertThat(evaluator.evaluate(rule, "TH-0001", 35.0, 1L).transition())
                .isEqualTo(EvaluationResult.Transition.FIRED);
    }

    @Test
    void firingPlusViolatedAgainIsDeduplicated() {
        evaluator.evaluate(rule, "TH-0001", 35.0, 1L); // -> FIRING
        assertThat(evaluator.evaluate(rule, "TH-0001", 36.0, 2L).transition())
                .isEqualTo(EvaluationResult.Transition.NONE);
    }

    @Test
    void firingPlusRecoveredResolves() {
        evaluator.evaluate(rule, "TH-0001", 35.0, 1L);
        assertThat(evaluator.evaluate(rule, "TH-0001", 25.0, 2L).transition())
                .isEqualTo(EvaluationResult.Transition.RESOLVED);
    }

    @Test
    void normalPlusNormalStaysQuiet() {
        assertThat(evaluator.evaluate(rule, "TH-0001", 20.0, 1L).transition())
                .isEqualTo(EvaluationResult.Transition.NONE);
    }

    @Test
    void statesArePerRulePerDevice() {
        evaluator.evaluate(rule, "TH-0001", 35.0, 1L); // TH-0001 FIRING
        // same rule, another device, violating value -> independent FIRED
        assertThat(evaluator.evaluate(rule, "TH-0002", 35.0, 1L).transition())
                .isEqualTo(EvaluationResult.Transition.FIRED);
    }

    @Test
    void restoredFiringStateDoesNotRefire() {
        // simulate restart recovery: pair re-seeded as FIRING
        evaluator.restore(1L, "TH-0001");
        assertThat(evaluator.evaluate(rule, "TH-0001", 35.0, 1L).transition())
                .isEqualTo(EvaluationResult.Transition.NONE); // no duplicate alert
        // and it can still resolve afterwards
        assertThat(evaluator.evaluate(rule, "TH-0001", 20.0, 2L).transition())
                .isEqualTo(EvaluationResult.Transition.RESOLVED);
    }

    @Test
    void evictedRuleCanFireFresh() {
        evaluator.evaluate(rule, "TH-0001", 35.0, 1L); // FIRING
        evaluator.evictRule(1L);                        // rule deleted/re-created
        assertThat(evaluator.evaluate(rule, "TH-0001", 35.0, 2L).transition())
                .isEqualTo(EvaluationResult.Transition.FIRED);
    }

    @Test
    void allOperatorsWork() {
        assertThat(new AlertRule("r", "m", AlertRule.Op.GTE, 30, null).violatedBy(30)).isTrue();
        assertThat(new AlertRule("r", "m", AlertRule.Op.LT, 30, null).violatedBy(29.9)).isTrue();
        assertThat(new AlertRule("r", "m", AlertRule.Op.LTE, 30, null).violatedBy(30)).isTrue();
        assertThat(new AlertRule("r", "m", AlertRule.Op.EQ, 30, null).violatedBy(30)).isTrue();
        assertThat(new AlertRule("r", "m", AlertRule.Op.GT, 30, null).violatedBy(30)).isFalse();
    }

    @Test
    void deviceScopedRuleOnlyAppliesToThatDevice() {
        AlertRule scoped = new AlertRule("r", "temp", AlertRule.Op.GT, 30, "TH-0001");
        assertThat(scoped.appliesTo("TH-0001")).isTrue();
        assertThat(scoped.appliesTo("TH-0002")).isFalse();
        AlertRule global = new AlertRule("r", "temp", AlertRule.Op.GT, 30, null);
        assertThat(global.appliesTo("anything")).isTrue();
    }
}