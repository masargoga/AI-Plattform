# Foreman (AI-Plattform) – project context for Claude Code

Spec-driven web app to manage AI agents, skills, data sources and compliance guardrails.
Work from the spec; the click-dummy is the visual reference for every screen.

## Sources of truth
- Spec (Claude Doc): https://claude.ai/artifact/4zJdBG4p21zibRSPFRfsVh  – committed as `docs/SPEC.md`
- Click-dummy: `ui-reference/foreman.html` (live: https://claude.ai/artifact/NzrhVZvzM8duJUsPwYvohF)
- Original design conversation: https://claude.ai/code/session_0111xKcwE3PqRQe21t7VpumP

## Decisions
- Vaadin 25 (Flow, Java), Java 21+, Spring Boot 4.1+, Maven, Spring Security (`VaadinSecurityConfigurer`, `LoginForm`).
- JPA/Hibernate + Flyway; H2 (PostgreSQL mode) for testing, PostgreSQL later via profile.
- Roles: ADMIN, AGENT_OWNER; seeded dummy users (password from config, BCrypt). Shared workspace, no tenants.
- Agents: MANAGED (run by Foreman) and EXTERNAL (guardrail check only). Pluggable `ModelProvider`, Claude first, EU-only fails closed.
- Data sources: connector SPI; file upload + website first. Keyword retrieval first.
- Guardrails: 7-control score, workspace policies, custom rules (Block/Mask/Needs approval/Add note), pure-Java guardrail engine shared by chat and API.
- Four-eyes: rule changes go live only after approval by a different Admin (enforced server-side). Append-only audit log.
- REST API v1: `POST /api/v1/agents/{id}/messages`, `POST /api/v1/guardrails/check`, `GET /api/v1/approvals/{id}`, `GET /api/v1/agents`; per-agent hashed API keys.
- Docker multi-stage, non-root; Kubernetes via Kustomize in `deploy/k8s`; single replica with H2, sticky sessions or Vaadin Kubernetes Kit for multi-replica.
- Package layout: agent, skill, source, guardrail, runtime, audit, compliance, user, api, ui.

## Milestones
M0 Skeleton -> M1 Core objects -> M2 Guardrail management -> M3 Runtime and upload -> M4 API and website -> M5 Production readiness. Start with M0 once the spec is approved.

## Rules
- Secrets only via env vars / Kubernetes Secrets, never in the repo or logs.
- UI wording follows the click-dummy; texts in message bundles (English first, German later).
- Open questions are listed at the end of the spec; ask before deciding them.
