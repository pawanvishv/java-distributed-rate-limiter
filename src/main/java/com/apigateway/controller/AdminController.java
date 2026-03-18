package com.apigateway.controller;

import com.apigateway.dto.ApiResponse;
import com.apigateway.model.RouteConfig;
import com.apigateway.repository.RouteConfigRepository;
import com.apigateway.service.RateLimiterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin")
@Slf4j
@RequiredArgsConstructor
public class AdminController {

    private final RouteConfigRepository routeConfigRepository;
    private final RateLimiterService rateLimiterService;

    // ── GET /admin/routes ────────────────────────────────────────────
    @GetMapping("/routes")
    public ResponseEntity<ApiResponse<List<RouteConfig>>> getAllRoutes(
            @RequestHeader(value = "X-User-Role", defaultValue = "") String role) {

        if (!role.equals("ADMIN")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("Admin access required"));
        }

        List<RouteConfig> routes = routeConfigRepository.findAll();
        return ResponseEntity.ok(ApiResponse.success(routes, "Routes fetched"));
    }

    // ── POST /admin/routes ───────────────────────────────────────────
    @PostMapping("/routes")
    public ResponseEntity<ApiResponse<RouteConfig>> createRoute(
            @RequestHeader(value = "X-User-Role", defaultValue = "") String role,
            @RequestBody RouteConfig routeConfig) {

        if (!role.equals("ADMIN")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("Admin access required"));
        }

        if (routeConfigRepository.existsByRouteId(routeConfig.getRouteId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("Route ID already exists"));
        }

        RouteConfig saved = routeConfigRepository.save(routeConfig);
        log.info("New route created: {}", saved.getRouteId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(saved, "Route created"));
    }

    // ── DELETE /admin/routes/{routeId} ───────────────────────────────
    @DeleteMapping("/routes/{routeId}")
    public ResponseEntity<ApiResponse<Void>> deleteRoute(
            @RequestHeader(value = "X-User-Role", defaultValue = "") String role,
            @PathVariable String routeId) {

        if (!role.equals("ADMIN")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("Admin access required"));
        }

        return routeConfigRepository.findByRouteId(routeId)
                .map(route -> {
                    routeConfigRepository.delete(route);
                    log.info("Route deleted: {}", routeId);
                    return ResponseEntity.ok(
                            ApiResponse.<Void>success(null, "Route deleted"));
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("Route not found")));
    }

    // ── DELETE /admin/rate-limit/{identifier} ────────────────────────
    @DeleteMapping("/rate-limit/{identifier}")
    public ResponseEntity<ApiResponse<Boolean>> resetRateLimit(
            @RequestHeader(value = "X-User-Role", defaultValue = "") String role,
            @PathVariable String identifier) {

        if (!role.equals("ADMIN")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("Admin access required"));
        }

        return rateLimiterService.resetLimit(identifier)
                .map(deleted -> ResponseEntity.ok(
                        ApiResponse.success(deleted, "Rate limit reset")))
                .block() != null
                ? rateLimiterService.resetLimit(identifier)
                        .map(deleted -> ResponseEntity.ok(
                                ApiResponse.success(deleted, "Rate limit reset for: " + identifier)))
                        .block()
                : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.error("Failed to reset"));
    }

    // ── GET /admin/stats ─────────────────────────────────────────────
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Object>> getStats(
            @RequestHeader(value = "X-User-Role", defaultValue = "") String role) {

        if (!role.equals("ADMIN")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("Admin access required"));
        }

        var stats = new java.util.HashMap<String, Object>();
        stats.put("totalRoutes", routeConfigRepository.count());
        stats.put("activeRoutes", routeConfigRepository.findAllByActiveTrue().size());

        return ResponseEntity.ok(ApiResponse.success(stats, "Stats fetched"));
    }
}