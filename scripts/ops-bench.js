/*
 * Measures the four operations a real user performs, each at its own ceiling.
 * Run one at a time so they do not compete for the same 10 cores:
 *
 *   k6 run -e OP=register       scripts/ops-bench.js
 *   k6 run -e OP=login          scripts/ops-bench.js
 *   k6 run -e OP=profileUpdate  scripts/ops-bench.js
 *   k6 run -e OP=profileRead    scripts/ops-bench.js
 *
 * constant-vus with no arrival-rate cap: we want the ceiling, not pass/fail at a target.
 */
import http from 'k6/http';
import { check } from 'k6';

const API   = __ENV.API   || 'http://localhost:7701';
const KC    = __ENV.KC    || 'http://localhost:8080';
const REALM = __ENV.REALM || 'p-platform';
const PW    = 'Password123!';
const OP    = __ENV.OP    || 'profileRead';
const VUS   = parseInt(__ENV.VUS || '100');
const DUR   = __ENV.DUR || '20s';

export const options = {
  scenarios: { [OP]: { executor: 'constant-vus', vus: VUS, duration: DUR, exec: OP } },
};

function tokenFor(email) {
  const r = http.post(`${KC}/realms/${REALM}/protocol/openid-connect/token`,
    { client_id: 'web-app', grant_type: 'password', username: email, password: PW });
  return r.status === 200 ? r.json('access_token') : null;
}

export function setup() {
  // Pre-create a pool so login/profile scenarios measure their own cost, not registration.
  const pool = [];
  const stamp = Date.now();
  for (let i = 0; i < 30; i++) {
    const email = `ops-${stamp}-${i}@example.com`;
    const reg = http.post(`${API}/api/v1/auth/register`,
      JSON.stringify({ email, password: PW }), { headers: { 'Content-Type': 'application/json' } });
    if (reg.status !== 201) continue;
    const t = tokenFor(email);
    if (t) pool.push({ email, token: t });
  }
  if (!pool.length) throw new Error('setup failed: no usable users');
  console.log(`setup: ${pool.length} users ready`);
  return { pool };
}

const pick = (d) => d.pool[Math.floor(Math.random() * d.pool.length)];

export function register() {
  const email = `reg-${__VU}-${__ITER}-${Date.now()}@example.com`;
  const res = http.post(`${API}/api/v1/auth/register`,
    JSON.stringify({ email, password: PW }), { headers: { 'Content-Type': 'application/json' } });
  check(res, { '201': (r) => r.status === 201 });
}

export function login(d) {
  const u = pick(d);
  const res = http.post(`${KC}/realms/${REALM}/protocol/openid-connect/token`,
    { client_id: 'web-app', grant_type: 'password', username: u.email, password: PW });
  check(res, { '200': (r) => r.status === 200 });
}

export function profileUpdate(d) {
  const u = pick(d);
  const res = http.patch(`${API}/api/v1/users/me/profile`,
    JSON.stringify({ headline: `updated ${__ITER}` }),
    { headers: { Authorization: `Bearer ${u.token}`, 'Content-Type': 'application/json' } });
  check(res, { '200': (r) => r.status === 200 });
}

export function profileRead(d) {
  const u = pick(d);
  const res = http.get(`${API}/api/v1/users/me/profile`,
    { headers: { Authorization: `Bearer ${u.token}` } });
  check(res, { '200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  const m = data.metrics;
  const g = (n, f) => (m[n] && m[n].values && m[n].values[f] != null) ? Number(m[n].values[f].toFixed(2)) : null;
  const out = {
    operation: OP, vus: VUS,
    per_second: g('http_reqs', 'rate'),
    p50_ms: g('http_req_duration', 'med'),
    p95_ms: g('http_req_duration', 'p(95)'),
    failed_pct: Number(((g('http_req_failed', 'rate') || 0) * 100).toFixed(2)),
    total: g('http_reqs', 'count'),
  };
  const r = { stdout: `\n${JSON.stringify(out)}\n` };
  if (__ENV.OUT) r[__ENV.OUT] = JSON.stringify(out);
  return r;
}
