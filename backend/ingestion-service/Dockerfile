# syntax=docker/dockerfile:1

# Build stage: compiles and packages the application, then splits the jar into layers.
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline
COPY src ./src
# Tests run separately (./mvnw test); the image build only packages the application.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp package -DskipTests
# Dependencies, loader, snapshot dependencies and application code become separate image layers,
# so a code change only rebuilds the small application layer. Every layer directory is created,
# even when empty, so the COPY instructions below always find it.
RUN java -Djarmode=tools -jar target/ingestion-service-*.jar extract --layers --destination extracted \
        --application-filename app.jar \
    && mkdir -p extracted/dependencies extracted/spring-boot-loader \
        extracted/snapshot-dependencies extracted/application

# Runtime stage: JRE only, application files owned by root (read-only for the service user).
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app -s /sbin/nologin app
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001:10001
EXPOSE 8081
# Heap: at most 60% of the container memory limit, leaving room for metaspace, threads and
# buffers. Exiting on OutOfMemoryError lets Docker restart a broken process. Exec form: the JVM
# is PID 1 and receives SIGTERM, which starts Spring Boot's graceful shutdown.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
