# syntax=docker/dockerfile:1

# Stage 1: build with Maven and the Vaadin production profile (Node.js is fetched here only)
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /workspace
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -Pproduction dependency:go-offline || true
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -Pproduction -DskipTests package \
 && cp target/agenticpilot-*.jar app.jar

# Stage 2: slim Java 21 runtime, non-root, port 8080
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app \
 && mkdir -p /app /data && chown -R app:app /app /data
WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar app.jar
ENV SPRING_PROFILES_ACTIVE=h2 \
    AGENTICPILOT_DB_URL="jdbc:h2:file:/data/agenticpilot;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH" \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
USER 10001:10001
EXPOSE 8080
VOLUME /data
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
