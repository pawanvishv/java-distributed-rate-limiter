package com.apigateway.service;

import com.apigateway.dto.LoginRequest;
import com.apigateway.dto.RegisterRequest;
import com.apigateway.dto.TokenResponse;
import com.apigateway.model.User;
import com.apigateway.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String BLACKLIST_PREFIX = "blacklist:";
    private static final String REFRESH_PREFIX   = "refresh:";

    // ── Login ────────────────────────────────────────────────────────

    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new RuntimeException("Invalid credentials"));

        if (!user.getActive()) {
            throw new RuntimeException("Account is disabled");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid credentials");
        }

        String accessToken  = jwtService.generateToken(user.getUsername(), user.getRole().name());
        String refreshToken = jwtService.generateRefreshToken(user.getUsername());

        // Store refresh token in Redis (7 days)
        redisTemplate.opsForValue().set(
                REFRESH_PREFIX + user.getUsername(),
                refreshToken,
                7, TimeUnit.DAYS
        );

        log.info("User logged in: {}", user.getUsername());

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(86400L)
                .username(user.getUsername())
                .role(user.getRole().name())
                .build();
    }

    // ── Register ─────────────────────────────────────────────────────

    public TokenResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("Username already taken");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already registered");
        }

        User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .email(request.getEmail())
                .role(User.Role.USER)
                .active(true)
                .build();

        userRepository.save(user);
        log.info("New user registered: {}", user.getUsername());

        return login(new LoginRequest(request.getUsername(), request.getPassword()));
    }

    // ── Logout ───────────────────────────────────────────────────────

    public void logout(String token) {
        String username = jwtService.extractUsername(token);

        // Blacklist the access token
        redisTemplate.opsForValue().set(
                BLACKLIST_PREFIX + token,
                username,
                24, TimeUnit.HOURS
        );

        // Delete refresh token
        redisTemplate.delete(REFRESH_PREFIX + username);

        log.info("User logged out: {}", username);
    }

    // ── Validate Token ───────────────────────────────────────────────

    public boolean isTokenBlacklisted(String token) {
        return Boolean.TRUE.equals(
                redisTemplate.hasKey(BLACKLIST_PREFIX + token)
        );
    }

    public boolean validateToken(String token) {
        if (isTokenBlacklisted(token)) {
            log.warn("Blacklisted token used");
            return false;
        }
        return jwtService.isTokenValid(token);
    }

    // ── Refresh Token ────────────────────────────────────────────────

    public TokenResponse refreshToken(String refreshToken) {
        if (!jwtService.isTokenValid(refreshToken)) {
            throw new RuntimeException("Invalid refresh token");
        }

        String username = jwtService.extractUsername(refreshToken);
        String stored   = redisTemplate.opsForValue().get(REFRESH_PREFIX + username);

        if (stored == null || !stored.equals(refreshToken)) {
            throw new RuntimeException("Refresh token mismatch or expired");
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String newAccessToken  = jwtService.generateToken(username, user.getRole().name());
        String newRefreshToken = jwtService.generateRefreshToken(username);

        redisTemplate.opsForValue().set(
                REFRESH_PREFIX + username,
                newRefreshToken,
                7, TimeUnit.DAYS
        );

        return TokenResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .tokenType("Bearer")
                .expiresIn(86400L)
                .username(username)
                .role(user.getRole().name())
                .build();
    }
}