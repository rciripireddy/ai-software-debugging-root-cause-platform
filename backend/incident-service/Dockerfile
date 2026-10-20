# syntax=docker/dockerfile:1

FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline
COPY src ./src
# Tests run separately (./mvnw test); the image build only packages the application.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
USER app
EXPOSE 8082
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
