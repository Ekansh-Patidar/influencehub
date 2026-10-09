# ── Stage 1: build the Spring Boot jar ───────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Resolve dependencies first so this layer is cached between builds when only code changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests (incl. the NFR suite) run before deploying — locally or in CI — not inside the image
# build, where the shared Cloud Build machine would make the latency tests meaningless.
RUN mvn -B -q package -DskipTests

# ── Stage 2: small runtime image (JRE only, no Maven, no sources) ────────────────────
FROM eclipse-temurin:17-jre
WORKDIR /app

# Run as an unprivileged user
RUN useradd --system --uid 10001 spring
USER spring

COPY --from=build /app/target/*.jar app.jar

# Cloud Run injects PORT (8080); application.properties reads it via server.port=${PORT:8082}.
ENV PORT=8080
EXPOSE 8080

# Size the heap from the container memory limit instead of the host's RAM.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+UseSerialGC", "-Xss512k", "-jar", "/app/app.jar"]
