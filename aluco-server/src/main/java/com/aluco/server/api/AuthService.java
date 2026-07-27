package com.aluco.server.api;

public interface AuthService {
    /** @return signed JWT; throws 401 BizException on bad credentials */
    String login(String username, String password);
}
