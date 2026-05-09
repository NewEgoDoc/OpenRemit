# syntax=docker/dockerfile:1.7
#
# 단일 Dockerfile로 4개 Spring Boot 모듈(remittance-api, payout-worker,
# webhook-dispatcher, reconciler)을 ARG MODULE 로 빌드.
#
# Layered JAR 패턴: dependencies / spring-boot-loader / snapshot-dependencies
# / application 4개 layer를 분리 COPY 하여 코드 변경 시 application layer만
# 갱신되도록 Docker layer 캐시 효율을 확보.

# === build stage: gradle → bootJar ===
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY common ./common
COPY payment-module ./payment-module
COPY remittance-api ./remittance-api
COPY payout-worker ./payout-worker
COPY webhook-dispatcher ./webhook-dispatcher
COPY reconciler ./reconciler

ARG MODULE
RUN chmod +x gradlew && ./gradlew :${MODULE}:bootJar --no-daemon -x test

# === extract stage: layered JAR을 layer별 디렉터리로 분해 ===
# tools jarmode의 extract --destination은 디렉터리가 비어있길 요구하므로
# JAR은 /tmp에 두고 빈 /extract를 destination으로 사용.
FROM eclipse-temurin:21-jdk AS extract
ARG MODULE
COPY --from=build /workspace/${MODULE}/build/libs/*.jar /tmp/app.jar
WORKDIR /extract
RUN java -Djarmode=tools -jar /tmp/app.jar extract \
      --layers dependencies,spring-boot-loader,snapshot-dependencies,application \
      --launcher --destination .

# === runtime stage: layer 순서대로 별도 COPY → 별도 Docker layer 형성 ===
FROM eclipse-temurin:21-jre
# curl: HEALTHCHECK가 actuator/health를 polling 하기 위해 필요
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=extract /extract/dependencies/          ./
COPY --from=extract /extract/spring-boot-loader/    ./
COPY --from=extract /extract/snapshot-dependencies/ ./
COPY --from=extract /extract/application/           ./
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=12 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java","org.springframework.boot.loader.launch.JarLauncher"]
