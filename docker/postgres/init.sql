-- Create users table
CREATE TABLE IF NOT EXISTS users (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(50)  UNIQUE NOT NULL,
    password    VARCHAR(255) NOT NULL,
    email       VARCHAR(100) UNIQUE NOT NULL,
    role        VARCHAR(20)  NOT NULL DEFAULT 'USER',
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Create route_configs table
CREATE TABLE IF NOT EXISTS route_configs (
    id              BIGSERIAL PRIMARY KEY,
    route_id        VARCHAR(100) UNIQUE NOT NULL,
    uri             VARCHAR(255) NOT NULL,
    path_pattern    VARCHAR(255) NOT NULL,
    rate_limit      INT          NOT NULL DEFAULT 100,
    rate_duration   INT          NOT NULL DEFAULT 60,
    requires_auth   BOOLEAN      NOT NULL DEFAULT TRUE,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Create api_logs table
CREATE TABLE IF NOT EXISTS api_logs (
    id              BIGSERIAL PRIMARY KEY,
    request_id      VARCHAR(100) NOT NULL,
    method          VARCHAR(10)  NOT NULL,
    path            VARCHAR(255) NOT NULL,
    status_code     INT,
    user_id         BIGINT,
    ip_address      VARCHAR(50),
    duration_ms     BIGINT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Seed default admin user
-- Password is: admin123 (BCrypt encoded)
INSERT INTO users (username, password, email, role) VALUES
('admin', '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQyCKzGMukCfKRGxMROiSg7fO', 'admin@gateway.com', 'ADMIN'),
('testuser', '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQyCKzGMukCfKRGxMROiSg7fO', 'test@gateway.com', 'USER')
ON CONFLICT (username) DO NOTHING;

-- Seed default routes
INSERT INTO route_configs (route_id, uri, path_pattern, rate_limit, rate_duration, requires_auth) VALUES
('user-service',    'http://localhost:8091', '/api/users/**',    100, 60, TRUE),
('product-service', 'http://localhost:8092', '/api/products/**', 200, 60, FALSE),
('order-service',   'http://localhost:8093', '/api/orders/**',   50,  60, TRUE)
ON CONFLICT (route_id) DO NOTHING;