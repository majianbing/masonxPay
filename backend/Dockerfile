# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

# Cache dependencies layer — all module poms are needed for the reactor to parse,
# but only the gateway-service slice is built (-pl gateway-service -am).
COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY contracts/pom.xml contracts/pom.xml
COPY gateway-service/pom.xml gateway-service/pom.xml
COPY virtual-account-service/pom.xml virtual-account-service/pom.xml
COPY rail-service/pom.xml rail-service/pom.xml
COPY rail-simulator/pom.xml rail-simulator/pom.xml
RUN mvn -B -pl gateway-service -am dependency:go-offline

# Build only gateway-service and its reactor dependencies (common, contracts).
COPY common/src common/src
COPY contracts/src contracts/src
COPY gateway-service/src gateway-service/src
RUN mvn -B -pl gateway-service -am -DskipTests package

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app

# Bootable artifact is the gateway-service module jar
COPY --from=builder /app/gateway-service/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
