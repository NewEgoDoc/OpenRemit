# OpenRemit

[![CI](https://github.com/NewEgoDoc/OpenRemit/actions/workflows/ci.yml/badge.svg)](https://github.com/NewEgoDoc/OpenRemit/actions/workflows/ci.yml)

송금 + 내부 결제 게이트웨이 백엔드.

## 실행

```bash
docker compose up -d
./gradlew :remittance-api:bootRun
```

헬스체크: `http://localhost:8080/actuator/health`

## 모듈

- `remittance-api` — REST API
- `payment-module` — 내부 결제 게이트웨이
- `payout-worker` — Kafka 컨슈머 (송금 처리)
- `webhook-dispatcher` — Webhook 발송 / 재시도
- `reconciler` — 정산 배치
- `common` — 공통 도메인

## 스택

Kotlin 2.2 / Spring Boot 4.0 / Java 21 · MySQL 8 + Flyway · Redis · Kafka · Resilience4j · Testcontainers + Kotest

## 테스트

```bash
./gradlew build
```

통합 테스트는 Testcontainers로 MySQL 8 + Redis 7을 자동 기동합니다 (Docker 필요).
GitHub Actions CI에서도 동일한 Testcontainers 기반으로 매 커밋 검증.
