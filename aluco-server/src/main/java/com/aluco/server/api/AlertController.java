package com.aluco.server.api;

import com.aluco.server.alerting.AlertEvent;
import com.aluco.server.alerting.AlertEventService;
import com.aluco.server.common.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertEventService eventService;

    public AlertController(AlertEventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    public Page<AlertEvent> list(@RequestParam(required = false) String status,
                                 @RequestParam(required = false) String deviceKey,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return eventService.query(status, deviceKey, page, size);
    }

    /** #13 ack; only FIRING can be acked, otherwise 409 */
    @PostMapping("/{id}/ack")
    public AlertEvent ack(@PathVariable long id) {
        eventService.ack(id);
        return eventService.getById(id);
    }
}
