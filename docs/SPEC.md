# AgenticPilot Specification

*Take the lead with your AI.*

Oct 21, 2026

## Purpose and scope

AgenticPilot is a web app where teams define AI agents, give them data sources and skills, and enforce compliance guardrails on them. This spec describes the target product and splits it into runnable milestones (section 11). The click-dummy (`ui-reference/foreman.html`) is the visual reference for every screen.

**Goals**

- Manage agents, skills, data sources and guardrails in one workspace shared by several users.
- Run agents with a pluggable AI model (Claude first) and expose them through a REST API.
- Enforce guardrails for agents AgenticPilot runs and for external agents that call a check API.
- Prove what happened: versioned rules, second-person approval and an audit log.

**Non-goals for the first releases**

- Multi-tenant SaaS, billing and real single sign-on (dummy login only).
- Native mobile apps. The UI is a responsive web app.
- Model fine-tuning and a dedicated vector database cluster.

**Decisions taken**

| Topic | Decision |
| --- | --- |
| UI framework | Vaadin 25 (Flow, all Java), Java 21+, Spring Boot 4.1+ |
| Database | H2 for testing (file or in-memory), PostgreSQL later through a profile |
| Users | Simple dummy login with the roles Admin and Agent owner |
| Agent scope | Manage agents, run them, expose them through a REST API |
| Data sources | Real connectors. File upload and website come first |
| Guardrails | Enforced in the built-in test chat and for external agents through an API |
| AI model | Provider interface, Claude first |
| Delivery | Phased milestones, each one runnable and deployable |
| Packaging | Docker image, deployable to a Kubernetes cluster |
| Repository | Private GitHub repository `masargoga/AI-Plattform` |

**Glossary**

| Term | Meaning |
| --- | --- |
| Agent | A configured AI assistant with a job, instructions, skills, data sources and guardrails |
| Skill | A reusable ability (instruction, workflow or tool) that can be given to many agents |
| Data source | Content an agent may read, such as uploaded files or a website |
| Guardrail | A rule that limits what an agent may see, say or do |
| Policy | A workspace-wide rule that agents can tighten but never loosen |
| Custom rule | A guardrail defined by users: words or a pattern, plus an action |
| Gateway | The API through which agents are run and checked against guardrails |

## Users, roles and permissions

Two roles cover the first releases: Admin and Agent owner. Users sign in with a simple dummy login and all work on the same database of agents, skills, data sources and guardrails.

**Permission matrix**

| Action | Admin | Agent owner |
| --- | --- | --- |
| View agents, skills, sources, policies, audit log | Yes | Yes |
| Create an agent | Yes | Yes (becomes its owner) |
| Edit, pause or activate an agent | All agents | Own agents |
| Change built-in guardrails of an agent | All agents | Own agents, never below workspace policy |
| Create and edit skills and data sources | All | Own (see open questions) |
| Propose a custom rule for selected agents | Yes | Own agents only |
| Apply a custom rule to all agents | Yes | No |
| Approve or reject a rule change | Yes, never own change | No |
| Turn a rule on or off, delete a rule | Yes | No |
| Change workspace policies | Yes | No |
| Complete records and data subject requests | Yes | No |
| Manage users | Yes | No |

**Dummy login**

- A login page with username and password, built with Vaadin `LoginForm` and Spring Security.
- Users live in the database. A migration seeds three: an Agent owner and two Admins, so the approval flow can be tested.
- Passwords are stored hashed (BCrypt). The seed password comes from configuration, never from source code.
- The click-dummy's "View as" switch is a prototype aid. The real app uses the signed-in user.
- Every view declares its allowed roles. Unauthorised access shows a clear message, not an empty page.

**Rule for approvals**

A change to a custom rule goes live only after a different Admin approves it. The author can withdraw their own change. This four-eyes rule is enforced on the server, not only in the UI.

## Functional requirements

Three areas hold the core objects: agents, skills and data sources. Every list has search and filters, and every page works at phone width.

