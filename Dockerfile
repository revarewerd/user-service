# ============================================================
# User Service — Multi-stage Docker build
# ============================================================

FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Копируем sbt файлы для кэширования зависимостей
COPY project/build.properties project/
COPY project/plugins.sbt project/
COPY build.sbt .

# Скачиваем зависимости (кэшируется)
RUN sbt update

# Копируем исходники
COPY src/ src/

# Собираем fat JAR
RUN sbt assembly

# ============================================================
# Runtime
# ============================================================

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S userservice && \
    adduser -S userservice -G userservice

COPY --from=builder /app/target/scala-3.4.0/user-service-assembly-*.jar app.jar

USER userservice

# Порт HTTP API
EXPOSE 8091

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8091/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
