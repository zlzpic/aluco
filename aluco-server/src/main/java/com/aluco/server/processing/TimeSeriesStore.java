package com.aluco.server.processing;

import com.aluco.server.common.TelemetryPoint;

import java.util.List;

/**
 * Seam 2: historical telemetry access.
 * v1 = MySQL; v2 = TimescaleDB replacement point (spec 7.2).
 */
public interface TimeSeriesStore {
    void writeBatch(List<TelemetryPoint> points);

    /**
     * Query telemetry with optional down-sampling.
     *
     * @param interval raw | 1m | 5m | 1h | 1d.
     *                 raw returns original points (capped at 10,000);
     *                 others return time-bucketed AVG points.
     * @return QueryResult with points list and truncation flag
     */
    QueryResult query(String deviceKey, String metric, long from, long to, String interval);

    /** Result container: points + truncation flag (spec 4.3). */
    record QueryResult(List<TelemetryPoint> points, boolean truncated) {}
}
