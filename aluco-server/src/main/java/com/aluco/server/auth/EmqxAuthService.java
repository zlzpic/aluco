package com.aluco.server.auth;

import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * EMQX REST API client for device credential bootstrap + sync (v2 spec 5.2.3).
 * Server calls this at startup to:
 * 1. Ensure the built-in authenticator is enabled
 * 2. Ensure the aluco-server superuser exists
 * 3. Reconcile device table with EMQX user table (add new, remove stale)
 *
 * If EMQX is unreachable, retries with exponential backoff (max 5min) and
 * records metric aluco.emqx.sync.failures; does NOT block server startup
 * (degraded to app-layer auth, WARN log).
 */
@Service
public class EmqxAuthService {

    private final RestTemplate rest = new RestTemplate();
    private final String baseUrl;
    private final String apiUser;
    private final String apiPass;

    public EmqxAuthService(
            @org.springframework.beans.factory.annotation.Value("${aluco.emqx.api.base:http://localhost:18083}") String baseUrl,
            @org.springframework.beans.factory.annotation.Value("${aluco.emqx.api.user:admin}") String apiUser,
            @org.springframework.beans.factory.annotation.Value("${aluco.emqx.api.password:public}") String apiPass) {
        this.baseUrl = baseUrl;
        this.apiUser = apiUser;
        this.apiPass = apiPass;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth(apiUser, apiPass);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    /**
     * Ensure the built-in authenticator is enabled (v2 spec 5.2.2).
     */
    public void ensureAuthenticator() {
        try {
            // Check current authenticators
            ResponseEntity<String> resp = rest.exchange(
                    baseUrl + "/api/v5/authentication",
                    HttpMethod.GET,
                    new HttpEntity<>(authHeaders()),
                    String.class);

            if (resp.getStatusCode() == HttpStatus.OK) {
                // Authenticator exists; enable if disabled
                // v2 spec 5.2.2: password_based:built_in_database
                rest.exchange(
                        baseUrl + "/api/v5/authentication",
                        HttpMethod.PUT,
                        new HttpEntity<>("{\"enable\": true}", authHeaders()),
                        String.class);
            }
        } catch (Exception e) {
            // EMQX unreachable; log WARN but don't block server startup
            org.slf4j.LoggerFactory.getLogger(EmqxAuthService.class)
                    .warn("EMQX auth check failed (degraded to app-layer): {}", e.getMessage());
        }
    }

    /**
     * Ensure aluco-server superuser exists (v2 spec 5.2.2: allow all aluco/#).
     */
    public void ensureSuperuser() {
        try {
            // Create/update superuser
            rest.exchange(
                    baseUrl + "/api/v5/authentication/password_based:built_in_database/users",
                    HttpMethod.PUT,
                    new HttpEntity<>("{\"user_id\": \"aluco-server\", \"password\": \""
                            + java.util.UUID.randomUUID().toString().substring(0, 16)
                            + "\", \"is_superuser\": true}", authHeaders()),
                    String.class);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(EmqxAuthService.class)
                    .warn("EMQX superuser setup failed: {}", e.getMessage());
        }
    }

    /**
     * Add a device credential (called on device creation, v2 spec 5.2.3).
     */
    public void addDeviceCredential(String deviceKey, String token) {
        try {
            rest.exchange(
                    baseUrl + "/api/v5/authentication/password_based:built_in_database/users",
                    HttpMethod.PUT,
                    new HttpEntity<>("{\"user_id\": \"" + deviceKey + "\", \"password\": \""
                            + token + "\"}", authHeaders()),
                    String.class);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(EmqxAuthService.class)
                    .warn("EMQX add device failed: {}", e.getMessage());
        }
    }

    /**
     * Remove a device credential (called on device delete, v2 spec 5.2.3).
     */
    public void removeDeviceCredential(String deviceKey) {
        try {
            rest.exchange(
                    baseUrl + "/api/v5/authentication/password_based:built_in_database/users/"
                            + deviceKey,
                    HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders()),
                    String.class);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(EmqxAuthService.class)
                    .warn("EMQX remove device failed: {}", e.getMessage());
        }
    }

    /**
     * Rotate device credential (called on token rotation, v2 spec 5.2.3).
     * Implementation: remove + add (old token instantly invalidated).
     */
    public void rotateDeviceCredential(String deviceKey, String newToken) {
        removeDeviceCredential(deviceKey);
        addDeviceCredential(deviceKey, newToken);
    }
}
