package com.aluco.sim;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * aluco-sim: configurable device fleet simulator (spec chapter 9).
 *
 * Example:
 *   java -jar aluco-sim-1.0.0.jar --broker tcp://localhost:1883 --devices 100 \
 *        --interval 1s --ramp 10s --api http://localhost:8080/api/v1
 */
@Command(name = "aluco-sim", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Aluco device fleet simulator")
public class AlucoSim implements Runnable {

    @Option(names = "--broker", defaultValue = "tcp://localhost:1883",
            description = "EMQX address (default: ${DEFAULT-VALUE})")
    String broker;

    @Option(names = "--site", defaultValue = "site-01",
            description = "topic first segment (default: ${DEFAULT-VALUE})")
    String site;

    @Option(names = "--devices", defaultValue = "100",
            description = "number of simulated devices (default: ${DEFAULT-VALUE})")
    int devices;

    @Option(names = "--device-prefix", defaultValue = "TH-",
            description = "device key prefix, 4-digit numbering (default: ${DEFAULT-VALUE})")
    String devicePrefix;

    @Option(names = "--interval", defaultValue = "1s",
            description = "per-device report interval, e.g. 500ms/1s/5s (default: ${DEFAULT-VALUE})")
    String interval;

    @Option(names = "--ramp", defaultValue = "10s",
            description = "total ramp-up duration to stagger device starts (default: ${DEFAULT-VALUE})")
    String ramp;

    @Option(names = "--metrics", defaultValue = "temp,humidity",
            description = "comma-separated metric set (default: ${DEFAULT-VALUE})")
    String metrics;

    @Option(names = "--spike-probability", defaultValue = "0.002",
            description = "per-point spike probability (default: ${DEFAULT-VALUE})")
    double spikeProbability;

    @Option(names = "--seed-devices", defaultValue = "true",
            description = "register devices via server REST at startup (default: ${DEFAULT-VALUE})")
    boolean seedDevices;

    @Option(names = "--api", defaultValue = "http://localhost:8080/api/v1",
            description = "server REST base, required when --seed-devices=true")
    String api;

    @Option(names = "--username", defaultValue = "admin")
    String username;

    @Option(names = "--password", defaultValue = "admin123")
    String password;

    private final AtomicLong sentTotal = new AtomicLong();
    private final AtomicLong failureTotal = new AtomicLong();
    private final List<SimDevice> fleet = new ArrayList<>();

    public static void main(String[] args) {
        System.exit(new CommandLine(new AlucoSim()).execute(args));
    }

    @Override
    public void run() {
        int intervalSec = (int) Math.max(1, parseDurationMs(interval) / 1000);
        long rampMs = parseDurationMs(ramp);
        String[] metricNames = metrics.split(",");

        if (seedDevices) {
            try {
                new DeviceSeeder(api).seed(username, password, site, devicePrefix, devices);
            } catch (Exception e) {
                System.err.println("[seeder] failed: " + e.getMessage()
                        + " (continuing anyway; unregistered devices will be dropped by server)");
            }
        }

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
                Math.min(devices, 64), r -> {
                    Thread t = new Thread(r, "aluco-sim-worker");
                    t.setDaemon(true);
                    return t;
                });

        // ramp-up: stagger device starts evenly over the ramp window (spec 9.1)
        for (int i = 1; i <= devices; i++) {
            String key = devicePrefix + String.format("%04d", i);
            SimDevice device = new SimDevice(broker, site, key, i, intervalSec, metricNames,
                    spikeProbability, sentTotal, failureTotal, scheduler);
            fleet.add(device);
            long delay = devices <= 1 ? 0 : rampMs * (i - 1) / (devices - 1);
            scheduler.schedule(device::start, delay, TimeUnit.MILLISECONDS);
        }

        // stats every 10s: sent total / current rate / failures / active devices (spec 9.2)
        ScheduledExecutorService stats = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aluco-sim-stats");
            t.setDaemon(true);
            return t;
        });
        stats.scheduleAtFixedRate(new StatsReporter(), 10, 10, TimeUnit.SECONDS);

        // graceful shutdown on SIGTERM (spec 9.2)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("shutting down, disconnecting " + fleet.size() + " devices...");
            fleet.forEach(SimDevice::stop);
            scheduler.shutdownNow();
            stats.shutdownNow();
        }));

        System.out.println("aluco-sim running: " + devices + " devices -> " + broker
                + " (site=" + site + ", interval=" + intervalSec + "s, metrics=" + metrics + ")");
    }

    /** Parses durations like "500ms", "1s", "2m" into milliseconds. */
    static long parseDurationMs(String text) {
        String t = text.trim().toLowerCase();
        if (t.endsWith("ms")) {
            return Long.parseLong(t.substring(0, t.length() - 2));
        }
        if (t.endsWith("s")) {
            return Long.parseLong(t.substring(0, t.length() - 1)) * 1000L;
        }
        if (t.endsWith("m")) {
            return Long.parseLong(t.substring(0, t.length() - 1)) * 60_000L;
        }
        return Long.parseLong(t); // bare number = ms
    }

    private class StatsReporter implements Runnable {
        private long lastSent = 0;

        @Override
        public void run() {
            long sent = sentTotal.get();
            long rate = (sent - lastSent) / 10;
            lastSent = sent;
            System.out.printf("[stats] sent=%d rate=%d/s failures=%d active=%d/%d%n",
                    sent, rate, failureTotal.get(), fleet.size(), devices);
        }
    }
}