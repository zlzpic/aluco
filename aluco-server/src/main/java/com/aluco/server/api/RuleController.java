package com.aluco.server.api;

import com.aluco.server.alerting.AlertRule;
import com.aluco.server.alerting.AlertRuleService;
import com.aluco.server.alerting.CreateRuleRequest;
import com.aluco.server.common.Page;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/rules")
public class RuleController {

    public record EnabledRequest(@NotNull Boolean enabled) {}

    private final AlertRuleService ruleService;

    public RuleController(AlertRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @GetMapping
    public Page<AlertRule> list(@RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return ruleService.page(page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AlertRule create(@Valid @RequestBody CreateRuleRequest req) {
        return ruleService.create(req);
    }

    @PatchMapping("/{id}/enabled")
    public AlertRule setEnabled(@PathVariable long id,
                                @Valid @RequestBody EnabledRequest req) {
        ruleService.setEnabled(id, req.enabled());
        // return the updated rule; page() rows come from the DB so re-read is fresh
        return ruleService.page(1, Integer.MAX_VALUE).list().stream()
                .filter(r -> r.getId() == id).findFirst()
                .orElseThrow(() -> com.aluco.server.common.BizException
                        .notFound("RULE_NOT_FOUND", "no such rule: " + id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        ruleService.delete(id);
    }
}
