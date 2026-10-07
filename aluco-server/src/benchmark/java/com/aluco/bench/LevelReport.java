package com.aluco.bench;

import java.time.Instant;

/**
 * 单档压测报告（v2 spec 4.5.2 产出物）.
 */
public record LevelReport(
        /** 设备数量 */
        int devices,
        /** 稳态采集窗口（秒） */
        int windowSeconds,
        /** 稳态开始时间 */
        Instant windowStart,
        /** 稳态结束时间 */
        Instant windowEnd,
        /** e2e 延迟 p50 (ms) */
        double e2eP50Ms,
        /** e2e 延迟 p99 (ms) */
        double e2eP99Ms,
        /** 窗口内 sink 丢弃总数（增量） */
        long sinkDropped,
        /** 批量写平均批大小（点） */
        double avgBatchSize,
        /** 单设备 1 小时 raw 查询 p95（ms） */
        long rawQueryP95Ms,
        /** telemetry 表体积（MB，可选） */
        long telemetrySizeMB
) {
    public boolean sinkDroppedTriggered() {
        return sinkDropped > 0;
    }

    public boolean e2eP99Triggered() {
        return e2eP99Ms > 1000.0;
    }

    public boolean rawQueryTriggered() {
        return rawQueryP95Ms > 500;
    }

    @Override
    public String toString() {
        return String.format(
                "LevelReport{devices=%,d, e2e p50=%.2fms, p99=%.2fms, sinkDropped=%d, batchSize=%.1f, queryP95=%dms}",
                devices, e2eP50Ms, e2eP99Ms, sinkDropped, avgBatchSize, rawQueryP95Ms
        );
    }
}
