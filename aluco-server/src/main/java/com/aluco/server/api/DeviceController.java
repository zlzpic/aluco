package com.aluco.server.api;

import com.aluco.server.alerting.AlertEvent;
import com.aluco.server.common.DeviceState;
import com.aluco.server.common.Page;
import com.aluco.server.device.CreateDeviceRequest;
import com.aluco.server.device.Device;
import com.aluco.server.device.DeviceCommandService;
import com.aluco.server.device.DeviceService;
import com.aluco.server.device.StateStore;
import com.aluco.server.processing.TimeSeriesStore;
import com.aluco.server.command.Command;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    public record SetIntervalRequest(@NotNull @Min(1) @Max(3600) Integer intervalSec) {}

    private final DeviceService deviceService;
    private final DeviceCommandService commandService;
    private final StateStore stateStore;
    private final TimeSeriesStore timeSeriesStore;

    public DeviceController(DeviceService deviceService,
                            DeviceCommandService commandService,
                            StateStore stateStore,
                            TimeSeriesStore timeSeriesStore) {
        this.deviceService = deviceService;
        this.commandService = commandService;
        this.stateStore = stateStore;
        this.timeSeriesStore = timeSeriesStore;
    }

    /** #2 create; token returned only here */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateDeviceRequest req) {
        Device d = deviceService.create(req);
        Map<String, Object> body = new java.util.HashMap<>(deviceView(d, null));
        body.put("token", d.getToken());
        return body;
    }

    /** #3 paged list, keyword fuzzy on device_key/name */
    @GetMapping
    public Page<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        Page<Device> p = deviceService.page(keyword, page, size);
        Map<String, DeviceState> states = stateStore.list(
                        p.list().stream().map(Device::getDeviceKey).toList()).stream()
                .collect(java.util.stream.Collectors.toMap(DeviceState::deviceKey, s -> s));
        return Page.of(p.list().stream().map(d -> deviceView(d, states.get(d.getDeviceKey()))).toList(),
                p.total(), p.page(), p.size());
    }

    /** #4 detail with online + lastSeenAt (no token) */
    @GetMapping("/{deviceKey}")
    public Map<String, Object> detail(@PathVariable String deviceKey) {
        Device d = deviceService.get(deviceKey);
        return deviceView(d, stateStore.get(deviceKey).orElse(null));
    }

    /** #5 delete; state cascades, telemetry/alert_event preserved */
    @DeleteMapping("/{deviceKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String deviceKey) {
        deviceService.delete(deviceKey);
    }

    /** #6 latest state from the StateStore seam */
    @GetMapping("/{deviceKey}/state")
    public DeviceState state(@PathVariable String deviceKey) {
        return deviceService.getState(deviceKey);
    }

    /** #7 history with down-sampling; raw capped with X-Truncated header */
    @GetMapping("/{deviceKey}/telemetry")
    public ResponseEntity<Map<String, Object>> telemetry(
            @PathVariable String deviceKey,
            @RequestParam @NotBlank @Pattern(regexp = "[a-zA-Z][a-zA-Z0-9_]{0,63}") String metric,
            @RequestParam long from,
            @RequestParam long to,
            @RequestParam @Pattern(regexp = "raw|1m|5m|1h|1d") String interval) {
        deviceService.get(deviceKey); // 404 guard
        TimeSeriesStore.QueryResult result = timeSeriesStore.query(deviceKey, metric, from, to, interval);
        Map<String, Object> body = Map.of(
                "metric", metric,
                "points", result.points().stream()
                        .map(p -> Map.of("ts", p.ts(), "val", p.val()))
                        .toList(),
                "truncated", result.truncated());
        return ResponseEntity.ok()
                .header("X-Truncated", String.valueOf(result.truncated()))
                .body(body);
    }

    /** #14 downlink SET_INTERVAL (revised response: cmdId only) */
    @PostMapping("/{deviceKey}/commands/set-interval")
    public Map<String, Object> setInterval(@PathVariable String deviceKey,
                                           @Valid @RequestBody SetIntervalRequest req) {
        String cmdId = commandService.setReportInterval(deviceKey, req.intervalSec());
        return Map.of("cmdId", cmdId);
    }

    /** #16 rotate device token (v2 spec 5.2.4): old token invalidated immediately */
    @PostMapping("/{deviceKey}/token/rotate")
    public Map<String, Object> rotateToken(@PathVariable String deviceKey) {
        String newToken = deviceService.rotateToken(deviceKey);
        return Map.of("token", newToken);
    }

    /** #17 get command by cmdId */
    @GetMapping("/commands/{cmdId}")
    public Command getCommand(@PathVariable String cmdId) {
        return commandService.getCommand(cmdId);
    }

    /** #18 list recent commands for a device */
    @GetMapping("/{deviceKey}/commands")
    public Page<Command> listCommands(@PathVariable String deviceKey,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        // For simplicity, return all commands (pagination can be added to CommandService if needed)
        var commands = commandService.listCommands(deviceKey);
        return Page.of(commands, commands.size(), page, size);
    }

    private Map<String, Object> deviceView(Device d, DeviceState s) {
        Map<String, Object> view = new java.util.HashMap<>();
        view.put("id", d.getId());
        view.put("deviceKey", d.getDeviceKey());
        view.put("name", d.getName());
        view.put("siteId", d.getSiteId());
        view.put("createdAt", d.getCreatedAt() == null ? null : d.getCreatedAt().toEpochMilli());
        view.put("online", s != null && s.online());
        view.put("lastSeenAt", s == null ? null : s.lastSeenAt());
        return view;
    }
}
