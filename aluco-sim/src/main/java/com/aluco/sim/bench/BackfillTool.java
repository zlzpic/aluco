package com.aluco.sim.bench;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * aluco-sim backfill: 历史数据回填工具（v2 spec 4.5.3）.
 *
 * 直写数据库，不经 MQTT，用于快速填充大量历史数据到 telemetry 表。
 *
 * 参数：
 *   --devices N         设备数
 *   --days D           回填天数
 *   --rows-per-day R    每天每设备行数
 *   --batch 5000        批大小（默认 5000）
 *   --checkpoint ./backfill.ckpt  断点文件路径
 *   --target mysql|timescale（v2 P2 时支持 timescale）
 *
 * 进程生命周期：一次性进程，正常退出码 0；支持 SIGINT 优雅退出（退出码 130）
 */
@Command(name = "backfill",
        mixinStandardHelpOptions = true,
        description = "Historical telemetry backfill tool (v2 spec 4.5.3)")
public class BackfillTool implements Runnable {

    @Option(names = "--devices", defaultValue = "1000",
            description = "number of devices (default: ${DEFAULT-VALUE})")
    int devices;

    @Option(names = "--days", defaultValue = "30",
            description = "backfill days (default: ${DEFAULT-VALUE})")
    int days;

    @Option(names = "--rows-per-day", defaultValue = "86400",
            description = "rows per device per day (default: ${DEFAULT-VALUE} = 1Hz)")
    int rowsPerDay;

    @Option(names = "--batch", defaultValue = "5000",
            description = "JDBC batch size (default: ${DEFAULT-VALUE})")
    int batchSize;

    @Option(names = "--checkpoint", defaultValue = "./backfill.ckpt",
            description = "checkpoint file for resume (default: ${DEFAULT-VALUE})")
    File checkpointFile;

    @Option(names = "--target", defaultValue = "mysql",
            description = "target: mysql | timescale (default: ${DEFAULT-VALUE})")
    String target;

    @Option(names = "--jdbc-url", defaultValue = "jdbc:mysql://localhost:3306/aluco",
            description = "JDBC URL")
    String jdbcUrl;

    @Option(names = "--username", defaultValue = "root",
            description = "DB username")
    String username;

    @Option(names = "--password", defaultValue = "123456",
            description = "DB password")
    String password;

    @Option(names = "--device-prefix", defaultValue = "TH-",
            description = "device key prefix (default: ${DEFAULT-VALUE})")
    String devicePrefix;

