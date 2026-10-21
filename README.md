# AgenticPilot

*Take the lead with your AI.*

Spec-driven web app (Vaadin 25, Spring Boot 4.1) to manage AI agents, skills, data sources and compliance guardrails.

- `docs/SPEC.md` – specification
- `ui-reference/foreman.html` – click-dummy (prototype was named Foreman)
- `deploy/k8s` – Kustomize manifests

## Run locally (M0)

Requires Java 21+ and Maven. The seed password for the demo users is never stored in the repository:

```sh
AGENTICPILOT_SEED_PASSWORD='choose-one' mvn spring-boot:run
```

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

## Build and image

```sh
mvn -Pproduction package     # builds the frontend bundle, jar in target/
docker build -t agenticpilot .
docker run -p 8080:8080 -e AGENTICPILOT_SEED_PASSWORD=... -v ap-data:/data agenticpilot
```

CI (`.github/workflows/ci.yml`) runs the tests, builds the image and pushes it to GitHub Container Registry on pushes.
