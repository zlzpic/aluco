package com.aluco.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Registers simulated devices through the server REST API at startup
 * (spec 9.1 --seed-devices). Duplicate device_keys (409) are tolerated so
 * the simulator can restart freely.
 */
public class DeviceSeeder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiBase; // e.g. http://localhost:8080/api/v1
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public DeviceSeeder(String apiBase) {
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
    }

    public void seed(String username, String password, String siteId,
                     String devicePrefix, int count) throws Exception {
        String token = login(username, password);
        int created = 0, existed = 0;
        for (int i = 1; i <= count; i++) {
            String key = devicePrefix + String.format("%04d", i);
            String body = MAPPER.writeValueAsString(java.util.Map.of(
                    "deviceKey", key, "name", "Sim " + key, "siteId", siteId));
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/devices"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 201 || resp.statusCode() == 200) {
                created++;
            } else if (resp.statusCode() == 409) {
                existed++;
            } else {
                System.err.println("[seeder] register " + key + " -> HTTP " + resp.statusCode()
                        + " " + resp.body());
            }
        }
        System.out.println("[seeder] done: " + created + " created, " + existed + " already existed");
    }

    private String login(String username, String password) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(
                                java.util.Map.of("username", username, "password", password))))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("login failed: HTTP " + resp.statusCode()
                    + " " + resp.body());
        }
        JsonNode node = MAPPER.readTree(resp.body());
        return node.path("token").asText();
    }
}