    // 统计
    private final AtomicLong totalRows = new AtomicLong();
    private final AtomicLong skippedDupes = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    public static void main(String[] args) {
        int exitCode = new CommandLine(new BackfillTool()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        System.out.printf("Backfill: %,d devices × %d days × %,d rows/day = %,d total rows%n",
                devices, days, rowsPerDay, (long) devices * days * rowsPerDay);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nReceived SIGINT, writing checkpoint...");
            writeCheckpoint();
            System.exit(130);
        }));

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            conn.setAutoCommit(false);

            // 加载断点
            Checkpoint ckpt = loadCheckpoint();
            int startDevice = ckpt.lastDeviceId;
            Instant startTime = Instant.now();

            // 批量写入
            for (int deviceIdx = startDevice; deviceIdx <= devices; deviceIdx++) {
                String deviceKey = devicePrefix + String.format("%04d", deviceIdx);
                int rowsWritten = writeDeviceData(conn, deviceIdx, deviceKey);

                if (deviceIdx % 100 == 0) {
                    long total = totalRows.get();
                    double rate = total / Math.max(1, Duration.between(startTime, Instant.now()).getSeconds());
                    System.out.printf("Progress: device %d/%d, total=%,d (%.0f rows/s)%n",
                            deviceIdx, devices, total, rate);
                }

                // 每 1000 台设备写一次断点
                if (deviceIdx % 1000 == 0) {
                    writeCheckpointPartial(deviceIdx);
                }
            }

            conn.commit();
            System.out.printf("Backfill complete: %,d rows (%s dupes skipped, %s errors)%n",
                    totalRows.get(), skippedDupes.get(), errors.get());

        } catch (Exception e) {
            System.err.println("Backfill failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 写单个设备的数据（按日分批）
     */
    private int writeDeviceData(Connection conn, int deviceIdx, String deviceKey) throws Exception {
        // 从数据库查询实际的 device_id（auto-increment PK）
        long deviceId;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM device WHERE device_key = ?")) {
            ps.setString(1, deviceKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    System.out.println("  Skipping " + deviceKey + " (not found in device table)");
                    return 0;
                }
                deviceId = rs.getLong(1);
            }
        }

        int totalWritten = 0;
        LocalDateTime dayStart = LocalDateTime.now().minusDays(days);

        for (int d = 0; d < days; d++) {
            LocalDateTime dayBegin = dayStart.plusDays(d);
            int written = insertBatch(conn, deviceId, deviceKey, dayBegin, rowsPerDay);
            totalWritten += written;
        }

        return totalWritten;
    }

    /**
     * 批量插入（JDBC batchUpdate）
     */
    private int insertBatch(Connection conn, long deviceId, String deviceKey,
                            LocalDateTime dayBegin, int rows) throws Exception {
        String sql = "INSERT INTO telemetry (device_id, ts, metric, val) VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE val = val";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            Random rand = new Random(deviceId * 1000L);
            int batchCount = 0;

            for (int i = 0; i < rows; i++) {
                LocalDateTime ts = dayBegin.plusSeconds(i);
                double temp = 22.0 + 3.0 * Math.sin(i / 600.0) + rand.nextGaussian() * 0.3;
                double humidity = 55.0 + 10.0 * Math.sin(i / 1020.0 + deviceId * 0.7) + rand.nextGaussian();

                // temp metric
                ps.setLong(1, deviceId);
                ps.setTimestamp(2, Timestamp.valueOf(ts));
                ps.setString(3, "temp");
                ps.setDouble(4, temp);
                ps.addBatch();

                // humidity metric
                ps.setLong(1, deviceId);
                ps.setTimestamp(2, Timestamp.valueOf(ts));
                ps.setString(3, "humidity");
                ps.setDouble(4, humidity);
                ps.addBatch();

                batchCount += 2;

                if (batchCount >= batchSize) {
                    int[] results = ps.executeBatch();
                    totalRows.addAndGet(batchCount);
                    batchCount = 0;
                }
            }

            if (batchCount > 0) {
                ps.executeBatch();
                totalRows.addAndGet(batchCount);
            }

            return rows * 2; // 2 metrics per row
        }
    }

    /**
     * 断点文件格式: lastDeviceId=<N>
     */
    private Checkpoint loadCheckpoint() {
        if (!checkpointFile.exists()) {
            return new Checkpoint(0);
        }
        try (BufferedReader br = new BufferedReader(new FileReader(checkpointFile))) {
            String line = br.readLine();
            if (line != null && line.startsWith("lastDeviceId=")) {
                int id = Integer.parseInt(line.substring("lastDeviceId=".length()));
                System.out.println("Resuming from device " + id);
                return new Checkpoint(id);
            }
        } catch (Exception e) {
            System.err.println("Failed to load checkpoint, starting from 0: " + e.getMessage());
        }
        return new Checkpoint(0);
    }

    private void writeCheckpoint() {
        writeCheckpointPartial(devices);
    }

    private synchronized void writeCheckpointPartial(int deviceId) {
        try (PrintWriter pw = new PrintWriter(checkpointFile)) {
            pw.println("lastDeviceId=" + deviceId);
            pw.flush();
        } catch (Exception e) {
            System.err.println("Failed to write checkpoint: " + e.getMessage());
        }
    }

    private record Checkpoint(int lastDeviceId) {}
}
