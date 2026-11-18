# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy wrapper and build scripts first so the Gradle distribution and
# dependencies are cached in their own layers.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && (./gradlew --no-daemon dependencies >/dev/null 2>&1 || true)

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test \
    && java -Djarmode=tools -jar build/libs/hookrelay.jar extract --layers --destination build/extracted

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -g 10001 -S hookrelay && adduser -u 10001 -S -G hookrelay hookrelay
WORKDIR /app

COPY --from=build --chown=hookrelay:hookrelay /workspace/build/extracted/dependencies/ ./
COPY --from=build --chown=hookrelay:hookrelay /workspace/build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=hookrelay:hookrelay /workspace/build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=hookrelay:hookrelay /workspace/build/extracted/application/ ./

USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "hookrelay.jar"]
