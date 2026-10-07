package com.aluco.server.auth;

import com.aluco.server.common.AuthService;
import com.aluco.server.common.BizException;
import com.aluco.server.common.JwtService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {

    private final AppUserRepository appUserRepository;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthServiceImpl(AppUserRepository appUserRepository, JwtService jwtService) {
        this.appUserRepository = appUserRepository;
        this.jwtService = jwtService;
    }

    @Override
    public String login(String username, String password) {
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> BizException.unauthorized("BAD_CREDENTIALS",
                        "invalid username or password"));
        if (!encoder.matches(password, user.getPasswordHash())) {
            throw BizException.unauthorized("BAD_CREDENTIALS", "invalid username or password");
        }
        return jwtService.issue(user.getUsername());
    }
}
