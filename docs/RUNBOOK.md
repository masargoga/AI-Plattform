# AgenticPilot runbook

For the people who deploy and operate AgenticPilot. Examples use Kubernetes; the same settings work for plain containers.

## 1. What runs

One stateless application container (port 8080, plus 8081 for probes and metrics when `AGENTICPILOT_MANAGEMENT_PORT` is set) and one PostgreSQL database. All state is in the database: users, agents, rules, conversations, audit log, uploaded file contents, sign-in tokens and job locks. Replicas share nothing else.

| Part | Where the state is | Lost with a replica? |
|---|---|---|
| Data (agents, rules, audit, uploads) | PostgreSQL | No |
| Sign-in | Remember-me token in PostgreSQL plus a cookie | No: any replica accepts the cookie |
| Open dialogs and a half-typed chat message | Server memory of one replica | Yes, on failover the page reloads |
| API rate limit counters | Memory of each replica | Yes (and each replica counts alone) |

## 2. First deployment

1. Create the database and user (`CREATE DATABASE agenticpilot; CREATE USER agenticpilot ...`). The user needs to create tables in its schema: Flyway creates everything on first start.
2. Create the Secret `agenticpilot-secrets` (section 4).
3. Set the host in `deploy/k8s/base/ingress.yaml`, `AGENTICPILOT_DB_URL` in `deploy/k8s/overlays/production/configmap-patch.yaml` and the image tag in the overlay.
4. `kubectl apply -k deploy/k8s/overlays/production`.
5. Wait for both pods to be ready (`kubectl -n agenticpilot rollout status deploy/agenticpilot`).
6. Sign in as `admin` with the seed password, **change the password straight away** (user box, "Change password"), do the same for `owner` and `admin2`.

The seed users (`owner`, `admin`, `admin2`) are created on the first start only, with the password from `AGENTICPILOT_SEED_PASSWORD`. There is no user management screen yet (section 12): add or disable users with SQL (section 10).

## 3. Settings

All settings are environment variables. Defaults are safe for tests, not for production.

| Variable | Default | Meaning |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `h2` | `postgres` for production. It also turns on JSON logs, secure cookies and proxy headers |
| `AGENTICPILOT_DB_URL` | `jdbc:postgresql://localhost:5432/agenticpilot` | JDBC URL (postgres profile) |
| `AGENTICPILOT_DB_USER`, `AGENTICPILOT_DB_PASSWORD` | `agenticpilot`, empty | **Secret** |
| `AGENTICPILOT_DB_POOL_SIZE` | 10 | Connections per replica. Keep `replicas x pool` below the database's `max_connections` |
| `AGENTICPILOT_SEED_PASSWORD` | empty | **Secret.** Password of the seeded users on first start |
| `AGENTICPILOT_MODEL_API_KEY` | empty | **Secret.** Claude API key. Without it the test chat and skill trial say "No model connection" |
| `AGENTICPILOT_MODEL_BASE_URL` | `https://api.anthropic.com` | Model endpoint |
| `AGENTICPILOT_MODEL_EU_ALLOWED` | `false` | Set `true` only if the endpoint is approved for EU-only processing. Otherwise EU-only agents refuse to run |
| `AGENTICPILOT_MODEL_FAST/BALANCED/DEEP`, `_TIMEOUT` | see `application.yml` | Model names and request timeout |
| `AGENTICPILOT_SECURE_COOKIES` | `false`, `true` with postgres | Marks cookies Secure. Needs HTTPS in front |
| `AGENTICPILOT_SESSION_HOURS` | 12 | How long a sign-in lasts without activity |
| `AGENTICPILOT_MANAGEMENT_PORT` | unset | Separate port for `/actuator/health`, `/actuator/prometheus`. Not published by the Service |
| `AGENTICPILOT_API_RATE_LIMIT` | 60 | API calls per key and minute, per replica |
| `agenticpilot.chat.rate-limit-per-minute` | 30 | Test chat messages per user and minute, per replica |
| `AGENTICPILOT_WEBSITE_MAX_PAGES`, `_REQUEST_DELAY` | 50, 1s | Website crawler limits |
| `AGENTICPILOT_API_DOCS_ENABLED` | `true` | `false` hides `/v3/api-docs` |
| `AGENTICPILOT_SHUTDOWN_TIMEOUT` | 30s | Time to finish running requests when stopping |
| `agenticpilot.demo-data` (`AGENTICPILOT_DEMO_DATA`) | `false` | Never in production |