### Agents

- **List:** cards with name, job, up to three skill chips, skill and source counts, guardrail score (n of 7), status and last run. Filter by All, Active, Paused. Search by name and job.
- **Create:** a three-step sheet. Step 1: template (Customer support, Research, Contracts, Blank), name, job. Step 2: data sources. Step 3: skills. A name is required. The creator becomes the owner.
- **Detail page:** editable name, status switch, Save. Tabs: Configure and Guardrails. On wide screens the test chat sits beside them. On phones it is a third tab, Test.
- **Configure:** job in one line, instructions, autonomy (Ask first, Act then report, Fully autonomous), model (Fast, Balanced, Most capable), skills and data sources with add and remove.
- **Test chat:** suggested prompts and guardrail tests. Each reply shows which skill and data source it used.

### Skills

- **Library:** cards with type badge, version, name, description and the agents using it. Search, and filter by Instruction, Workflow, Tool.
- **Create:** from a template (Blank, Summarize anything, Extract details, Review against rules).
- **Editor:** name, type, what it does, when an agent should use it, instructions (one rule or step per line), allowed tools, answer format (Plain text, Bullets, Table, JSON). Saving raises the version number.
- **Try it:** a sample input, a Run button and the result. In the real app it runs the skill with the model.
- **Used by:** the agents that have the skill, each linking to its page.

### Data sources

- **List:** name, kind, location, count with unit, sync status, the agents using it and a Sync button. A source not synced for more than 5 days shows a warning.
- **Connect:** choose a kind, then name and location. All access is read-only.
- **Kinds:** Uploaded files and Website are built first. Shared folder, Wiki or notes, Database and API appear in the picker as planned and are not selectable yet.
- **Sync:** reads the source again and updates its searchable content. States: Syncing, Synced, Error with the reason and the last good sync.
- **Access rule:** an agent can read only the sources attached to it.

## Guardrails and compliance

Guardrails work in layers. What must never fail is enforced by the platform through permissions and hard blocks, not by instructions in a prompt.

### Built-in controls per agent

| Control | Options | Recommended |
| --- | --- | --- |
| Personal data (names, emails, IBANs) | Mask, Block, Allow | Mask |
| Special categories (health, religion) | Block, Needs review, Allow | Block |
| Processing location | EU only, Anywhere | EU only |
| Retention of conversations | Do not store, 30 days, 90 days, 1 year, Unlimited | 90 days |
| Hidden-instruction protection | On, Off | On |
| Actions that need approval | Send email, Issue refunds, Delete or change records, Share files outside the company, Create tickets | At least one |
| Purpose | Free text | Written down |

Three more settings sit beside these: the most records in one answer (10, 50 or 200), human review for decisions about people, and the audit log, which is always on.

**Guardrail check.** Each recommended control that is met adds one to a score of 7. Personal data and special categories count unless set to Allow. Location counts when EU only. Retention counts unless Unlimited. Hidden-instruction protection counts when on. Approvals count when at least one action is selected. Purpose counts when not empty. Missing controls are listed with a Fix button that applies the recommended value; for purpose it focuses the text field. The score is green at 7, amber at 5 or 6, red below 5, and shows on the agent card.

### Workspace policies (Admin only)

- Switches: mask or block personal data, block special-category data, keep data in the EU. The audit log is always on and cannot be switched off.
- A default retention period for new agents.
- When a policy is on, the loosest option disappears from every agent. Agents that break the policy are adjusted, and the Admin sees how many.

### Custom rules

Users define their own rules once and apply them to all agents or to selected ones.

| Field | Meaning |
| --- | --- |
| Name | Short title, up to 48 characters |
| Catches | Words separated by commas, or a pattern between slashes such as `/KD-\d{4,}/`. Case-insensitive, whole words |
| Action | Block, Mask, Needs approval or Add a note |
| Message | Block: what the agent says instead. Needs approval: what the approver sees. Note: text appended to the answer. Mask has none |
| Applies to | All agents (Admin only) or selected agents |
| Sample message | Test text with a Check button that shows what would happen |
| On or off | Admin only |

