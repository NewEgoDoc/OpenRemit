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

Kotlin 2.2 / Spring Boot 4.0 / Java 21 · MySQL 8 + Flyway · Redis · Kafka · Debezium CDC · Resilience4j · Testcontainers + Kotest

## 이벤트 흐름 (송금 1건)

```
[Client]
   │ POST /remittances
   ▼
[remittance-api]                                  ── (단일 트랜잭션) ──┐
   ├─ Wallet 차감 + Remittance(REQUESTED→PAID)                          │
   └─ remittance_events(outbox) INSERT  ◀────────────────────────────────┘
            │
            │  (MySQL binlog)
            ▼
   ┌──────────────────────┐
   │ Debezium (standalone)│  ── outbox event router SMT ──▶  Kafka topic: remittance.paid
   └──────────────────────┘
                                                                │
                                                                ▼
                                                       [payout-worker]
                                                          ├─ payout_attempts INSERT (멱등성 키 = remittanceId)
                                                          ├─ 송금사 API 호출 (WireMock)
                                                          └─ payout_outbox INSERT (단일 트랜잭션)
                                                                │
                                                                │  (MySQL binlog)
                                                                ▼
                                                       ┌──────────────────────┐
                                                       │       Debezium       │
                                                       └──────────────────────┘
                                                                │
                                                  ┌─────────────┴─────────────┐
                                                  ▼                           ▼
                                  remittance.payout.completed     remittance.payout.failed
                                                  │                           │
                                                  └────────────┬──────────────┘
                                                               ▼
                                                       [remittance-api consumer]
                                                          └─ Remittance: PROCESSING → COMPLETED / FAILED
```

### 핵심 설계 포인트

- **Outbox 패턴 + Debezium CDC.** 모든 이벤트 발행은 동일 트랜잭션에서 outbox INSERT만 하고, 별도 polling publisher 없이 MySQL binlog → Debezium → Kafka로 흘립니다. "DB는 커밋되었는데 메시지는 발행 실패" 시나리오가 구조적으로 불가능합니다.
- **분산 구조 (DB 소유권 분리).** payout-worker는 `remittances` 테이블을 절대 보지 않습니다. 자체 테이블(`payout_attempts`, `payout_outbox`)만 매핑·소유하고, 결과는 또 다른 outbox로 흘려보내 remittance-api 컨슈머가 상태 머신을 갱신합니다. 모놀리스이지만 모듈 간 결합도는 마이크로서비스에 가깝게 분리했습니다.
- **물리 DB는 단일.** 분산 시연 가치 대비 docker-compose 비대화 + 일정 리스크를 고려해 MySQL 인스턴스 자체는 공유합니다. 논리 소유권만 모듈별로 분리합니다.

### 왜 worker가 KafkaTemplate으로 직접 publish 하지 않는가

worker가 송금사 호출 결과를 `KafkaTemplate.send(...)`로 바로 발행하면 코드는 단순해지지만, **at-least-once 보장이 깨질 위험**이 있습니다:

- 송금사 API는 호출 성공했는데, 직후 Kafka publish 직전에 worker가 죽으면 결과 이벤트가 소실됩니다.
- send() 자체가 비동기/실패할 수 있고, 송금사 호출과 publish가 동일 트랜잭션이 될 수 없습니다 (외부 시스템 + Kafka).
- 결과 소실은 remittance가 영원히 `PROCESSING`에 머무는 incident로 이어집니다.

따라서 worker도 결과를 자체 `payout_outbox`에 트랜잭션 INSERT 하고 Debezium이 Kafka로 흘리는 동일한 패턴을 적용합니다. 송금사 호출 후 worker가 죽어도 outbox 행은 DB에 살아있고, 재기동 시 또는 다음 binlog tail에서 자연스럽게 발행됩니다.

## 테스트

```bash
./gradlew build
```

통합 테스트는 Testcontainers로 MySQL 8 + Redis 7 + Kafka를 자동 기동합니다 (Docker 필요).
GitHub Actions CI에서도 동일한 Testcontainers 기반으로 매 커밋 검증.

> 통합 테스트는 KafkaTemplate으로 토픽에 직접 발행해 컨슈머/Outbox INSERT 동작만 검증합니다. binlog → Debezium → Kafka 단계는 docker-compose 실환경 검증으로 분리합니다 (ADR-012).

## docker-compose 풀스택 검증 (Debezium 포함 E2E)

```bash
docker compose up -d
```

기동 컨테이너:
- `openremit-mysql` (3306, binlog ROW + GTID 활성)
- `openremit-redis` (6379)
- `openremit-kafka` (9092 host / 29092 internal, KRaft single-node)
- `openremit-mock-fx` (9999), `openremit-mock-payout` (9998) — WireMock
- `openremit-debezium` (8083) — Connect standalone
- `openremit-debezium-register` — outbox 커넥터 자동 등록 (1회성, retry 6회)

검증:
```bash
# Debezium 커넥터 상태
curl -sS http://localhost:8083/connectors/openremit-outbox/status | jq

# remittance-api 기동
./gradlew :remittance-api:bootRun
# 다른 터미널에서 payout-worker 기동
./gradlew :payout-worker:bootRun

# 송금 요청 (POST /api/v1/remittances) 후 토픽에 흐르는지 확인
docker exec -it openremit-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic remittance.paid --from-beginning
```

볼륨 초기화가 필요하면 `docker compose down -v` 후 재기동 (mysql-init 스크립트가 다시 실행되어 Debezium 사용자가 생성됨).
