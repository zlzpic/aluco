package com.aluco.server.alerting;

/** Outcome of one evaluation step of the (rule x device) state machine. */
public record EvaluationResult(Transition transition) {

    public enum Transition { NONE, FIRED, RESOLVED }

    public static final EvaluationResult NONE = new EvaluationResult(Transition.NONE);
    public static final EvaluationResult FIRED = new EvaluationResult(Transition.FIRED);
    public static final EvaluationResult RESOLVED = new EvaluationResult(Transition.RESOLVED);
}
