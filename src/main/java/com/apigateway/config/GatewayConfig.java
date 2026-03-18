package com.apigateway.config;

import com.apigateway.repository.RouteConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class GatewayConfig {

    private final RouteConfigRepository routeConfigRepository;

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        RouteLocatorBuilder.Builder routes = builder.routes();

        // Load routes from PostgreSQL
        routeConfigRepository.findAllByActiveTrue().forEach(route -> {
            log.info("Registering route: {} → {} [{}]",
                    route.getRouteId(), route.getUri(), route.getPathPattern());

            routes.route(route.getRouteId(), r -> r
                    .path(route.getPathPattern())
                    .filters(f -> f
                            .stripPrefix(0)
                            .addRequestHeader("X-Gateway-Source", "api-gateway")
                            .addResponseHeader("X-Powered-By", "API-Gateway")
                    )
                    .uri(route.getUri())
            );
        });

        // Auth routes — handled internally (no proxy)
        routes.route("auth-route", r -> r
                .path("/auth/**")
                .filters(f -> f
                        .addRequestHeader("X-Gateway-Source", "api-gateway")
                )
                .uri("no://op")
        );

        return routes.build();
    }
}