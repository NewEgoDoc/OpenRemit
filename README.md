# OpenRemit

[![CI](https://github.com/NewEgoDoc/OpenRemit/actions/workflows/ci.yml/badge.svg)](https://github.com/NewEgoDoc/OpenRemit/actions/workflows/ci.yml)

송금 + 내부 결제 게이트웨이 백엔드. 사용자 결제 → 환전 → 해외 송금 → 수취인 Webhook 알림 → 일일 정산까지의 **머니 무브먼트 전체 흐름**을 멀티모듈 모놀리스 안에서 구현합니다. 모놀리스이지만 모듈 간 결합도는 마이크로서비스에 가깝게 분리해, **DB 테이블 소유권 / 마이그레이션 히스토리 / 이벤트 토픽**을 모듈별로 나눴습니다.

### 증명 포인트

- **트랜잭션 정합성 + 동시성 제어** — Redisson 분산 락 + JPA `@Version` 이중 방어 ([↓](#동시성-제어--분산-락--낙관적-락-이중-방어))
- **외부 API 장애 대응 + 비동기** — Resilience4j Circuit Breaker + Stale Fallback ([↓](#circuit-breaker--stale-fallback--환율-api-장애-대응)), Outbox + Debezium CDC ([↓](#왜-worker가-kafkatemplate으로-직접-publish-하지-않는가))
- **인덱스/쿼리 최적화** — EXPLAIN before/after, filesort 제거 ([↓](#인덱스-튜닝-사례--remittances-사용자별-최신순-조회))
- **운영 관점** — 멱등성 / 정산 배치 A·B 이중 검증 ([↓](#정산-배치-reconciler--왜-ab-두-검증을-모두-돌리는가)) / Prometheus·Grafana / 구조화 JSON 로깅

## 시스템 아키텍처

```
              ┌──────────────────────────────────────┐
              │         Client (REST + JWT)          │
              └─────────────┬────────────────────────┘
                            │
                            ▼
   ┌────────────────────────────────────────────────────┐
   │  remittance-api  (Resilience4j · Idempotency-Key)  │
   │   │                                                │       ┌──────────────┐
   │   └── payment-module (in-process gateway) ─────────│──────▶│ WireMock FX  │
   └──────────────┬─────────────────────────────────────┘       │   (9999)     │
                  │ outbox INSERT (single tx)                   └──────────────┘
                  ▼
            ┌──────────┐                              ┌────────────────────┐
            │  MySQL   │ ── binlog ──▶ Debezium ───▶ │       Kafka        │
            └──────────┘                              └─────────┬──────────┘
                                                                │
                  ┌─────────────────────────────────────────────┼──────────────────┐
                  ▼                                             ▼                  ▼
        ┌──────────────────┐               ┌──────────────────────┐    ┌────────────────────┐
        │   payout-worker  │ ── 송금사 ──▶ │ webhook-dispatcher    │    │ remittance-api     │
        │  (자체 outbox)   │  WireMock 9998│ (Exponential Backoff) │    │  result consumer   │
        └──────────────────┘               └──────────┬────────────┘    └────────────────────┘
                                                       ▼
                                              WireMock webhook (9997)

   ┌─────────────────┐    ┌──────────────────────┐    ┌──────────────────────┐
   │ Redis (락+캐시) │    │ reconciler (배치 4시)  │    │ Prometheus + Grafana │
   └─────────────────┘    └──────────────────────┘    └──────────────────────┘
```

4개 Spring Boot 모듈(`remittance-api` · `payout-worker` · `webhook-dispatcher` · `reconciler`) + 라이브러리 모듈 `payment-module`. MySQL 인스턴스는 1개지만 **테이블 소유권은 모듈별로 분리**(`remittance-api`만 `remittances`/`wallets`, `payout-worker`는 `payout_attempts`/`payout_outbox` 등). Flyway 히스토리 테이블도 모듈별로 분리(`flyway_schema_history`, `_payout`, `_webhook`, `_reconcile`)해, 한 모듈의 마이그레이션 충돌이 다른 모듈을 막지 않습니다.

## 실행

두 가지 모드를 지원합니다. **개발 모드**는 인프라만 컨테이너로 띄우고 4개 앱은 IDE/호스트 JVM에서 `bootRun`, **풀스택 모드**는 4개 앱까지 컨테이너로 한 번에 기동합니다.

### 개발 모드 (인프라만)

```bash
docker compose up -d                       # 인프라만 (MySQL/Redis/Kafka/Debezium/WireMock×3/Prometheus/Grafana)
./gradlew :remittance-api:bootRun          # 다른 터미널에서 모듈별 bootRun
```

### 풀스택 모드 — 1-command 실행 (`--profile app`)

```bash
docker compose --profile app up -d --build
```

4개 앱(remittance-api / payout-worker / webhook-dispatcher / reconciler)까지 컨테이너로 띄웁니다. 첫 실행은 Gradle 의존성 다운로드 때문에 수 분 걸리지만, 이후 코드 변경 시에는 layered JAR의 `application` layer만 갱신되어 빌드/푸시가 빠릅니다(상세는 ["컨테이너화 — Layered JAR + ARG MODULE"](#컨테이너화--layered-jar--arg-module) 섹션).

헬스체크 (두 모드 공통):
```
http://localhost:8080/actuator/health   # remittance-api
http://localhost:8081/actuator/health   # payout-worker
http://localhost:8082/actuator/health   # webhook-dispatcher
http://localhost:8084/actuator/health   # reconciler
```

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

## 동시성 제어 — 분산 락 + 낙관적 락 (이중 방어)

같은 사용자(`userId`)가 동시에 여러 송금 요청을 보낼 때 **잔액이 음수가 되거나 차감이 한 번만 반영되는 race**를 막아야 합니다. 한 겹으로는 부족해 두 겹으로 보호합니다.

**1차 방어 — Redisson 분산 락 (`wallet:{userId}`)**

여러 인스턴스가 떠 있어도 같은 사용자에 대해서는 직렬화됩니다. `RemittanceCreateUseCase`(락 보유) ↔ `RemittanceCreator`(`@Transactional` 메서드) 두 빈으로 분리해 **락이 트랜잭션 commit을 enclose**하는 순서를 만듭니다(자기호출 AOP 제약 회피).

`leaseTime`을 명시하지 않아 Redisson **watchdog 모드** (기본 30초 TTL을 `lockWatchdogTimeout/3` 주기로 자동 갱신). 외부 결제/환율 호출이 길어져도 락이 만료되지 않고, 보유 스레드가 죽으면 watchdog 갱신이 멈춰 자동 해제됩니다 — 데드락 안전.

**2차 방어 — JPA `@Version` (낙관적 락)**

분산 락은 네트워크 분할이나 lease 만료 같은 비상 상황에서 일시적으로 깨질 수 있습니다. 그때 두 트랜잭션이 같은 wallet에 동시 진입하더라도 DB 커밋 단계에서 `OptimisticLockException`으로 막히도록 `wallet`에 `@Version`을 함께 둡니다 — defense in depth.

**왜 `SELECT ... FOR UPDATE`가 아닌가**

비관적 DB 락은 락 보유 중 외부 결제 호출(수백 ms~수 초)이 들어가면 DB connection이 그 시간만큼 잡혀 풀이 빠르게 소진됩니다. 락은 외부, 트랜잭션은 내부 — 두 범위를 분리하는 편이 안전합니다.

**검증 결과**

10 스레드가 동시에 송금 요청 (잔액 100,000 / 요청당 30,000) → 정확히 **3건 성공 + 7건 실패**, 잔액 10,000으로 정합. `RemittanceConcurrencyTest`로 자동 검증.

## Circuit Breaker + Stale Fallback — 환율 API 장애 대응

환율 API는 **외부 의존성**이고 송금 기능의 깊은 곳에 있습니다. 단순히 Circuit Breaker만 두면 회로가 OPEN인 동안 모든 송금 요청이 실패하므로, **fresh + stale 두 단계 캐시**로 한 번 더 감쌉니다(Stale-while-degraded).

**3단 폴백 흐름**

```
1. fresh cache (Redis, TTL 60초) hit → 즉시 반환
2. miss → 외부 API 호출 (Retry + Circuit Breaker 적용)
   ├─ 성공 → fresh + stale(TTL 24h) 둘 다 갱신, 반환
   └─ 실패 (Retry 후에도 실패 / CB OPEN)
       ├─ stale cache hit → stale 값 반환 (열화 모드)
       └─ stale도 없으면 예외 → 송금 거부
```

**계층 순서 — Retry outer, CB inner**

```
[Retry] → [Circuit Breaker] → 실제 호출
```

Retry가 바깥, Circuit Breaker가 안쪽입니다. CB가 OPEN이면 Retry가 호출 자체를 시도조차 안 하고 즉시 fallback으로 빠져 **fast-fail** 합니다(외부 API에 추가 부하 X). CB가 CLOSED일 때만 Retry가 5xx에 대해 재시도해 일시적 장애를 흡수합니다.

**왜 Resilience4j인가** — Spring Cloud Circuit Breaker의 백엔드 중 가장 가볍고(Hystrix 대비 1/10 크기, Hystrix는 maintenance mode), Spring Boot 3+ Actuator 메트릭 자동 노출. Grafana 대시보드의 `resilience4j_circuitbreaker_state` 패널이 그대로 사용됩니다.

**검증 결과**

6개 시나리오 통합 테스트로 검증: 정상 / fresh hit / 5xx 후 stale 폴백 / stale 없으면 예외 / CB OPEN fast-fail / 동일 통화 short-circuit. WireMock으로 외부 API 5xx/타임아웃 시뮬.

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

## 부하 테스트 — 가상 스레드 ON vs OFF (K6)

송금 생성(`POST /api/v1/remittances`) 시나리오를 K6로 1,000 VU 동시 ramp-up 하고, **`spring.threads.virtual.enabled` 토글**로 가상 스레드 ON/OFF를 비교합니다. ADR-006에서 가상 스레드 채택의 정량 근거로 명시한 검증 기준입니다.

**시나리오 (`docs/perf/k6/remittance.js`)**

- 가상 사용자(VU) 0 → 1000으로 60초 ramp-up + 60초 sustain (총 2분)
- 사용자 1,000명을 미리 시드(`docs/perf/k6/prepare.sh`로 sign-up + wallet 초기 잔액 충전)하고 **1 VU = 1 사용자** 1:1 매핑으로 **분산 락 충돌 없이 순수 처리량/지연**을 측정 (동시성 제어 정확도는 별도 단위 테스트가 담당)
- WireMock(환율/송금사/Webhook)에 100ms 고정 지연을 주입해 외부 I/O 부하 흉내

**환경**

docker-compose 풀스택 모드 (`--profile app`) — Java 21 / Spring Boot 4.0 / 컨테이너 내 heap 512MB. 호스트는 macOS arm64.

**측정 결과** (ramp 1m + sustain 1m, 총 2m)

| 지표 | 가상 스레드 OFF (플랫폼) | 가상 스레드 ON | 비교 |
|---|---|---|---|
| 처리량 (iterations/s) | **366.7** | 342.0 | OFF 7% ↑ |
| 총 성공 요청 | 53,352 | 47,340 | — |
| p50 (ms) | 1,760 | **1,620** | ON 8% ↓ |
| p95 (ms) | **2,670** | 4,400 | OFF 39% ↓ |
| p99 (ms) | **3,060** | 6,060 | OFF 50% ↓ |
| max (ms) | **4,180** | 11,150 | OFF 62% ↓ |
| 에러율 | 0.00% | 0.00% | 동일 |

**해석 — ADR-006 가정 vs 측정 현실**

본 시나리오에서 **가상 스레드 ON이 tail latency에서 명백한 페널티**를 보였습니다. 처리량/median은 비슷하지만 p95·p99·max에서 ON이 1.6~2.7배 느립니다. 원인 추정 두 가지:

1. **외부 I/O 비중이 작음.** ADR-006은 "I/O 바운드 압도적"을 가상 스레드 채택 근거로 들었지만, 본 시나리오는 **fresh fx cache hit가 지배적**(60s TTL Redis, 측정 2분 동안 첫 호출 외 모두 캐시 hit)이라 실제 외부 HTTP 대기 시간이 거의 없습니다. 가상 스레드가 빛나는 "수많은 가벼운 스레드가 외부 응답을 기다림" 패턴이 만들어지지 않습니다.
2. **JDBC + Hibernate의 `synchronized` 핀.** Hibernate session, JDBC connection 획득 경로에 `synchronized` 블록이 있어 가상 스레드가 캐리어 스레드를 잡고 풀에서 빠지지 못하는 **carrier pinning**이 발생합니다. fresh cache hit 워크로드에서는 DB 비중이 상대적으로 커 핀의 영향이 두드러집니다.

**시사점** — "가상 스레드는 항상 빠르다"가 아니라 **워크로드에 따라 이득과 페널티가 갈립니다**. 프로덕션 트래픽이 외부 결제/송금사 호출(수백 ms~수 초)이 dominant하면 가상 스레드의 이점이 살아날 가능성이 높지만, 본 측정처럼 캐시 친화적 + 짧은 DB 트랜잭션 워크로드에서는 플랫폼 스레드가 유리합니다. ADR-006은 이 측정을 근거로 **"가상 스레드 ON 유지 + 핀 모니터링 + 트래픽 패턴 변경 시 재측정"** 입장입니다(상세는 [`docs/08-decisions.md`](../docs/08-decisions.md#adr-006)).

> K6 스크립트와 실행 가이드(ON/OFF 재현 절차 포함): [`docs/perf/k6/`](../docs/perf/k6/). 원본 결과 JSON은 로컬 측정 산출물이므로 `.gitignore` 처리되어 있으며, 위 표는 `k6 run --summary-export` 출력에서 발췌한 수치입니다.

## 테스트

```bash
./gradlew build
```

통합 테스트는 Testcontainers로 MySQL 8 + Redis 7 + Kafka를 자동 기동합니다 (Docker 필요).
GitHub Actions CI에서도 동일한 Testcontainers 기반으로 매 커밋 검증.

> 통합 테스트는 KafkaTemplate으로 토픽에 직접 발행해 컨슈머/Outbox INSERT 동작만 검증합니다. binlog → Debezium → Kafka 단계는 docker-compose 실환경 검증으로 분리합니다 (ADR-012).

## docker-compose 풀스택 검증 (Debezium 포함 E2E)

기동 컨테이너 (default 프로필 — 인프라만):
- `openremit-mysql` (3306, binlog ROW + GTID 활성)
- `openremit-redis` (6379)
- `openremit-kafka` (9092 host / 29092 internal, KRaft single-node)
- `openremit-mock-fx` (9999), `openremit-mock-payout` (9998), `openremit-mock-webhook` (9997) — WireMock
- `openremit-debezium` (8083) — Connect standalone
- `openremit-debezium-register` — outbox 커넥터 자동 등록 (1회성, retry 6회)
- `openremit-prometheus` (9090) — 풀스택 모드의 컨테이너 앱(`remittance-api:8080` 등)을 직접 scrape. 호스트 JVM 모드에서는 Grafana 대시보드 비어 보임(트레이드오프)
- `openremit-grafana` (3000) — anonymous Viewer 허용, datasource(`prometheus`) + 대시보드(`openremit`) provisioning

`--profile app` 추가 시 4개 앱 컨테이너도 기동:
- `openremit-remittance-api` (8080:8080), `openremit-payout-worker` (8081:8080), `openremit-webhook-dispatcher` (8082:8080), `openremit-reconciler` (8084:8080)

E2E 검증:
```bash
docker compose --profile app up -d --build

# Debezium 커넥터 상태
curl -sS http://localhost:8083/connectors/openremit-outbox/status | jq

# 4개 앱 헬스
for p in 8080 8081 8082 8084; do curl -fsS http://localhost:$p/actuator/health; echo; done

# 송금 요청(POST /api/v1/remittances) 후 토픽에 흐르는지 확인
docker exec -it openremit-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic remittance.paid --from-beginning
```

볼륨 초기화가 필요하면 `docker compose --profile app down -v` 후 재기동 (mysql-init 스크립트가 다시 실행되어 Debezium 사용자가 생성됨).

## 컨테이너화 — Layered JAR + ARG MODULE

루트의 단일 [`Dockerfile`](Dockerfile) 한 벌로 4개 Spring Boot 모듈을 모두 빌드합니다. 두 가지 결정이 들어가 있습니다.

**1. `ARG MODULE` — Dockerfile 한 벌로 4개 이미지**

```dockerfile
ARG MODULE
RUN ./gradlew :${MODULE}:bootJar --no-daemon -x test
COPY --from=build /workspace/${MODULE}/build/libs/*.jar app.jar
```

`docker-compose.yml`에서 모듈마다 `build.args.MODULE`만 다르게 넘겨 4번 호출. Dockerfile 4개 복붙을 피하고, 빌드 패턴 변경 시 한 곳만 수정하면 됩니다.

**2. Layered JAR — 변경 빈도가 다른 4개 layer를 분리**

Spring Boot의 `tools` jarmode로 fat JAR을 layer별로 분해해 별도 `COPY`로 가져옵니다.

```dockerfile
RUN java -Djarmode=tools -jar app.jar extract --layers --destination .
COPY --from=extract /extract/dependencies/          ./   # 거의 안 바뀜
COPY --from=extract /extract/spring-boot-loader/    ./   # 거의 안 바뀜
COPY --from=extract /extract/snapshot-dependencies/ ./   # 가끔
COPY --from=extract /extract/application/           ./   # 매 커밋
```

코드만 바꾼 재빌드에서 dependencies layer는 Docker 캐시 hit로 재전송 0, application layer(수백 KB)만 갱신됩니다. 단순 `COPY *.jar` 방식은 코드 한 줄 변경에도 fat JAR 전체(수십 MB)가 새 layer로 묶여 레지스트리 푸시·노드 풀 비용을 그대로 받습니다.

**3. 컨테이너 내부 포트 표준화**

application.yaml의 `server.port`(8080/8081/8082/8084)는 호스트 JVM 모드를 위한 값이고, 컨테이너에서는 `SERVER_PORT=8080`을 ENV로 주입해 모두 8080을 듣게 합니다. HEALTHCHECK·Prometheus scrape 경로를 컨테이너 단에서 단일화하고, 호스트 포트는 매핑 단계(`8081:8080` 등)에서만 분리합니다.

## 관측성 — 메트릭 / 로그 (Day 12)

### 메트릭

각 Spring Boot 모듈이 `/actuator/prometheus`를 노출하고, `docker-compose`의 Prometheus가 풀스택 모드의 컨테이너 앱(`remittance-api:8080` 등)을 직접 scrape 합니다. 호스트 JVM 모드(인프라만 띄우고 IDE에서 `bootRun`)에서는 Grafana 대시보드가 비어 보이며, raw 메트릭은 `curl localhost:808x/actuator/prometheus`로 확인합니다 — 호스트 JVM 잡과 컨테이너 잡을 동시에 두면 `host-published` 포트를 통해 같은 인스턴스가 중복 scrape 되어 Grafana 집계가 2배로 보이는 문제가 있어 풀스택 모드 정확도를 우선했습니다.

```bash
curl -s http://localhost:8080/actuator/prometheus | head    # remittance-api
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job, health}'
```

| 모듈 | host 포트 | 컨테이너 포트 | prometheus 라벨 |
|---|---|---|---|
| `remittance-api` | 8080 | 8080 | `service="remittance-api"` |
| `payout-worker` | 8081 | 8080 | `service="payout-worker"` |
| `webhook-dispatcher` | 8082 | 8080 | `service="webhook-dispatcher"` |
| `reconciler` | 8084 | 8080 | `service="reconciler"` |

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
