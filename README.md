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
The H2 database file lives in `./data`. Health probes: `/actuator/health/liveness` and `/actuator/health/readiness`.

## Build and image

```sh
mvn -Pproduction package     # builds the frontend bundle, jar in target/
docker build -t agenticpilot .
docker run -p 8080:8080 -e AGENTICPILOT_SEED_PASSWORD=... -v ap-data:/data agenticpilot
```

CI (`.github/workflows/ci.yml`) runs the tests, builds the image and pushes it to GitHub Container Registry on pushes.
