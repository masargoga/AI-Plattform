# AgenticPilot

*Take the lead with your AI.*

Spec-driven web app (Vaadin 25, Spring Boot 4.1) to manage AI agents, skills, data sources and compliance guardrails.

- `docs/SPEC.md` – specification
- `ui-reference/foreman.html` – click-dummy (prototype was named Foreman)
- `deploy/k8s` – Kustomize manifests

## Run locally (M0)

Requires Java 21+. Maven does not need to be installed: the repository contains the Maven wrapper (`mvnw` and `mvnw.cmd`), which downloads Maven on first use. If you have Maven installed, `mvn` works the same way. Start the app with:

```sh
./mvnw spring-boot:run          # Linux, macOS, Git Bash
.\mvnw.cmd spring-boot:run      # Windows PowerShell or cmd
```

On the first start a random password for the demo users is generated and shown once on the console (look for the framed "Password:" line). To choose your own instead, set it first: `AGENTICPILOT_SEED_PASSWORD='choose-one' ./mvnw spring-boot:run` (PowerShell: `$env:AGENTICPILOT_SEED_PASSWORD='choose-one'` first). Passwords are never stored in the repository; the generated password is only used by `spring-boot:run`, never in the Docker image or Kubernetes.

Open http://localhost:8080 and sign in as `owner` (Agent owner), `admin` or `admin2` (Admins) with that password.
On first start the demo data from the click-dummy (4 agents, 8 skills, 6 data sources) is loaded; set `AGENTICPILOT_DEMO_DATA=false` to start empty.
The H2 database file lives in `./data` (delete it to reset). Health probes: `/actuator/health/liveness` and `/actuator/health/readiness`.

## Model connection (test chat, skill trial)

The chat needs a Claude API key. Without one the app still runs and the guardrails still work; the chat says that no model is configured.

| Variable | Meaning |
| --- | --- |
| `AGENTICPILOT_MODEL_API_KEY` | Claude API key (a Kubernetes Secret in the cluster). Never logged. |
| `AGENTICPILOT_MODEL_BASE_URL` | Endpoint, default `https://api.anthropic.com` |
| `AGENTICPILOT_MODEL_EU_ALLOWED` | Set to `true` only when that endpoint is approved for EU-only processing. Until then agents set to "EU only" refuse to run (fail closed). |
| `AGENTICPILOT_MODEL_FAST` / `_BALANCED` / `_DEEP` | Model names for the agent setting Fast / Balanced / Most capable |
| `AGENTICPILOT_MODEL_TIMEOUT` | Per call, default `30s` |

Uploads: PDF, Word (`.docx`), text and Markdown, 25 MB per file. Only the extracted text is kept, the original file is not stored.

## REST API and website sources

- **API v1:** `GET /api/v1/agents`, `POST /api/v1/agents/{id}/messages` (JSON or server-sent events with `"stream": true`), `POST /api/v1/guardrails/check` and `GET /api/v1/approvals/{id}`. Create a key on the agent page (shown once, stored hashed, revocable) and send it as `Authorization: Bearer ap_...`. A key reaches only its own agent. The OpenAPI description is served at `/v3/api-docs`. Every call is audited and rate limited per key (`AGENTICPILOT_API_RATE_LIMIT`, default 60 per minute; the counters are per replica). Errors always look like `{"code","message","requestId"}`.
- **Website sources:** an Admin first allows the domain under Data sources (with an empty list nothing is crawled). The crawler stays on one host, respects `robots.txt`, reads at most 50 pages with a 1 second pause, and refuses internal and reserved addresses. `AGENTICPILOT_WEBSITE_MAX_PAGES` and `AGENTICPILOT_WEBSITE_REQUEST_DELAY` tune it. `AGENTICPILOT_WEBSITE_ALLOW_PRIVATE_ADDRESSES` exists for tests only: never set it in a deployment.

## Files and spreadsheets

Data sources accept PDF, Word (.docx), Excel (.xlsx), CSV, text and Markdown. Spreadsheets are read only: each row becomes a searchable line with its column names, so an agent can answer from a price list or a customer table.

## Production

Set `SPRING_PROFILES_ACTIVE=postgres` and the `AGENTICPILOT_DB_*` variables to use PostgreSQL; run two or more replicas with `deploy/k8s/overlays/production`. Users stay signed in when a replica is replaced (shared sign-in tokens in the database), rolling updates lose no data, and metrics are on `/actuator/prometheus` (management port). Everything about operating it is in [docs/RUNBOOK.md](docs/RUNBOOK.md); the security review is in [docs/SECURITY-REVIEW.md](docs/SECURITY-REVIEW.md). Every user can change their password in the user box; do this after the first sign-in.

## Build and image

```sh
mvn -Pproduction package     # builds the frontend bundle, jar in target/
docker build -t agenticpilot .
docker run -p 8080:8080 -e AGENTICPILOT_SEED_PASSWORD=... -v ap-data:/data agenticpilot
```

CI (`.github/workflows/ci.yml`) runs the tests on H2 and on PostgreSQL, renders the Kubernetes manifests, builds the image and pushes it to GitHub Container Registry on pushes.
