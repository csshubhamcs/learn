/**
 * 1,000 users through the whole chain: API Gateway -> ALB -> service -> Postgres.
 *
 *   docker run --rm --network learn_default \
 *     --add-host learnapi.execute-api.localhost.floci.io:<floci-ip> \
 *     -v "$PWD/scripts:/s" -e GW=... -e KC=... grafana/k6 run /s/load-1k.js
 *
 * Every stage gets its own Trend, so the output says WHERE time goes rather than giving one
 * meaningless average. Registration is expected to be far slower than everything else: it
 * calls Keycloak, which hashes the password with argon2 on purpose.
 */
import http from 'k6/http'
import { check, fail } from 'k6'
import { Trend, Counter, Rate } from 'k6/metrics'
import { SharedArray } from 'k6/data'
import exec from 'k6/execution'

// Through the gateway ONE base URL serves both services: the ALB's path rules decide which
// backend each request reaches. Pointed straight at the services -- to measure what the
// gateway and ALB actually cost -- there is no path routing, so each needs its own base.
const GW = __ENV.GW
const TASK_BASE = __ENV.TASK_BASE || __ENV.GW
const KC = __ENV.KC
const REALM = __ENV.REALM || 'p-platform'
const USERS = Number(__ENV.USERS || 1000)
const PW = 'Password123!'
const RUN = __ENV.RUN || `${Date.now()}`

// one Trend per hop of the journey, so the report reads like the flow diagram
const tRegister = new Trend('stage_1_register', true)
const tLogin    = new Trend('stage_2_login', true)
const tCreate   = new Trend('stage_3_create_task', true)
const tList     = new Trend('stage_4_list_tasks', true)
const tRead     = new Trend('stage_5_read_task', true)
const tUpdate   = new Trend('stage_6_update_task', true)
const tProfile  = new Trend('stage_7_read_profile', true)
const tDelete   = new Trend('stage_8_delete_task', true)

const journeysOk = new Counter('journeys_completed')
const journeyOk  = new Rate('journey_success')

export const options = {
  scenarios: {
    journeys: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 50),
      iterations: USERS,
      maxDuration: '15m',
    },
  },
  thresholds: {
    // the gateway must not be dropping anything
    http_req_failed: ['rate<0.01'],
    journey_success: ['rate>0.99'],
  },
}

// Each iteration owns one distinct user. This matters: Keycloak SERIALISES login attempts
// per account, so hammering one account measures a lock, not the system. Twenty concurrent
// logins for one user gave 1 success and 19 invalid_grant; ten different users gave 10/10.
const users = new SharedArray('users', () =>
  Array.from({ length: USERS }, (_, i) => `load-${RUN}-${i}@test.local`),
)

function jsonHeaders(token) {
  const h = { 'Content-Type': 'application/json' }
  if (token) h.Authorization = `Bearer ${token}`
  return h
}

export default function () {
  // exec.scenario.iterationInTest is the GLOBAL iteration number. __ITER is per-VU, so with
  // several VUs every VU would start at 0, pick the same emails, and collide on the unique
  // email constraint -- which looks exactly like the service rejecting valid registrations.
  const email = users[exec.scenario.iterationInTest % users.length]
  let ok = true

  // 1 · register — the ONLY route the gateway lets through without a token
  let r = http.post(`${GW}/api/v1/auth/register`, JSON.stringify({ email, password: PW }), {
    headers: jsonHeaders(), tags: { stage: 'register' },
  })
  tRegister.add(r.timings.duration)
  ok = check(r, { 'register 201': (x) => x.status === 201 }) && ok
  if (r.status !== 201) { journeyOk.add(false); return }

  // 2 · login — straight to Keycloak, never through the gateway
  r = http.post(`${KC}/realms/${REALM}/protocol/openid-connect/token`,
    { client_id: 'web-app', grant_type: 'password', username: email, password: PW },
    { tags: { stage: 'login' } })
  tLogin.add(r.timings.duration)
  ok = check(r, { 'login 200': (x) => x.status === 200 }) && ok
  const token = r.json('access_token')
  if (!token) { journeyOk.add(false); return }

  // 3 · create — gateway verifies the JWT, ALB path rule sends it to task-service
  r = http.post(`${TASK_BASE}/api/v1/tasks`,
    JSON.stringify({ title: `task for ${email}`, status: 'TODO' }),
    { headers: jsonHeaders(token), tags: { stage: 'create' } })
  tCreate.add(r.timings.duration)
  ok = check(r, { 'create 201': (x) => x.status === 201 }) && ok
  const taskId = r.json('id')
  if (!taskId) { journeyOk.add(false); return }

  // 4 · list — paged, and must contain only this user's own task
  r = http.get(`${TASK_BASE}/api/v1/tasks?page=0&size=20`, { headers: jsonHeaders(token), tags: { stage: 'list' } })
  tList.add(r.timings.duration)
  ok = check(r, {
    'list 200': (x) => x.status === 200,
    'list shows exactly this users task': (x) => x.json('totalElements') === 1,
  }) && ok

  // 5 · read one
  r = http.get(`${TASK_BASE}/api/v1/tasks/${taskId}`, { headers: jsonHeaders(token), tags: { stage: 'read' } })
  tRead.add(r.timings.duration)
  ok = check(r, { 'read 200': (x) => x.status === 200 }) && ok

  // 6 · update (PUT = full replacement)
  r = http.put(`${TASK_BASE}/api/v1/tasks/${taskId}`,
    JSON.stringify({ title: `task for ${email}`, status: 'DONE' }),
    { headers: jsonHeaders(token), tags: { stage: 'update' } })
  tUpdate.add(r.timings.duration)
  ok = check(r, { 'update 200': (x) => x.status === 200 && x.json('status') === 'DONE' }) && ok

  // 7 · profile — different service, proving the ALB's default route
  r = http.get(`${GW}/api/v1/users/me/profile`, { headers: jsonHeaders(token), tags: { stage: 'profile' } })
  tProfile.add(r.timings.duration)
  ok = check(r, { 'profile 200': (x) => x.status === 200 }) && ok

  // 8 · delete
  r = http.del(`${TASK_BASE}/api/v1/tasks/${taskId}`, null, { headers: jsonHeaders(token), tags: { stage: 'delete' } })
  tDelete.add(r.timings.duration)
  ok = check(r, { 'delete 204': (x) => x.status === 204 }) && ok

  journeyOk.add(ok)
  if (ok) journeysOk.add(1)
}