Actions in detail: **Block** answers with the message and never calls the model. **Mask** replaces each match with `[hidden]` before processing. **Needs approval** pauses the request and creates a pending action. **Add a note** lets the request through and appends the note to the answer.

### Versions and approval

- Every change to a custom rule creates a pending version with a required note. The live version stays active until the change is approved.
- A new rule starts at version 0 and is not live until approved. Adding or removing an agent from a rule is also a change.
- Another Admin approves or rejects. The reviewer sees each changed field as old value to new value. The author can withdraw.
- The history lists version, author, date, approver, note and whether it was approved or rejected.
- Turning a rule on or off and deleting it are immediate, Admin only, and always written to the audit log.

### Audit log

- Append-only. Each entry has time, agent (or Workspace), event text and an outcome: Allowed, Masked, Blocked or Needs approval.
- Filter by outcome. Export as CSV. Kept for 12 months by default. No screen can edit or delete an entry.
- Rule proposals, approvals, rejections, deletions and on/off changes appear here, next to agent events.
- Addition to the click-dummy: Needs approval entries create a pending action that an Admin can approve or deny from the log.

### Compliance records and requests

- **Records:** title, owner, due or updated date and a status (To do, In progress, Done). Seed examples: record of processing activities, impact assessment for the first agent, data processing agreement with the model provider, privacy notice for chat users.
- **Data subject requests:** access and deletion requests with a due date. Under 7 days left shows as urgent. Find data searches all sources and stored conversations for the person and lists the matches. Mark done closes the request.

## Agent runtime and API

AgenticPilot runs agents with a model behind a provider interface and exposes them through a REST API. One guardrail pipeline checks every request, whether it comes from the test chat, an API client or an external agent.

### Agent kinds

- **Managed:** runs inside AgenticPilot with a model, skills and data sources.
- **External:** runs elsewhere and is registered only so its text can be checked against the guardrails. AgenticPilot never calls a model for it.

### Model provider

- A `ModelProvider` interface takes messages and tool definitions and returns text and tool calls. The first implementation calls the Claude API.
- Provider, model names and API key come from configuration. In Kubernetes the key is a Secret.
- The agent's model setting (Fast, Balanced, Most capable) maps to concrete model names in configuration.
- An agent set to EU only runs only when the configuration names an EU-allowed endpoint. Otherwise it refuses to run (fail closed).

### Prompt assembly

The prompt is built in this order: agent instructions, purpose, each assigned skill (name, when to use it, instructions, answer format), retrieved passages from the agent's data sources, then the conversation. Only the tools allowed by the agent's skills are offered to the model.

### Order of checks

1. Special categories, then personal data, then built-in approval actions such as refunds.
2. Custom rules in order of creation.
3. The first Block or Needs approval stops the request. Masks and notes accumulate.
4. After the model answers: personal data, record limit and notes are applied to the output.

### REST API v1

| Endpoint | Purpose |
| --- | --- |
| `POST /api/v1/agents/{id}/messages` | Send a message to a managed agent. Returns the answer, the skills and sources used, and every guardrail decision |
| `POST /api/v1/guardrails/check` | For external agents. Send text and an agent id. Returns allow, mask, block or needs_approval, the transformed text and the matched rules |
| `GET /api/v1/approvals/{id}` | Poll a pending action: pending, approved or denied |
| `GET /api/v1/agents` | List the agents the key may use |

- Each agent has API keys. A key is shown once, stored hashed and sent as a Bearer token. Keys can be revoked.
- Every call is rate limited per key and written to the audit log.
- Errors use one JSON format with a code and a message. An OpenAPI description is generated from the code.
- `POST /api/v1/agents/{id}/messages` also supports streaming answers (server-sent events) when the client asks for it. Guardrail decisions on the input come first; output rules are applied to the stream before text is released.

