package com.apigateway.repository;

import com.apigateway.model.RouteConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RouteConfigRepository extends JpaRepository<RouteConfig, Long> {

    Optional<RouteConfig> findByRouteId(String routeId);

    List<RouteConfig> findAllByActiveTrue();

    boolean existsByRouteId(String routeId);
}