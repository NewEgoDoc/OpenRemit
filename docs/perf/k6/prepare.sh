#!/usr/bin/env bash
# K6 부하 테스트 사전 준비 — N명 sign-up + wallet 잔액 충전 + users.json 생성
#
# 사용법:
#   docker compose --profile app up -d --build      # 풀스택 기동 (선행)
#   N=1000 BASE_URL=http://localhost:8080 ./prepare.sh
#
# 결과:
#   users        +N    (loadtest-user-N@loadtest.local, password=K6password1!)
#   wallets      +N    (KRW, balance=WALLET_BALANCE)
#   users.json         (이메일/비번 목록 — remittance.js가 읽음)
set -euo pipefail

N=${N:-1000}
BASE_URL=${BASE_URL:-http://localhost:8080}
PASSWORD="K6password1!"
DOMAIN="loadtest.local"
EMAIL_PREFIX="loadtest-user"
WALLET_BALANCE=${WALLET_BALANCE:-100000000}
MYSQL_CONTAINER=${MYSQL_CONTAINER:-openremit-mysql}
DIR="$(cd "$(dirname "$0")" && pwd)"

echo "[prepare] sign up $N users at $BASE_URL ..."
for i in $(seq 1 "$N"); do
  email="${EMAIL_PREFIX}-${i}@${DOMAIN}"
  curl -fsS -X POST "$BASE_URL/api/v1/auth/signup" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"$PASSWORD\",\"name\":\"LoadTest $i\"}" \
    >/dev/null 2>&1 || echo "[prepare] signup skip (already exists?): $email"
  if (( i % 100 == 0 )); then echo "  signed up $i / $N"; fi
done

echo "[prepare] topup wallets to $WALLET_BALANCE KRW ..."
docker exec -i "$MYSQL_CONTAINER" mysql -uroot -prootpw openremit <<SQL
SET sql_log_bin = 0;
UPDATE wallets w
  JOIN users u ON u.id = w.user_id
SET w.balance = $WALLET_BALANCE,
    w.version = w.version + 1
WHERE u.email LIKE '${EMAIL_PREFIX}-%@${DOMAIN}';
SELECT CONCAT('topped up wallets: ', ROW_COUNT()) AS info;
SQL

echo "[prepare] writing users.json (N=$N) ..."
{
  echo "["
  for i in $(seq 1 "$N"); do
    sep=","
    [ "$i" -eq "$N" ] && sep=""
    echo "  {\"email\":\"${EMAIL_PREFIX}-${i}@${DOMAIN}\",\"password\":\"$PASSWORD\"}$sep"
  done
  echo "]"
} > "$DIR/users.json"

echo "[prepare] done. users.json with $N entries at $DIR/users.json"
