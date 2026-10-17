# syntax=docker/dockerfile:1
# Build stage: compile the orchestrator.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY orchestrator/pom.xml orchestrator/
COPY shortener/pom.xml shortener/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl orchestrator -am dependency:go-offline || true
COPY orchestrator/src orchestrator/src
COPY shortener/src shortener/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl orchestrator -am -DskipTests package

# Runtime stage: agents' code is built and tested inside the container, so it needs a JDK,
# Maven and git, not just a JRE.
FROM maven:3.9-eclipse-temurin-21
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home agentic
WORKDIR /app
COPY --from=build /src/orchestrator/target/orchestrator-*.jar /app/app.jar
COPY policies /app/policies
COPY scenarios /app/scenarios
RUN mkdir -p /app/runs /app/.m2 && chown -R agentic /app
USER agentic
ENV AGENTIC_MVN_REPO=/app/.m2/repository
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["fake-demo"]