## Data model

The model has about twenty tables. Ids are UUIDs, enums are stored as strings, and every table has created and updated timestamps. Editable entities carry a version column for optimistic locking. Schema changes are Flyway migrations. H2 runs in PostgreSQL compatibility mode so the later switch stays small.

| Entity | Key fields | Relations |
| --- | --- | --- |
| app_user | username (unique), display name, password hash, role (ADMIN, AGENT_OWNER), active | owns agents, authors changes |
| agent | name, job, instructions, kind (MANAGED, EXTERNAL), status (ACTIVE, PAUSED), autonomy (ASK, ACT, AUTO), model tier (FAST, BALANCED, DEEP), last run, run count | owner: app_user |
| agent_skill | agent, skill | many to many |
| agent_source | agent, data source | many to many |
| skill | name, type (INSTRUCTION, WORKFLOW, TOOL), description, trigger text, instructions, output format, version | created by app_user |
| skill_tool | skill, tool (SEARCH_DOCUMENTS, BROWSE_WEB, SEND_EMAIL, CREATE_TICKET, READ_SHEETS) | many per skill |
| data_source | name, kind, location, config (JSON), status (SYNCED, SYNCING, ERROR), item count, last synced, last error | created by app_user |
| source_document | source, title, URI, content hash | many per source |
| chunk | document, ordinal, text | many per document |
| agent_guardrail | agent, personal data mode, special data mode, region, retention, hidden-instruction protection, human review, max records, purpose | one per agent |
| agent_approval_action | agent, action (EMAIL, REFUND, DELETE, SHARE, TICKET) | many per agent |
| workspace_policy | personal data on, special data on, EU only on, default retention, version, changed by | one live row; each change is kept as a history row (no approval) |
| custom_rule | name, catches, action, message, scope (ALL, SELECTED), on, live version, sample | selected agents in custom_rule_agent |
| rule_version | rule, version number, field snapshot (JSON), author, note, status (PENDING, APPROVED, REJECTED, WITHDRAWN), approver, decided at | many per rule |
| conversation | agent, channel (TEST, API), started by, started at, expires at | many per agent |
| message | conversation, role, text (masked), trace (JSON) | many per conversation |
| api_key | agent, name, prefix, hash, created by, revoked at | many per agent |
| pending_action | agent, conversation, rule (optional), description, status (PENDING, APPROVED, DENIED), decided by | created by Needs approval |
| audit_entry | time, agent (optional), actor, event, outcome (ALLOWED, MASKED, BLOCKED, NEEDS_APPROVAL), rule (optional), request id, details (JSON) | append-only |
| compliance_record | title, owner, due or updated date, status (TODO, IN_PROGRESS, DONE) | none |
| subject_request | kind (ACCESS, DELETION), subject, due date, done at | none |

**Integrity rules**

- A rule's live fields always equal its newest approved version. Only one version per rule may be pending.
- A version's approver differs from its author. A database constraint backs the service check.
- Audit entries and rule versions are never updated or deleted through the application.
- Deleting an agent deletes its conversations, API keys and links. Its audit entries stay, with the agent name kept in the details.

## Architecture

One Spring Boot application serves the Vaadin UI and the REST API. It has one codebase, one database and one container image.

### Stack

| Layer | Choice |
| --- | --- |
| Runtime | Java 21 or later |
| Framework | Spring Boot 4.1 or later, as Vaadin 25 requires |
| UI | Vaadin Flow 25 in Java, Lumo theme customised with the click-dummy design tokens |
| Security | Spring Security with `VaadinSecurityConfigurer`, form login, `@RolesAllowed` on views and services |
| Persistence | Spring Data JPA with Hibernate, Flyway migrations |
| Database | H2 in PostgreSQL mode for testing, PostgreSQL for production later |
| AI access | Spring `RestClient` behind the `ModelProvider` interface |
| Build | Maven, with the Vaadin production profile building the frontend bundle |
| API description | springdoc OpenAPI |
| Tests | JUnit 5, Spring Boot tests, browser tests for key flows |

