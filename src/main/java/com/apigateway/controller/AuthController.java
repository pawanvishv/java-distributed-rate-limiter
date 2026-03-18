package com.apigateway.controller;

import com.apigateway.dto.ApiResponse;
import com.apigateway.dto.LoginRequest;
import com.apigateway.dto.RegisterRequest;
import com.apigateway.dto.TokenResponse;
import com.apigateway.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@Slf4j
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // ── POST /auth/login ─────────────────────────────────────────────
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(
            @Valid @RequestBody LoginRequest request) {
        try {
            TokenResponse token = authService.login(request);
            return ResponseEntity.ok(
                    ApiResponse.success(token, "Login successful")
            );
        } catch (Exception e) {
            log.warn("Login failed for user: {}", request.getUsername());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    // ── POST /auth/register ──────────────────────────────────────────
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<TokenResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        try {
            TokenResponse token = authService.register(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(token, "Registration successful"));
        } catch (Exception e) {
            log.warn("Registration failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    // ── POST /auth/logout ────────────────────────────────────────────
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestHeader("Authorization") String authHeader) {
        try {
            String token = authHeader.substring(7);
            authService.logout(token);
            return ResponseEntity.ok(
                    ApiResponse.success(null, "Logged out successfully")
            );
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    // ── POST /auth/refresh ───────────────────────────────────────────
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @RequestHeader("X-Refresh-Token") String refreshToken) {
        try {
            TokenResponse token = authService.refreshToken(refreshToken);
            return ResponseEntity.ok(
                    ApiResponse.success(token, "Token refreshed")
            );
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    // ── GET /auth/validate ───────────────────────────────────────────
    @GetMapping("/validate")
    public ResponseEntity<ApiResponse<Boolean>> validate(
            @RequestHeader("Authorization") String authHeader) {
        try {
            String token = authHeader.substring(7);
            boolean valid = authService.validateToken(token);
            return ResponseEntity.ok(
                    ApiResponse.success(valid, valid ? "Token is valid" : "Token is invalid")
            );
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("Invalid token"));
        }
    }
}