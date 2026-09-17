/*
 * k6 load test for user-service.
 *
 * Measures the THREE paths separately, because they differ by orders of magnitude:
 *
 *   read        GET /users/me            local JWT verification (~0.1ms), no network to Keycloak
 *   login       Keycloak password grant  bcrypt, deliberately slow (~250-400ms of CPU)
 *   register    POST /auth/register      DB write + Keycloak Admin API call
 *
 * Conflating these is the most common load-testing mistake. A system that does 1000 reads/sec
 * may only do 50 logins/sec, and that is not a defect -- password hashing is *designed* to be
 * expensive so offline cracking is infeasible.
 *
 *   k6 run scripts/load-test.js                       # all scenarios
 *   k6 run -e SCENARIO=read  -e RATE=1000 scripts/load-test.js
 *   k6 run -e SCENARIO=login -e RATE=50   scripts/load-test.js
 */
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const API   = __ENV.API   || 'http://localhost:7701';
const KC    = __ENV.KC    || 'http://localhost:8080';
const REALM = __ENV.REALM || 'p-platform';
const PW    = 'Password123!';
const RATE  = parseInt(__ENV.RATE || '1000');
const ONLY  = __ENV.SCENARIO;

const readLatency  = new Trend('read_latency', true);
const loginLatency = new Trend('login_latency', true);
const readOk       = new Rate('read_success');
const loginOk      = new Rate('login_success');

const allScenarios = {
  read: {
    executor: 'constant-arrival-rate',
    rate: RATE, timeUnit: '1s', duration: '30s',
    preAllocatedVUs: 100, maxVUs: 600,
    exec: 'readScenario', tags: { path: 'read' },
  },
  login: {
    // Deliberately far lower. Keycloak's own sizing is ~15 password logins/sec per vCPU.
    executor: 'constant-arrival-rate',
    rate: Math.max(10, Math.floor(RATE / 20)), timeUnit: '1s', duration: '30s',
    preAllocatedVUs: 50, maxVUs: 300,
    exec: 'loginScenario', startTime: '35s', tags: { path: 'login' },
  },
  register: {
    executor: 'constant-arrival-rate',
    rate: Math.max(5, Math.floor(RATE / 100)), timeUnit: '1s', duration: '20s',
    preAllocatedVUs: 20, maxVUs: 150,
    exec: 'registerScenario', startTime: '70s', tags: { path: 'register' },
  },

  /* Finds the LOGIN CEILING by ramping until it stops keeping up. A fixed rate only tells
   * you pass/fail at that rate; this tells you where the wall is. Watch dropped_iterations
   * and the p95 knee -- the last stage that holds is your real capacity. */
  login_max: {
    executor: 'ramping-arrival-rate',
    startRate: 10, timeUnit: '1s',
    preAllocatedVUs: 100, maxVUs: 800,
    stages: [
      { target: 25,  duration: '20s' },
      { target: 50,  duration: '20s' },
      { target: 100, duration: '20s' },
      { target: 200, duration: '20s' },
      { target: 400, duration: '20s' },
    ],
    exec: 'loginScenario', tags: { path: 'login' },
  },

  /* All at once: N virtual users hammering login simultaneously with no pacing.
   * This is "50 people hit the login button at the same instant", not a smooth rate. */
  login_concurrent: {
    executor: 'constant-vus',
    vus: parseInt(__ENV.VUS || '50'),
    duration: '30s',
    exec: 'loginScenario', tags: { path: 'login' },
  },
};

export const options = {
  scenarios: ONLY ? { [ONLY]: { ...allScenarios[ONLY], startTime: '0s' } } : allScenarios,
  thresholds: {
    'read_latency':            ['p(95)<150'],
    'read_success':            ['rate>0.99'],
    'http_req_failed{path:read}': ['rate<0.01'],
  },
};

/* Pre-register a pool of users and cache their tokens, so the read scenario measures the API
 * and not Keycloak. Tokens are fetched once here, not per iteration. */
export function setup() {
  const pool = [];
  const stamp = Date.now();
  for (let i = 0; i < 20; i++) {
    const email = `load-${stamp}-${i}@example.com`;
    const reg = http.post(`${API}/api/v1/auth/register`,
      JSON.stringify({ email, password: PW }),
      { headers: { 'Content-Type': 'application/json' } });
    if (reg.status !== 201) continue;
    const tok = http.post(`${KC}/realms/${REALM}/protocol/openid-connect/token`,
      { client_id: 'web-app', grant_type: 'password', username: email, password: PW });
    if (tok.status === 200) pool.push({ email, token: tok.json('access_token'), id: reg.json('id') });
  }
  if (pool.length === 0) throw new Error('setup failed: could not register or authenticate any user');
  console.log(`setup: ${pool.length} users ready`);
  return { pool };
}

export function readScenario(data) {
  const u = data.pool[Math.floor(Math.random() * data.pool.length)];
  const res = http.get(`${API}/api/v1/users/me`, {
    headers: { Authorization: `Bearer ${u.token}` }, tags: { path: 'read' },
  });
  readLatency.add(res.timings.duration);
  readOk.add(res.status === 200);
  check(res, { 'read 200': (r) => r.status === 200 });
}

export function loginScenario(data) {
  const u = data.pool[Math.floor(Math.random() * data.pool.length)];
  const res = http.post(`${KC}/realms/${REALM}/protocol/openid-connect/token`,
    { client_id: 'web-app', grant_type: 'password', username: u.email, password: PW },
    { tags: { path: 'login' } });
  loginLatency.add(res.timings.duration);
  loginOk.add(res.status === 200);
  check(res, { 'login 200': (r) => r.status === 200 });
}

export function registerScenario() {
  const email = `reg-${__VU}-${__ITER}-${Date.now()}@example.com`;
  const res = http.post(`${API}/api/v1/auth/register`,
    JSON.stringify({ email, password: PW }),
    { headers: { 'Content-Type': 'application/json' }, tags: { path: 'register' } });
  check(res, { 'register 201': (r) => r.status === 201 });
}

/* Machine-readable summary so scripts/bench.sh can build the scaling table. */
export function handleSummary(data) {
  const out = __ENV.SUMMARY_OUT;
  const m = data.metrics;
  const pick = (name, field) => (m[name] && m[name].values && m[name].values[field] != null)
      ? Number(m[name].values[field].toFixed(2)) : null;
  const result = {
    scenario:      __ENV.SCENARIO || 'all',
    target_rate:   Number(__ENV.RATE || 0),
    achieved_rps:  pick('http_reqs', 'rate'),
    iterations:    pick('iterations', 'count'),
    dropped:       pick('dropped_iterations', 'count') || 0,
    p50_ms:        pick('http_req_duration', 'med'),
    p95_ms:        pick('http_req_duration', 'p(95)'),
    p99_ms:        pick('http_req_duration', 'p(99)'),
    max_ms:        pick('http_req_duration', 'max'),
    failed_rate:   pick('http_req_failed', 'rate'),
  };
  const res = { stdout: '\n' + JSON.stringify(result, null, 2) + '\n' };
  if (out) res[out] = JSON.stringify(result);
  return res;
}

export function teardown(data) {
  console.log(`teardown: ${data.pool.length} pool users left in place; purge with scripts/e2e.sh cleanup or admin hard delete`);
}
