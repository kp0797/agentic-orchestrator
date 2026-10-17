# syntax=docker/dockerfile:1
# Multi-stage: Maven build, then a slim JRE image. Day 2 adds the verifier tools
# (Semgrep, gitleaks, Spectral, openapi-diff) to the runtime stage.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY orchestrator/pom.xml orchestrator/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl orchestrator -am dependency:go-offline || true
COPY orchestrator/src orchestrator/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 agentic && mkdir -p /app/runs && chown agentic /app/runs
COPY --from=build /src/orchestrator/target/orchestrator-*.jar /app/app.jar
USER agentic
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["fake-demo"]
