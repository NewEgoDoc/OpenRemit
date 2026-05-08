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

## 정산 배치 (`reconciler`) — 왜 A·B 두 검증을 모두 돌리는가

`잔액 = Σ(거래 내역)` 이라는 한 줄짜리 정합성 명제는 실제 시스템에서는 **같은 BigDecimal 비교지만 잡는 결함의 종류가 서로 다른 두 검증** 으로 쪼개집니다. reconciler 는 매일 두 검증을 모두 수행해 `reconciliations.mismatch_count` 에 기록합니다.

**A-검증 (총합 무결성)**

```
wallet.balance == Σ wallet_transactions.amount   // 양수=입금, 음수=출금
```

지갑의 모든 ledger row 의 amount 합과 현재 잔액이 일치하는지. **누락되거나 오기록된 ledger row** 를 잡습니다. 예: 송금 use case 에서 wallet.withdraw 만 하고 ledger insert 가 빠진 코드 경로 — 잔액은 차감됐지만 ledger row 가 없어 합이 안 맞습니다.

**B-검증 (마지막 갱신 무결성)**

```
wallet.balance == 마지막 wallet_transactions.balance_after   // id DESC LIMIT 1
```

가장 최근 ledger row 의 `balance_after` 스냅샷과 현재 잔액이 일치하는지. **race 로 인한 last-write 손실, 트랜잭션 외부에서 일어난 balance 직접 수정** 을 잡습니다. 예: 운영자가 SQL 콘솔에서 `UPDATE wallets SET balance = ...` 를 직접 친 경우 — ledger 합은 그대로지만 마지막 balance_after 와 어긋납니다.

**왜 둘 중 하나만으로는 부족한가**

| 결함 시나리오 | A 만 돌리면 | B 만 돌리면 | A+B 둘 다 |
|---|---|---|---|
| ledger row 1건 누락 | 탐지 (합 불일치) | 탐지 못 함 (마지막 balance_after 는 우연히 일치 가능) | 탐지 |
| balance 직접 SQL 수정 | 탐지 못 함 (합은 그대로) | 탐지 (마지막 balance_after 와 어긋남) | 탐지 |
| 양쪽 모두 부정합 | 탐지 | 탐지 | 탐지 (두 플래그 모두 켜짐) |

A 와 B 는 **서로 다른 클래스의 결함** 을 잡습니다. ledger 가 단일 출처(single source of truth)라면 둘이 동일해 보이지만, 실제 incident 는 ledger 외부 경로(콘솔, 데이터 패치, 잘못된 마이그레이션, race condition)에서도 발생합니다. 두 검증 모두 통과해야 비로소 "잔액 = Σ(거래 내역)" 의 의미적 보장이 성립합니다.

mismatch 가 발견되어도 잡 자체는 SUCCESS 로 종료합니다. `reconciliations.mismatch_count > 0` 을 별도 alerting 트리거로 쓰는 편이, "잡 결함" 과 "데이터 결함" 을 메타데이터 측면에서 구분할 수 있어 운영상 명확합니다.

실행:
```bash
./gradlew :reconciler:bootRun         # 매일 04:00 KST 자동 (openremit.reconcile.cron 으로 변경)
```

## 인덱스 튜닝 사례 — `remittances` 사용자별 최신순 조회

대상 쿼리: `RemittanceRepository.findByUserIdOrderByCreatedAtDesc(userId)`

```sql
SELECT * FROM remittances WHERE user_id = ? ORDER BY created_at DESC;
```

측정 환경: MySQL 8 / 사용자 10,000명 × 송금 50건 = `remittances` 500,000행 (시드: `docs/perf/seed.sql`).

**BEFORE — `idx_remittances_user_status (user_id, status)` 만 존재**

```
EXPLAIN: type=ref  key=idx_remittances_user_status  rows=50  Extra=Using filesort

EXPLAIN ANALYZE:
  -> Sort: remittances.created_at DESC  (cost=17.5 rows=50)
       (actual time=0.10..0.43 rows=50 loops=1)
      -> Index lookup on remittances using idx_remittances_user_status (user_id=…)
         (cost=17.5 rows=50) (actual time=0.018..0.243 rows=50 loops=1)
```

`(user_id, status)` 인덱스는 user_id 필터까지만 도와주고 `ORDER BY created_at` 단계에서 **별도 정렬(filesort)** 이 발생합니다.

**AFTER — V6 마이그레이션으로 `(user_id, created_at)` 인덱스 추가**

```sql
ALTER TABLE remittances ADD INDEX idx_remittances_user_created (user_id, created_at);
```