Never set `AGENTICPILOT_WEBSITE_ALLOW_PRIVATE_ADDRESSES` outside tests: it switches off the protection against requests to internal addresses.

## 4. Secrets

Secrets live only in the Kubernetes Secret `agenticpilot-secrets` (or your secret manager), never in the repository, ConfigMap or logs.

Keys: `AGENTICPILOT_SEED_PASSWORD`, `AGENTICPILOT_MODEL_API_KEY`, `AGENTICPILOT_DB_USER`, `AGENTICPILOT_DB_PASSWORD`.

**Rotate the model API key:** update the Secret, then `kubectl -n agenticpilot rollout restart deploy/agenticpilot`.

**Rotate the database password:** change it in PostgreSQL and in the Secret, then restart. For no interruption, create a second database user with the same rights, switch the Secret to it, restart, then remove the old user.

**Agent API keys** are created on the agent page, shown once and stored as a hash. If one leaks, revoke it there; revoking takes effect on the next call.

## 5. Updating without downtime

The production overlay uses `RollingUpdate` with `maxUnavailable: 0`: a new pod must be ready before an old one is stopped. A stopping pod waits 10 s (the ingress stops sending traffic), then finishes running requests for up to 30 s. Users stay signed in because the sign-in is in the database.

```sh
kubectl -n agenticpilot set image deploy/agenticpilot app=ghcr.io/masargoga/ai-plattform:<commit-sha>
kubectl -n agenticpilot rollout status deploy/agenticpilot
```

**Database changes must work for both versions during the update** (expand, then contract):

1. Release N adds columns or tables as nullable or with defaults and writes both old and new shape. Flyway runs on the first new pod that starts; old pods keep working.
2. Release N+1 stops using the old columns.
3. Release N+2 drops them.

Never rename or drop a column in the same release that stops using it. A migration that fails stops that pod from starting; the old pods keep serving.

**Roll back:** `kubectl -n agenticpilot rollout undo deploy/agenticpilot`. This is safe as long as the migration of the failed release only added things (rule above). A migration that cannot be undone needs a database restore (section 6).

## 6. Backup and restore

Back up PostgreSQL. There is nothing else to back up.

```sh
# backup (custom format, compressed)
pg_dump -Fc -h <host> -U agenticpilot agenticpilot > agenticpilot-$(date +%F).dump
# restore into an empty database, with the application stopped
kubectl -n agenticpilot scale deploy/agenticpilot --replicas=0
pg_restore --clean --if-exists --no-owner -h <host> -U agenticpilot -d agenticpilot agenticpilot-<date>.dump
kubectl -n agenticpilot scale deploy/agenticpilot --replicas=2
```

