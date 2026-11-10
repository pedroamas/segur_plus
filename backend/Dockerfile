# syntax=docker/dockerfile:1
# Backend de SegurPlus: Maven + Temurin 21 en la etapa de compilación, JRE 21 sin privilegios en la de ejecución.
# Contexto: backend/ (docker compose lo usa en deploy/docker-compose.yml). Los tests NO se ejecutan aquí (CI: ./mvnw verify).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q -DskipTests package \
 && cp "$(ls target/*.jar | grep -v '\.original$' | head -n 1)" /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin segurplus
COPY --from=build --chown=segurplus /app.jar /app/app.jar
USER segurplus
WORKDIR /app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"
EXPOSE 8100
# No hay Actuator: /v3/api-docs es público (SecurityConfig) y solo responde 200 con la aplicación, Flyway y la BD arriba.
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=12 \
  CMD curl -fsS -o /dev/null http://127.0.0.1:8100/v3/api-docs || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
