# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

# Cache dependencies layer — copy the reactor parent + module poms first
COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY contracts/pom.xml contracts/pom.xml
COPY gateway-service/pom.xml gateway-service/pom.xml
RUN mvn -B dependency:go-offline

# Build the reactor
COPY common/src common/src
COPY contracts/src contracts/src
COPY gateway-service/src gateway-service/src
RUN mvn -B -DskipTests package

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Bootable artifact is the gateway-service module jar
COPY --from=builder /app/gateway-service/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
