package com.aluco.server.common;

/**
 * Authentication service interface (cross-cutting concern).
 * Implementations belong to the auth package (business logic layer).
 */
public interface AuthService {
    /** @return signed JWT; throws 401 BizException on bad credentials */
    String login(String username, String password);
}