```
EXPLAIN: type=ref  key=idx_remittances_user_created  rows=50  Extra=Backward index scan

EXPLAIN ANALYZE:
  -> Index lookup on remittances using idx_remittances_user_created (user_id=…) (reverse)
     (cost=17.5 rows=50) (actual time=0.010..0.292 rows=50 loops=1)
```

옵티마이저가 새 인덱스를 선택해 **plan에서 Sort 노드 자체가 제거**됩니다. `created_at`을 인덱스 정의에 ASC로 남기고 InnoDB의 backward index scan으로 DESC 요구를 그대로 처리합니다.

| 관점 | BEFORE | AFTER |
|---|---|---|
| Plan 노드 | Sort + Index lookup (2단계) | Index lookup (reverse) (1단계) |
| 정렬 비용 | filesort (사용자당 N건 정렬) | 0 (인덱스가 이미 정렬) |
| 시간 복잡도 | O(N log N) | O(N) |
| 측정 시간 (50건) | 0.10 ~ 0.43 ms | 0.05 ~ 0.29 ms |

50행 정렬은 절대 비용이 작아 시간 차이는 마이크로초 단위지만, **사용자당 송금 건수 N이 커질수록 격차가 비선형으로 벌어집니다** (filesort N log N vs 인덱스 스캔 N).

**왜 기존 `(user_id, status)` 인덱스를 유지하는가**

두 인덱스는 워크로드가 다릅니다.

| 쿼리 | 옵티마이저가 고르는 인덱스 |
|---|---|
| `WHERE user_id=? ORDER BY created_at DESC` | `idx_remittances_user_created` (이번에 추가) |
| `WHERE user_id=? AND status=?` (정렬 없음) | `idx_remittances_user_status` (기존) |

`(user_id, status, created_at)` 한 개로 둘을 묶는 안도 있지만 status enum 카디널리티가 6에 불과해 prefix 가치가 떨어집니다. 워크로드별로 인덱스를 분리하는 편이 더 명확합니다.

자세한 표·전체 EXPLAIN 출력은 [`docs/04-erd.md`](../docs/04-erd.md#인덱스-튜닝-사례--remittances-사용자별-최신순-조회) 참고.

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
- `openremit-mock-fx` (9999), `openremit-mock-payout` (9998), `openremit-mock-webhook` (9997) — WireMock
- `openremit-debezium` (8083) — Connect standalone
- `openremit-debezium-register` — outbox 커넥터 자동 등록 (1회성, retry 6회)
- `openremit-prometheus` (9090) — `host.docker.internal`로 호스트 JVM의 4개 모듈 scrape
- `openremit-grafana` (3000) — anonymous Viewer 허용, datasource(`prometheus`) + 대시보드(`openremit`) provisioning

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

## 관측성 — 메트릭 / 로그 (Day 12)

### 메트릭

각 Spring Boot 모듈이 `/actuator/prometheus`를 노출하고, `docker-compose`의 Prometheus가 `host.docker.internal:808x`로 scrape 합니다.

```bash
curl -s http://localhost:8080/actuator/prometheus | head    # remittance-api
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[].health'
```

| 모듈 | actuator 포트 | prometheus 라벨 |
|---|---|---|
| `remittance-api` | 8080 | `service="remittance-api"` |
| `payout-worker` | 8081 | `service="payout-worker"` |
| `webhook-dispatcher` | 8082 | `service="webhook-dispatcher"` |
| `reconciler` | 8084 | `service="reconciler"` |

Grafana 접속: `http://localhost:3000` (anonymous Viewer). 좌측 메뉴 → Dashboards → **OpenRemit / OpenRemit — Service Overview** 자동 로드. 대시보드 UID는 `openremit`이며 [`docker/grafana/dashboards/openremit.json`](docker/grafana/dashboards/openremit.json)에서 관리합니다.

### 로깅

`logback-spring.xml`은 `common` 모듈에 단일 정의되며 profile에 따라 분기합니다.

- **`local`** (개발): 사람이 읽기 좋은 콘솔 텍스트
- **그 외 default/docker/prod**: 한 줄 JSON (`logstash-logback-encoder` 8.0)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :remittance-api:bootRun   # 텍스트
./gradlew :remittance-api:bootRun                                # JSON (default)
```

JSON 한 줄에는 `@timestamp`, `level`, `logger_name`, `thread_name`, `message`, `appName`(=spring.application.name), `app`(customField, 동일 값) 필드가 부착되어 Loki/ELK/Datadog 같은 수집기에서 즉시 라벨링 가능합니다.
