package com.aluco.bench;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 阶梯压测配置 (v2 spec 4.5.2, 预注册阈值，不可事后修改).
 *
 * 压测流程：
 *   warmup(60s) → steady(30min) → cool-down → next level
 *
 * 采集指标：
 *   - aluco.ingest.received (counter)
 *   - aluco.sink.dropped (counter)  ← 门控触发条件
 *   - aluco.e2e.latency (histogram) ← 门控触发条件
 *   - aluco.persist.batch.size (summary)
 */
public record BenchConfig(
        /** 设备阶梯: 1k → 5k → 10k */
        int[] deviceLevels,
        /** 每档稳态时长（分钟） */
        int steadyStateMinutes,
        /** 预热时长（秒） */
        int warmupSeconds,
        /** 指标种类数（temp + humidity = 2） */
        int metricsPerDevice,
        /** Server Prometheus endpoint */
        String prometheusUrl,
        /** 是否生成 CSV 报告 */
        boolean csvEnabled,
        /** CSV 报告路径 */
        String csvPath
) {
    public static final BenchConfig V2_SPEC = new BenchConfig(
            new int[]{1_000, 5_000, 10_000},
            30,
            60,
            2,
            "http://localhost:8080/actuator/prometheus",
            true,
            "docs/benchmarks/v2-baseline.csv"
    );

    public int expectedIngestionRate(int devices) {
        return devices * metricsPerDevice; // 1Hz × 2 metrics = 2× device count
    }

    public Duration steadyState() {
        return Duration.ofMinutes(steadyStateMinutes);
    }

    public Duration warmup() {
        return Duration.ofSeconds(warmupSeconds);
    }
}
