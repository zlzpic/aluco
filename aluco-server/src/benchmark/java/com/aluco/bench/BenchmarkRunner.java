package com.aluco.bench;

import com.aluco.bench.report.BenchmarkReport;
import com.aluco.bench.report.CsvReportWriter;
import com.aluco.bench.report.ReportWriter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 *阶梯压测工具 (v2 spec 4.5.2):
 *  1k → 5k → 10k 台 × 1Hz × 2 指标（2k/10k/20k 行每秒写入）
 *  每档稳态 30 分钟；采集 sink 丢弃数、e2e p50/p99、批量写吞吐
 *
 * 用法：作为独立 JAR 运行，或通过 Maven plugin 启动
 */
@Component
public class阶梯压测Runner implements CommandLineRunner {

    private final MeterRegistry meterRegistry;
    private final MetricsEndpoint metricsEndpoint;
    private final RestTemplate restTemplate;

    // 压测配置（v2 spec 预注册，不可事后修改）
    private static final int[] DEVICE_LEVELS = {1_000, 5_000, 10_000};
    private static final int STEADY_STATE_MINUTES = 30;
    private static final Duration STEADY_STATE = Duration.ofMinutes(STEADY_STATE_MINUTES);
    private static final int METRICS_PER_DEVICE = 2; // temp + humidity
    private static final String SERVER_BASE = "http://localhost:8080";

    public阶梯压测Runner(MeterRegistry meterRegistry,
                            MetricsEndpoint metricsEndpoint,
                            RestTemplate restTemplate) {
        this.meterRegistry = meterRegistry;
        this.metricsEndpoint = metricsEndpoint;
        this.restTemplate = restTemplate;
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("=== Aluco Benchmark Suite (v2 spec 4.5) ===");
        System.out.println("Levels: " + Arrays.toString(DEVICE_LEVELS));
        System.out.println("Steady state: " + STEADY_STATE_MINUTES + " min per level");
        System.out.println();

        List<BenchmarkReport> reports = new ArrayList<>();
        ReportWriter reportWriter = new CsvReportWriter("benchmarks/v2-baseline.csv");

        for (int devices : DEVICE_LEVELS) {
            System.out.printf("=== Starting level: %,d devices ===%n", devices);
            BenchmarkReport report = runLevel(devices);
            reports.add(report);
            reportWriter.write(report);
            System.out.printf("=== Level %,d complete: e2e p99=%.2fms, sinkDropped=%d ===%n%n",
                    devices, report.e2eP99Ms(), report.sinkDropped());
        }

        // 生成 Markdown 报告
        generateMarkdownReport(reports);
        System.out.println("Benchmark complete. Reports written to benchmarks/");
    }

    /**
     * 运行单档压测：预热 → 稳态采集 → 采样查询
     */
    private BenchmarkReport runLevel(int devices) throws Exception {
        int warmupSeconds = 60;
        System.out.printf("Warm-up: %,d devices × 1Hz × %d metrics for %ds...%n",
                devices, METRICS_PER_DEVICE, warmupSeconds);

        // TODO: 启动 aluco-sim（通过 ProcessBuilder）
        // 这里需要调用 aluco-sim 的 ProcessBuilder，传入 --devices/--interval/--metrics

        Thread.sleep(warmupSeconds * 1000L);

        System.out.printf("Steady state: collecting metrics for %d min...%n", STEADY_STATE_MINUTES);
        long startMs = System.currentTimeMillis();

        // 采样：每 5s 采集一次指标
        List<Sample> samples = new ArrayList<>();
        long endMs = startMs + STEADY_STATE.toMillis();
        while (System.currentTimeMillis() < endMs) {
            samples.add(collectSample());
            Thread.sleep(5000);
        }

        // 计算统计值
        double[] latencies = samples.stream().mapToDouble(Sample::e2eLatencyMs).toArray();
        double p50 = percentile(latencies, 50);
        double p99 = percentile(latencies, 99);

        long sinkDropped = samples.stream().mapToLong(Sample::sinkDropped)
                .max().orElse(0) - samples.stream().mapToLong(Sample::sinkDropped)
                .min().orElse(0);

        long batchSize = samples.stream().mapToLong(Sample::batchSize).average().orElse(0);

        // 查询测试：单设备 1 小时历史
        long queryStart = System.currentTimeMillis();
        // TODO: 调用 GET /api/v1/devices/TH-0001/telemetry?metric=temp&from=...&to=...&interval=raw
        long queryMs = System.currentTimeMillis() - queryStart;

        // TODO: 停止 aluco-sim（发送 SIGTERM）

        return new BenchmarkReport(
                devices,
                STEADY_STATE_MINUTES,
                p50,
                p99,
                sinkDropped,
                batchSize,
                queryMs,
                System.currentTimeMillis()
        );
    }

    /**
     * 采集一次指标快照
     */
    private Sample collectSample() {
        // 从 Prometheus endpoint 拉取指标
        try {
            String metrics = restTemplate.getForObject(SERVER_BASE + "/actuator/prometheus", String.class);

            double e2eP99 = extractPercentile(metrics, "aluco_e2e_latency_seconds", 0.99) * 1000;
            long sinkDropped = extractCounter(metrics, "aluco_sink_dropped_total");
            double batchSize = extractSummary(metrics, "aluco_persist_batch_size_count", "mean");

            return new Sample(e2eP99, sinkDropped, (long) batchSize);
        } catch (Exception e) {
            return new Sample(0, 0, 0);
        }
    }

    private double extractPercentile(String metrics, String metricName, double quantile) {
        // 解析 Prometheus 文本格式的直方图分位数
        // 简化实现：实际应解析 TYPE metric histogram 和 quantile 行
        return 0;
    }

    private long extractCounter(String metrics, String counterName) {
        return 0;
    }

    private double extractSummary(String metrics, String summaryName, String stat) {
        return 0;
    }

    private double percentile(double[] values, double p) {
        Arrays.sort(values);
        int idx = (int) Math.ceil(p / 100.0 * values.length) - 1;
        return values[Math.max(0, idx)];
    }

    private void generateMarkdownReport(List<BenchmarkReport> reports) {
        // 生成 docs/benchmarks/v2-baseline.md
    }

    record Sample(double e2eLatencyMs, long sinkDropped, long batchSize) {}
}
