// K6 부하 테스트 — POST /api/v1/remittances 동시 1000 VU ramp-up.
// 가상 스레드 ON/OFF 비교용 시나리오. ADR-006 검증 기준.
//
// 사용법:
//   ./prepare.sh                           # users.json 생성 (선행)
//   k6 run remittance.js                   # 기본값 VU=1000, RAMP=1m, DURATION=1m
//   VU=500 RAMP=30s DURATION=1m k6 run remittance.js
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VU_TARGET = parseInt(__ENV.VU || '1000');
const RAMP_DURATION = __ENV.RAMP || '1m';
const SCENARIO_DURATION = __ENV.DURATION || '1m';

const users = new SharedArray('users', function () {
  return JSON.parse(open('./users.json'));
});

export const options = {
  setupTimeout: '5m',
  scenarios: {
    remittance_create: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP_DURATION, target: VU_TARGET },
        { duration: SCENARIO_DURATION, target: VU_TARGET },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    'http_req_duration{expected_response:true}': ['p(95)<2000', 'p(99)<5000'],
  },
};

// setup(): 모든 사용자에게 토큰 발급 후 default()에 전달.
// http.batch로 chunk 병렬화해 N=1000일 때 setupTimeout 안에 끝나도록 한다.
export function setup() {
  if (users.length === 0) {
    throw new Error('users.json is empty. Run ./prepare.sh first.');
  }
  const CHUNK = 50;
  console.log(`[setup] login ${users.length} users at ${BASE_URL} (chunk=${CHUNK}) ...`);
  const tokens = new Array(users.length);
  for (let i = 0; i < users.length; i += CHUNK) {
    const slice = users.slice(i, i + CHUNK);
    const reqs = slice.map((u) => ({
      method: 'POST',
      url: `${BASE_URL}/api/v1/auth/login`,
      body: JSON.stringify({ email: u.email, password: u.password }),
      params: { headers: { 'Content-Type': 'application/json' } },
    }));
    const responses = http.batch(reqs);
    for (let j = 0; j < responses.length; j++) {
      if (responses[j].status !== 200) {
        throw new Error(`login failed for ${slice[j].email}: ${responses[j].status} ${responses[j].body}`);
      }
      tokens[i + j] = responses[j].json('access_token');
    }
  }
  console.log(`[setup] obtained ${tokens.length} tokens`);
  return { tokens };
}

export default function (data) {
  // VU id를 토큰 풀 인덱스로 사용 — 동일 VU = 동일 사용자 (1:1 매핑)
  const idx = (exec.vu.idInTest - 1) % data.tokens.length;
  const token = data.tokens[idx];

  const idemKey = `k6-${exec.vu.idInTest}-${exec.scenario.iterationInTest}-${Date.now()}`;
  const payload = JSON.stringify({
    from_currency: 'KRW',
    from_amount: 10000,
    to_currency: 'USD',
    receiver_name: `k6-recv-${idx}`,
    receiver_account: `K6-ACC-${idx}`,
    method: 'CARD',
  });

  const res = http.post(`${BASE_URL}/api/v1/remittances`, payload, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      'Idempotency-Key': idemKey,
    },
  });

  check(res, {
    'status is 201': (r) => r.status === 201,
  });
}
