# ---- build ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY . .
# 'sh mvnw' (not ./mvnw): survives lost exec bits and CRLF mishaps from Windows checkouts
RUN sh mvnw -q -DskipTests package

# ---- runtime ----
FROM eclipse-temurin:25-jre
WORKDIR /app
ARG OTEL_AGENT_VERSION=2.30.0
# The OTel Java agent is baked in from day one but DORMANT: nothing loads it until
# docker-compose sets JAVA_TOOL_OPTIONS (branch 03-otlp-receiver). The migration
# diff is therefore env vars only - zero image/code changes.
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
COPY --from=build /build/target/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