Spring Boot 4 splits its starters, so exact dependency names are checked when the project is generated.

### Package layout

Packages follow features, not layers: `agent`, `skill`, `source` (with `connector`), `guardrail`, `runtime` (pipeline, provider, retrieval), `audit`, `compliance`, `user`, `api` and `ui` (main layout, shared components, theme). Views call services only. Services own transactions.

### Key components

- **Guardrail engine:** a plain Java component with no UI or database dependency in its core. It takes text and an agent context and returns decisions plus transformed text. The test chat, the message API and the check API all use it, so behaviour is identical everywhere and easy to unit test.
- **Connector SPI:** `SourceConnector` validates a configuration and syncs a source into documents. Built first: file upload and website. The website connector stays on one host, respects robots.txt, limits pages and request rate, and extracts text.
- **Ingestion:** sync jobs run in the background. Documents are parsed (PDF, Word, text), split into chunks of roughly 800 characters with overlap, and stored with their source.
- **Retrieval:** a `Retriever` returns the best chunks from an agent's sources for a question. The first version is keyword search in the database. Vector search can be added behind the same interface.
- **Approvals:** a service creates, lists and decides pending actions and writes each decision to the audit log.
- **Retention job:** a daily job deletes conversations older than the agent's retention period and logs the count.
- **Configuration:** all secrets and environment settings come from environment variables or Kubernetes Secrets, never from the repository.

## User interface

The click-dummy sets look, layout and behaviour. The Vaadin views reproduce its screens and keep its wording.

**Navigation.** On wide screens a left rail holds the brand, Agents, Skills, Data sources and Policies (with a count of open items) and the signed-in user with logout. Below 860 px the rail becomes a top bar and a bottom tab bar with the same four items.

| Screen | Content | Route |
| --- | --- | --- |
| Login | Username, password, error message | `/login` |
| Agents | Cards, filter, search, how-it-fits-together hint, new agent tile | `/agents` |
| Agent | Configure, Guardrails and Test | `/agents/{id}`, `/agents/{id}/guardrails` |
| Skills | Library cards, filter, search | `/skills` |
| Skill | Editor and Try it | `/skills/{id}` |
| Data sources | Rows with status, sync, used by | `/sources` |
| Policies | Rules, Custom rules, Audit log, Records | `/policies/{tab}` |

Sheets: new agent (3 steps), add skills, add data sources, connect source (2 steps), new skill templates, rule editor, rule review. They open as a bottom sheet on phones and a centred dialog from 700 px. Every screen and tab has its own route so links can be shared.

**Responsive.** The agent and skill pages show the editor and the test panel side by side from 1180 px and as tabs below. Tap targets are at least 40 px. Nothing scrolls sideways.

**Theme.** Light and dark follow the system setting. The accent is teal. Status colours are separate: green, amber, red. Headings use Bricolage Grotesque, text uses DM Sans and code uses JetBrains Mono. Fonts are bundled with the app and never loaded from an external server, to avoid sending visitor data to third parties.

**Behaviour rules**

- Saving, approving and similar actions confirm with a short message.
- Empty lists explain what to do next.
- Destructive actions need a second click on the button.
- Keyboard focus is always visible, and every control has a label for screen readers.
- All texts live in message bundles. English is first. German can be added without code changes.

## Security, privacy and operations

The app handles possibly personal data, so privacy rules are part of the design and not added later.

**Access**

- Form login with Spring Security. The dummy login can be replaced by OpenID Connect later without changing views.
- Roles are checked on views and again in services. The four-eyes rule for approvals is a server check.
- API keys belong to one agent, are stored hashed, shown once and revocable.
- Secrets (model API key, database password, seed password) come from environment variables or Kubernetes Secrets and never appear in logs.

