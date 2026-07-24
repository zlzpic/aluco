package com.aluco.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration smoke test (spec 7.4.5, fixed as @SpringBootTest + H2):
 * login -> create device -> create rule -> query empty alert list, all 2xx.
 * MQTT ingestion and the offline sweep are disabled via application-test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SmokeIT {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void fullManagementFlowReturns200() {
        // 1. login
        ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login",
                Map.of("username", "admin", "password", "admin123"), Map.class);
        assertThat(login.getStatusCode().is2xxSuccessful()).isTrue();
        String token = (String) login.getBody().get("token");
        assertThat(token).isNotBlank();

        HttpHeaders auth = new HttpHeaders();
        auth.setBearerAuth(token);
        auth.setContentType(MediaType.APPLICATION_JSON);

        // 2. create device
        ResponseEntity<Map> device = rest.exchange("/api/v1/devices", HttpMethod.POST,
                new HttpEntity<>(Map.of("deviceKey", "TH-0001", "name", "Sensor 1",
                        "siteId", "site-01"), auth), Map.class);
        assertThat(device.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat((String) device.getBody().get("token")).isNotBlank();

        // 3. create rule (temp > 30, all devices)
        ResponseEntity<Map> rule = rest.exchange("/api/v1/rules", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "temp high", "metric", "temp",
                        "op", "GT", "threshold", 30.0), auth), Map.class);
        assertThat(rule.getStatusCode().is2xxSuccessful()).isTrue();

        // 4. empty alert list
        ResponseEntity<Map> alerts = rest.exchange("/api/v1/alerts", HttpMethod.GET,
                new HttpEntity<>(auth), Map.class);
        assertThat(alerts.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(((Number) alerts.getBody().get("total")).longValue()).isZero();

        // 5. unauthenticated request is rejected
        ResponseEntity<String> denied = rest.getForEntity("/api/v1/devices", String.class);
        assertThat(denied.getStatusCode().value()).isEqualTo(401);
    }
}