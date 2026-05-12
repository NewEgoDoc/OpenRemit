# K6 부하 테스트 — 가상 스레드 ON vs OFF

ADR-006(Java 21 가상 스레드 채택)의 정량 검증용 시나리오. `POST /api/v1/remittances` 1,000 VU ramp-up.

## 파일

| 파일 | 역할 |
|---|---|
| `prepare.sh` | N명 sign-up + wallet 잔액 충전 + `users.json` 생성 |
| `remittance.js` | K6 시나리오 (setup login + ramp-up POST) |
| `users.json` | prepare.sh가 생성, remittance.js가 읽음 (gitignored) |

## 실행 흐름

```bash
# 1) 풀스택 기동 (4개 앱 + 인프라)
cd ../../../        # OpenRemit 루트
docker compose --profile app up -d --build

# 4개 앱 health 확인
for p in 8080 8081 8082 8084; do curl -fsS http://localhost:$p/actuator/health; echo; done

# 2) 사용자 시드 + 토큰 풀 준비
cd docs/perf/k6
N=1000 ./prepare.sh

# 3) K6 실행 — 가상 스레드 ON 기본
k6 run remittance.js

# 4) 가상 스레드 OFF로 전환 후 재측정
# docker-compose.yml 의 4개 앱 서비스(remittance-api/payout-worker/webhook-dispatcher/reconciler)는
# SPRING_THREADS_VIRTUAL_ENABLED 환경 변수를 직접 받지 않으므로, override 파일로 주입한다.
cd ../../../        # OpenRemit 루트
cat > docker-compose.override.yml <<'EOF'
services:
  remittance-api:    { environment: { SPRING_THREADS_VIRTUAL_ENABLED: "false" } }
  payout-worker:     { environment: { SPRING_THREADS_VIRTUAL_ENABLED: "false" } }
  webhook-dispatcher: { environment: { SPRING_THREADS_VIRTUAL_ENABLED: "false" } }
  reconciler:        { environment: { SPRING_THREADS_VIRTUAL_ENABLED: "false" } }
EOF
docker compose --profile app up -d --force-recreate

cd docs/perf/k6
k6 run remittance.js

# 측정 후 override 제거 + ON 상태로 복귀
cd ../../../
rm docker-compose.override.yml
docker compose --profile app up -d --force-recreate
```

## 환경 변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | remittance-api 엔드포인트 |
| `N` | 1000 | 사전 시드할 사용자 수 (= VU 권장값) |
| `WALLET_BALANCE` | 100000000 | 시드 wallet 초기 잔액 (KRW) |
| `VU` | 1000 | K6 ramp-up 목표 동시 사용자 수 |
| `RAMP` | `1m` | 0 → VU ramp-up 시간 |
| `DURATION` | `1m` | sustain 시간 |

## 시나리오 의도

- **N = VU** (1:1 매핑) — 같은 사용자에 동시 송금이 들어오면 분산 락이 직렬화하므로 노이즈가 됨. 1 VU = 1 사용자로 매핑해 **순수 처리량/지연**만 측정.
- **`Idempotency-Key`** — 매 요청 unique. 같은 키면 멱등 캐시로 빠르게 응답되어 측정 왜곡.
- **분산 락 충돌 = 0**, **wallet 잔액 충분**(요청당 10,000 KRW × 60초 sustain ≪ 1억 KRW) — 송금 자체는 항상 성공해야 함.
- 외부 fx API는 fresh cache(60s) 도입 후 거의 모든 요청이 캐시 hit. 외부 I/O 지연이 측정에 영향을 거의 안 줌. 가상 스레드 ON/OFF 차이가 의도보다 작게 나올 수 있음 — 측정 후 결과 해석에 명시.

## 정리

```bash
docker compose --profile app down -v        # 볼륨 포함 초기화 (시드 사용자/wallet 삭제)
rm -f users.json results-on.json results-off.json run-on.log run-off.log
```

가상 스레드 OFF 측정용 `docker-compose.override.yml`을 만들었다면 반드시 삭제 후 `--force-recreate`로 ON 상태로 복귀시키세요. compose는 override 파일이 존재하면 자동 병합하므로 남겨 두면 이후 모든 기동이 OFF 상태로 뜹니다.
