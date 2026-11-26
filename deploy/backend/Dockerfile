FROM gradle:8.14-jdk21 AS build
WORKDIR /src
COPY . .
ARG SERVICE_NAME
RUN --mount=type=cache,target=/home/gradle/.gradle,sharing=locked gradle :${SERVICE_NAME}:bootJar --no-daemon --max-workers=2
RUN cp "/src/${SERVICE_NAME}/build/libs/${SERVICE_NAME}-0.1.0-SNAPSHOT.jar" /tmp/app.jar && \
    java -Djarmode=tools -jar /tmp/app.jar extract --destination /extracted

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
ARG SERVICE_NAME
WORKDIR /app
COPY --from=build /extracted/ ./
ENV SERVER_PORT=8080 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55 -XX:InitialRAMPercentage=15 -XX:ReservedCodeCacheSize=64m -Xss256k -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=18 \
  CMD curl --fail --silent --max-time 4 "http://127.0.0.1:${SERVER_PORT}/actuator/health" >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
