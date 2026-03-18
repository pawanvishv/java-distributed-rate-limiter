┌─────────────────────────────────────────────────────────┐
│                    CLIENT (Postman/Browser)              │
└─────────────────────────┬───────────────────────────────┘
                          │ HTTP Request
                          ▼
┌─────────────────────────────────────────────────────────┐
│              API GATEWAY (Port 8080)                     │
│                                                         │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │  Logging    │→ │  RateLimit   │→ │     Auth      │  │
│  │  Filter     │  │  Filter      │  │     Filter    │  │
│  └─────────────┘  └──────────────┘  └───────────────┘  │
│         │                │                  │           │
│         ▼                ▼                  ▼           │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │ PostgreSQL  │  │    Redis     │  │  JWT Validate │  │
│  │  (Logs)     │  │ (RateLimit)  │  │  + Blacklist  │  │
│  └─────────────┘  └──────────────┘  └───────────────┘  │
└─────────────────────────┬───────────────────────────────┘
                          │ Route to Microservice
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
   ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
   │   User      │ │   Product   │ │   Order     │
   │  Service    │ │   Service   │ │   Service   │
   │  :8091      │ │   :8092     │ │   :8093     │
   └─────────────┘ └─────────────┘ └─────────────┘


api-gateway/                          ← Root
├── Dockerfile                        ← Gateway image
├── docker-compose.yml                ← Local dev (all services)
├── docker-compose.prod.yml           ← Production overrides
├── docker-compose.scale.yml          ← Scaling config
├── .dockerignore                     ← Ignore build files
│
├── docker/
│   ├── postgres/
│   │   └── init.sql                  ← DB seed data
│   └── redis/
│       └── redis.conf                ← Redis config
│
├── microservices/                    ← Future microservices
│   ├── user-service/
│   │   └── Dockerfile
│   ├── product-service/
│   │   └── Dockerfile
│   └── order-service/
│       └── Dockerfile
│
└── src/                              ← Gateway source code


# ── STAGE 1: BUILD ───────────────────────────────────────
# Uses full JDK to compile the code
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy pom first — Docker caches this layer
# Only re-downloads dependencies if pom.xml changes
COPY pom.xml .
COPY .mvn/ .mvn/
COPY mvnw .
RUN ./mvnw dependency:go-offline -B

# Copy source and build jar
COPY src/ src/
RUN ./mvnw clean package -DskipTests -B

# ── STAGE 2: RUNTIME ─────────────────────────────────────
# Uses only JRE — much smaller image (no compiler)
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Security: run as non-root user
RUN addgroup -S gateway && adduser -S gateway -G gateway

