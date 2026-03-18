package com.apigateway.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "route_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_id", unique = true, nullable = false, length = 100)
    private String routeId;

    @Column(nullable = false)
    private String uri;

    @Column(name = "path_pattern", nullable = false)
    private String pathPattern;

    @Column(name = "rate_limit", nullable = false)
    @Builder.Default
    private Integer rateLimit = 100;

    @Column(name = "rate_duration", nullable = false)
    @Builder.Default
    private Integer rateDuration = 60;

    @Column(name = "requires_auth", nullable = false)
    @Builder.Default
    private Boolean requiresAuth = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}