**Data protection**

- Personal data is masked before the model call. Stored messages hold the masked text.
- Audit entries record the decision, rule and category, not the personal value itself.
- Application logs never contain message text. Each request has an id for tracing.
- The retention job deletes expired conversations daily.
- Fonts and scripts are served by the app itself. No third-party requests from the browser.

**Inputs from outside**

- Uploads are limited by size (25 MB default) and type (PDF, Word, text, Markdown). Files are stored in the database in the test setup.
- The website connector blocks private and cluster-internal addresses (protection against server-side request forgery), allows only http and https, and sets timeouts and size limits.
- Retrieved text is data, never instructions. With hidden-instruction protection on, instruction-like passages are flagged and cannot change tool permissions or guardrails.

**Operations**

- Spring Boot Actuator provides liveness and readiness probes and Prometheus metrics.
- Logs are structured JSON on standard output.
- Model calls have timeouts (30 seconds default) and a maximum prompt size.
- Rate limits apply per API key and per user.

## Docker and Kubernetes deployment

The app ships as one Docker image. It runs in Kubernetes as a single replica with H2 at first, and scales out after the switch to PostgreSQL.

### Image and build

- A multi-stage Dockerfile. Stage one builds with Maven and the Vaadin production profile (Node.js is needed only here). Stage two is a slim Java 21 runtime image running as a non-root user on port 8080.
- A GitHub Actions workflow runs tests on every push, builds the image, tags it with the commit id and pushes it to a registry. GitHub Container Registry is the proposal.
- Settings come from environment variables: Spring profile (`h2` or `postgres`), database connection, model provider and key, seed password, upload limits.

### Kubernetes objects

Manifests live in the repository under `deploy/k8s` as a Kustomize base plus overlays.

| Object | Purpose |
| --- | --- |
| Deployment | One replica with H2. Liveness and readiness probes from Actuator, resource requests and limits, non-root security context |
| Service | ClusterIP on port 8080 |
| Ingress | TLS, cookie-based sticky sessions, timeouts suited to Vaadin push connections |
| ConfigMap | Non-secret settings |
| Secret | Model API key, database password, seed password |
| PersistentVolumeClaim | H2 database file and uploads in the test setup |
| PostgreSQL | Replaces the volume in the production overlay |
| HorizontalPodAutoscaler | Production overlay (needs PostgreSQL) |

### Scaling path

1. **Test:** one replica, H2 file on a volume. No high availability. Data is lost if the volume is lost.
2. **Production:** PostgreSQL and two or more replicas. Vaadin keeps its UI state in server memory. **Decided (M5): sticky sessions plus a shared sign-in in PostgreSQL** (persistent remember-me tokens). A lost replica reloads open pages but the user stays signed in; the Vaadin Kubernetes Kit was not chosen to avoid a cache cluster. See `docs/RUNBOOK.md`.
3. Background jobs (source sync, retention) run on one replica at a time, guarded by a database lock.

## Milestones and tests

Seven milestones, each runnable and deployable on its own. Coding starts with M0 once this spec is approved.

