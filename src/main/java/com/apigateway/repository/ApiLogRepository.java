package com.apigateway.repository;

import com.apigateway.model.ApiLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ApiLogRepository extends JpaRepository<ApiLog, Long> {

    List<ApiLog> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<ApiLog> findByPathAndCreatedAtAfter(String path, LocalDateTime after);

    long countByCreatedAtAfter(LocalDateTime after);
}