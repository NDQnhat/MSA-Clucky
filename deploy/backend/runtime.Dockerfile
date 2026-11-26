FROM eclipse-temurin:21-jre AS extract
WORKDIR /extract
COPY app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination /extracted

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=extract /extracted/ ./
ENV SERVER_PORT=8080 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:InitialRAMPercentage=15 -XX:ReservedCodeCacheSize=64m -Xss256k -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=18 \
  CMD curl --fail --silent --max-time 4 "http://127.0.0.1:${SERVER_PORT}/actuator/health" >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
