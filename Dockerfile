# syntax=docker/dockerfile:1

# ---------- сборка: Maven + JDK 25 ----------
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build

# Зависимости отдельным слоем: при правке кода они не скачиваются заново
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
# Тесты гоняются локально (mvn test); здесь только сборка jar
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
    && cp target/pii-anonymizer-*.jar /build/app.jar

# ---------- запуск: только JRE 25 ----------
FROM eclipse-temurin:25-jre-noble

# Шрифт с кириллицей для обезличенных PDF (Arial в Linux нет, сервис берёт DejaVu Sans)
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER app

# В application.yml сервис слушает только 127.0.0.1. В контейнере это сделало бы его недоступным,
# поэтому внутри слушаем все интерфейсы, а наружу порт публикуется только на 127.0.0.1 хоста
# (см. docker-compose.yml и README) — из сети сервис по-прежнему недоступен.
ENV SERVER_ADDRESS=0.0.0.0 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