| Milestone | Scope | Done when |
| --- | --- | --- |
| M0 Skeleton | Maven project, main layout and navigation, login with seeded users and roles, H2 with Flyway, Dockerfile, CI, base Kubernetes manifests | `mvn spring-boot:run` starts the app. All seed users can sign in. The image builds in CI and runs in a local cluster. Health endpoint is up |
| M1 Core objects | Agents, skills and data sources as stored data. Wizards, pickers, search, filters, role checks, demo data | Every click-dummy screen for these areas works against H2. Permissions follow the matrix. Data survives a restart |
| M2 Guardrail management | Guardrails tab with check score, workspace policies, custom rules with versions and approval, audit log, records and requests | Four-eyes rules hold in tests. Every state change writes an audit entry. Policy switches adjust agents |
| M3 Runtime and upload | Model provider (Claude), prompt assembly, real test chat, guardrail engine in the chat, file upload and ingestion, keyword retrieval, pending actions, retention job | With an API key, the test chat answers from an uploaded PDF and names the source. The guardrail tests behave as specified. Expired conversations are deleted |
| M4 API and website | REST API with keys, message and check endpoints, approval polling, OpenAPI, rate limits, website connector | An external client gets decisions from the check endpoint. A test site syncs. Private addresses are refused |
| M5 Production readiness | PostgreSQL profile, multi-replica decision (sticky sessions or Kubernetes Kit), security review, runbook | A rolling update loses no data. Two replicas keep users signed in. The runbook is complete |
| M6 Open decisions | Decisions on registry, retention, malware scanning and the first integration; read-only spreadsheets (.xlsx, .csv) as data source files for the Read spreadsheets tool | Spreadsheet uploads are searchable by any cell value and refused when oversized or renamed. The decisions and their consequences are in the runbook and security review |

**Test strategy**

- **Guardrail engine:** table-driven unit tests with edge cases for patterns, word boundaries, masking and order. Target 90 percent line coverage.
- **Services:** Spring Boot tests on H2, including the four-eyes and permission rules.
- **Security:** parameterised tests that call every view and endpoint as each role.
- **API:** contract tests against the OpenAPI description.
- **UI:** smoke tests for the main flows and an accessibility scan, run in CI.
- **Model calls:** tested with a fake provider. Real calls only in a manual check.

## Assumptions and open questions

**Assumptions**

- One workspace, no separate tenants. All users see the same agents, skills, sources and rules.
- The Claude API key and the model names are supplied by you through configuration.
- The first UI language is English.
- Send email, Create ticket, Browse the web and Read spreadsheets exist as skill tools in the interface. Search documents and Read spreadsheets (uploaded .xlsx and .csv files) are functional; the others are stubs until their integrations are defined.

**Open questions**

- [x] GitHub: owner and name of the private repository, and is GitHub Container Registry fine for images? **Decided: Yes, GitHub Container Registry (`ghcr.io/masargoga/ai-plattform`) (M6).**
- [x] Skills and sources: may Agent owners edit those created by others, or only their own? **Decided: Agent owners edit only their own; Admins edit all.**
- [x] Pending actions from Needs approval are an addition to the click-dummy. Can any Admin approve them? **Decided: Yes, any Admin (M3).**
- [x] Should workspace policies also be versioned and approved like custom rules? **Decided: Yes, versioned (history kept); no approval needed.**
- [x] Should the approval count be configurable, for example two approvers for high-risk rules? **Decided: No, one approver (a different Admin) stays fixed.**
- [x] Which Claude endpoint counts as EU only? Without one, EU-only agents refuse to run. Is that acceptable? **Decided: Yes, fail closed. The operator sets `AGENTICPILOT_MODEL_EU_ALLOWED=true` for an EU-approved endpoint (M3).**
- [x] Does the message API need streaming answers in the first version? **Decided: Yes, streaming answers are required (M4).**
- [x] Is German needed in the first release or later? **Decided: No German translation; English only, message bundles stay.**
- [x] Website connector: should Admins keep an allow list of domains that may be crawled? **Decided: Yes, an Admin-managed domain allow list (M4).**
- [x] Retention defaults: 90 days for conversations and 12 months for the audit log. Do these match your policy? **Decided: conversations 90 days by default (per agent); the audit log is never deleted automatically (M6). The operator archives or prunes it by policy, see the runbook.**
- [x] Are uploaded files scanned for malware, and by which tool? **Decided: No scanner. Strict type, size and structure checks and text-only storage stay; the residual risk is accepted and written in the security review (M6).**
- [x] Which integrations should replace the stub tools first (email, tickets, spreadsheets)? **Decided: Spreadsheets first, read only (.xlsx and .csv uploads), no outside credentials (M6). Email and tickets stay stubs.**