# Copy ONLY the jar from builder stage
COPY --from=builder /app/target/*.jar app.jar

RUN chown -R gateway:gateway /app
USER gateway

EXPOSE 8080

# Health check — Docker knows if container is healthy
HEALTHCHECK --interval=30s --timeout=10s \
    --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

# JVM tuning for containers
ENTRYPOINT ["java",
    "-XX:+UseContainerSupport",        
    "-XX:MaxRAMPercentage=75.0",       
    "-Djava.security.egd=file:/dev/./urandom",
    "-jar", "app.jar"]

// local docker ymal file

version: '3.9'

services:
  postgres:
    image: postgres:16-alpine
    container_name: api-gateway-postgres
    environment:
      POSTGRES_DB: api_gateway_db
      POSTGRES_USER: gateway_user
      POSTGRES_PASSWORD: gateway_pass
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./docker/postgres/init.sql:/docker-entrypoint-initdb.d/init.sql
    networks:
      - gateway-network
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U gateway_user"]
      interval: 10s
      retries: 5

  redis:
    image: redis:7.2-alpine
    container_name: api-gateway-redis
    command: redis-server /usr/local/etc/redis/redis.conf
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
      - ./docker/redis/redis.conf:/usr/local/etc/redis/redis.conf
    networks:
      - gateway-network
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      retries: 5

  api-gateway:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: api-gateway-app
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/api_gateway_db
      SPRING_DATASOURCE_USERNAME: gateway_user
      SPRING_DATASOURCE_PASSWORD: gateway_pass
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: 6379
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    networks:
      - gateway-network

volumes:
  postgres_data:
  redis_data:

networks:
  gateway-network:
    driver: bridge

// production docker file 

version: '3.9'

services:
  api-gateway:
    image: api-gateway:${VERSION:-latest}  # use pre-built image
    restart: always
    environment:
      SPRING_PROFILES_ACTIVE: prod
      JAVA_OPTS: "-Xms512m -Xmx1024m"
    deploy:
      resources:
        limits:
          cpus: '1.0'
          memory: 1G
        reservations:
          memory: 512M
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"

  postgres:
    restart: always
    environment:
      POSTGRES_PASSWORD: ${DB_PASSWORD}    # from .env file
    volumes:
      - /data/postgres:/var/lib/postgresql/data  # host mount

  redis:
    restart: always
    command: redis-server --requirepass ${REDIS_PASSWORD}




// docker-compose.scale.yml (Scaling)
version: '3.9'

services:
  api-gateway:
    ports: []                    # remove fixed port for scaling
    environment:
      SPRING_PROFILES_ACTIVE: docker

  nginx:
    image: nginx:alpine
    container_name: nginx-lb
    ports:
      - "80:80"
    volumes:
      - ./docker/nginx/nginx.conf:/etc/nginx/nginx.conf
    depends_on:
      - api-gateway
    networks:
      - gateway-network

/// docker/nginx/nginx.conf:

events {
    worker_connections 1024;
}

http {
    upstream api_gateway {
        least_conn;                          # least connections algorithm
        server api-gateway:8080;             # Docker resolves multiple instances
    }

    server {
        listen 80;

        location / {
            proxy_pass http://api_gateway;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        }
    }
}


— Daily Development Commands

# ── START ────────────────────────────────────────────────

# Start only infrastructure (PostgreSQL + Redis)
docker compose up -d postgres redis

# Start everything including gateway
docker compose up -d

# Build and start (after code changes)
docker compose up --build -d

# Force rebuild from scratch
docker compose up --build --force-recreate -d

# ── STOP ─────────────────────────────────────────────────

# Stop all containers (keep data)
docker compose down

# Stop and remove all data (full reset)
docker compose down -v

# Stop single service
docker compose stop api-gateway

# ── RESTART ──────────────────────────────────────────────

# Restart single service
docker compose restart api-gateway

# Rebuild and restart single service
docker compose up --build -d api-gateway



/// — Logs Commands

# View all logs
docker compose logs

# Follow live logs
docker compose logs -f

# Gateway logs only
docker compose logs -f api-gateway

# Last 50 lines
docker compose logs --tail=50 api-gateway

# Since specific time
docker compose logs --since="2024-01-01T00:00:00" api-gateway



— Debugging Commands

# Enter gateway container shell
docker exec -it api-gateway-app sh

# Enter PostgreSQL
docker exec -it api-gateway-postgres psql -U gateway_user -d api_gateway_db

# Enter Redis CLI
docker exec -it api-gateway-redis redis-cli

# Check container stats (CPU/Memory)
docker stats

# Inspect container details
docker inspect api-gateway-app

# Check container health
docker inspect --format='{{.State.Health.Status}}' api-gateway-app



— Image Management

# Build image manually
docker build -t api-gateway:latest .

# Build with version tag
docker build -t api-gateway:1.0.0 .

# List all images
docker images | grep api-gateway

# Remove old image
docker rmi api-gateway:old

# Remove all unused images
docker image prune -a


— Scaling Commands

# Scale gateway to 3 instances
docker compose -f docker-compose.yml \
               -f docker-compose.scale.yml \
               up -d --scale api-gateway=3

# Check scaled instances
docker compose ps

# Scale back to 1
docker compose up -d --scale api-gateway=1



— Environment Files

# .env (never commit to git!)
DB_PASSWORD=gateway_pass
REDIS_PASSWORD=redis_secret
JWT_SECRET=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970
APP_VERSION=1.0.0
```

Add to `.gitignore`:
```
.env
*.log
target/




— Multiple Environments

# Local development
docker compose up -d

# Production
docker compose \
  -f docker-compose.yml \
  -f docker-compose.prod.yml \
  up -d

# Scaled production
docker compose \
  -f docker-compose.yml \
  -f docker-compose.prod.yml \
  -f docker-compose.scale.yml \
  up -d --scale api-gateway=3



 — Health Monitoring

 # Watch all container health
watch -n 2 'docker compose ps'

# Watch Redis rate limit keys live
watch -n 1 'docker exec api-gateway-redis redis-cli keys "rate_limit:*"'

# Watch PostgreSQL logs live
watch -n 3 'docker exec api-gateway-postgres psql \
  -U gateway_user -d api_gateway_db \
  -c "SELECT method,path,status_code,duration_ms \
      FROM api_logs ORDER BY created_at DESC LIMIT 5;"'


 — Data Backup & Restore

 # Backup PostgreSQL
docker exec api-gateway-postgres pg_dump \
  -U gateway_user api_gateway_db > backup.sql

# Restore PostgreSQL
cat backup.sql | docker exec -i api-gateway-postgres \
  psql -U gateway_user -d api_gateway_db

# Backup Redis
docker exec api-gateway-redis redis-cli BGSAVE
docker cp api-gateway-redis:/data/dump.rdb ./redis-backup.rdb
```

---

## 6. Complete Workflow Summary
```
DEVELOPMENT WORKFLOW:
─────────────────────
1. Code change
      ↓
2. mvn spring-boot:run (local test)
      ↓
3. mvn clean package -DskipTests
      ↓
4. docker build -t api-gateway:latest .
      ↓
5. docker compose up --build -d
      ↓
6. Test with Postman
      ↓
7. docker compose logs -f api-gateway

PRODUCTION WORKFLOW:
─────────────────────
1. git push → CI/CD pipeline
      ↓
2. docker build -t api-gateway:$VERSION .
      ↓
3. docker push registry/api-gateway:$VERSION
      ↓
4. docker compose -f docker-compose.prod.yml up -d
      ↓
5. docker compose up --scale api-gateway=3 -d
      ↓
6. Nginx load balances across 3 instances
      ↓
7. Redis shared across all instances ✅
8. PostgreSQL shared across all instances ✅