Run the backup at least daily, keep copies outside the cluster, and **test a restore** into a scratch database each quarter. With a managed database use its point-in-time recovery. Backups contain conversations and the audit log: treat them like the database and apply the same retention (deleting a person's data in the live database does not delete it from old backups; let backups expire).

## 7. Scaling

- Replicas: 2 to 5 by the HorizontalPodAutoscaler (CPU 70 %). More replicas need more database connections.
- Sticky sessions: the ingress sets the cookie `AP_ROUTE`, so a browser stays on one replica. Needed for Vaadin's server-side UI state. If the replica is lost the browser reloads on another one and the user is still signed in.
- Rate limits are per replica. With 3 replicas a key can reach about 3 times `AGENTICPILOT_API_RATE_LIMIT`. Lower the value to match if the exact limit matters.
- At most 4 streaming answers run at the same time per API key (further calls get 429 `too_many_streams`).
- Background jobs (retention at 03:15, sign-in token cleanup at 03:17, stale sync watchdog every 5 minutes) run on one replica at a time (table `job_lock`). A lock held by a crashed replica frees itself after its hold time (up to 30 minutes for retention).

## 8. Monitoring

- Probes: `/actuator/health/liveness` and `/actuator/health/readiness` (port 8081 in production, 8080 otherwise).
- Metrics: `/actuator/prometheus` on the management port (JVM, HTTP, Hikari connection pool, Flyway). The pod carries the `prometheus.io/*` annotations.
- Logs: one JSON line per event (ECS format) in the postgres profile. API calls carry `requestId`, which is also returned as `X-Request-Id` and in error bodies. Logs never contain message texts, API keys or passwords.
- Suggested alerts: readiness failing for 5 minutes; `hikaricp_connections_pending > 0` for 5 minutes; HTTP 5xx rate above 1 %; model errors ("Model unavailable" notices in the audit log); `job_lock` for `retention` not updated for 26 hours; certificate expiry.
- The audit log (Policies, Audit log, CSV export) is the record of what happened to messages and rules.

## 9. Sign-in behaviour

- 5 failed sign-ins for one name lock that name for 15 minutes (in memory per replica; a restart clears it). The message does not say whether the name exists.
- A password change signs out all other devices. Sign-out deletes the user's remember-me tokens, so it signs out all devices.
- A stolen remember-me cookie that is used after the real owner refreshed it is detected, and the token series is rejected.
- Idle HTTP session timeout is 30 minutes; the remember-me token then signs the user in again without a login page for up to `AGENTICPILOT_SESSION_HOURS`.

## 10. Operations

Run SQL with `psql` against the production database. Take a backup first.

**Disable a user:** `update app_user set active = false where username = '<name>';` and delete their tokens: `delete from persistent_logins where username = '<name>';`

**Reset a password** (BCrypt hash required; create it with `htpasswd -bnBC 10 "" '<new password>' | tr -d ':\n'`): `update app_user set password_hash = '<hash>' where username = '<name>';` then delete the user's tokens as above. Ask the user to change it after signing in.

**Add a user:** insert into `app_user` (`id` UUID, `username`, `display_name`, `password_hash`, `role` = `ADMIN` or `AGENT_OWNER`, `active` = true, timestamps, `version` = 0).

**Data subject request:** conversations are linked to agents and users; find them in the `conversation` and `message` tables, delete the rows, and note it in the audit log. Conversations expire by themselves after the agent's retention period (the audit log never contains message texts; it is not deleted automatically, and its retention period is still an open question in the spec).

**Stuck source sync:** a sync in "Syncing" for more than 30 minutes is marked failed by the watchdog. The last good content stays in use.

**Clear the sign-in lockout:** restart one pod, or wait 15 minutes.

## 11. Troubleshooting

| Symptom | Check |
|---|---|
| Pod does not become ready | `kubectl logs`: database unreachable (URL, user, password, NetworkPolicy), Flyway migration error |
| `FlywayValidateException` after an update | A migration was edited after release. Never edit released migrations; add a new one |
| Users are asked to sign in again after an update | Browser blocks cookies, or `AGENTICPILOT_SECURE_COOKIES=true` without HTTPS, or the database was restored from before their sign-in |
| Chat says "No model connection" | `AGENTICPILOT_MODEL_API_KEY` missing |
| Chat says "EU-only: no EU endpoint" | The agent is EU-only and `AGENTICPILOT_MODEL_EU_ALLOWED` is not `true`. Deliberate: it fails closed |
| API answers 429 | Rate limit (see `Retry-After`) or too many failed key attempts from one address |
| API answers 401 for a key that worked | The key was revoked, or the agent was deleted |
| Website sync fails | The domain is not on the Admin allow list, robots.txt forbids it, the host resolves to an internal address, or the NetworkPolicy blocks it |
| Push/streaming drops after exactly 60 s | Ingress timeouts; the base ingress sets 3600 s |
| `too many connections` | Lower `AGENTICPILOT_DB_POOL_SIZE` or raise `max_connections` |

## 12. Known limits

- No user management screen and no single sign-on: users are changed with SQL (section 10). Planned.
- Sticky sessions mean a replica loss reloads open pages (unsaved dialog input is lost). The Vaadin Kubernetes Kit would remove this and was not chosen (see the decision in the spec).
- Rate limits and the sign-in lockout are per replica.
- Keyword search only for data sources; uploads are not virus scanned (open question in the spec).
- The audit log is append-only in the application code (there is no update or delete path), but a database administrator can still change it. Ship it to a write-once store if you need stronger proof.
- The Kubernetes manifests were validated by rendering them (`kubectl kustomize`), and the application against PostgreSQL 16 in tests; a full cluster rollout test is part of your acceptance.
