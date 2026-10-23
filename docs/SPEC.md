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
| Approve or reject a rule, skill or agent version | Yes, never own change | No |
| Submit a skill or agent version for review | All | Own (M7) |
| Retire a skill or agent | Yes | No (M7) |
| Turn a rule on or off, delete a rule | Yes | No |
| Change workspace policies | Yes | No |
| Complete records and data subject requests | Yes | No |
| Manage users | Yes | No |

**Dummy login**

- A login page with username and password, built with Vaadin `LoginForm` and Spring Security.
- Users live in the database. A migration seeds three: an Agent owner and two Admins, so the approval flow can be tested.
- Passwords are stored hashed (BCrypt). The seed password comes from configuration, never from source code.
- Every user can change their own password (12 to 72 characters); this signs out their other devices. Five failed sign-ins lock a name for 15 minutes.
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
- Filter by outcome. Export as CSV. Kept indefinitely (decided in M6; the operator archives by policy). No screen can edit or delete an entry.
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

**Theme.** Light and dark follow the system setting until the user picks one with the toggle next to their name; the choice is kept in the browser. The accent is teal. Status colours are separate: green, amber, red. Headings use Bricolage Grotesque, text uses DM Sans and code uses JetBrains Mono. Fonts are bundled with the app and never loaded from an external server, to avoid sending visitor data to third parties.

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

**Added during M5 and M6 (not in the first version of this spec)**

- Sign-in survives a replica loss through persistent remember-me tokens in the database; sign-out and password change delete them.
- Security headers (CSP, frame, referrer, permissions), no error details, secure cookies behind HTTPS, a separate management port for probes and metrics.
- Chat rate limit per user (30 per minute), at most 4 concurrent streams per API key, chunked API bodies refused.
- Background jobs (retention, sync watchdog, token cleanup) take a database lock (`job_lock`). Extra tables: `workspace_policy_version`, `allowed_domain`, `persistent_logins`, `job_lock`.
- Deployment: production overlay (2 to 5 replicas, rolling update, disruption budget, autoscaler, network policy), optional PostgreSQL example, local test setup for Docker Desktop and kind (`deploy/local`), CI jobs for PostgreSQL, manifests and CodeQL, Dependabot. Operating guide in `docs/RUNBOOK.md`, review in `docs/SECURITY-REVIEW.md`.

**Data protection**

- Personal data is masked before the model call. Stored messages hold the masked text.
- Audit entries record the decision, rule and category, not the personal value itself.
- Application logs never contain message text. Each request has an id for tracing.
- The retention job deletes expired conversations daily.
- Fonts and scripts are served by the app itself. No third-party requests from the browser.

**Inputs from outside**

- Uploads are limited by size (25 MB default) and type (PDF, Word, Excel, CSV, text, Markdown). Files are stored in the database in the test setup.
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

Nine milestones, each runnable and deployable on its own. Coding starts with M0 once this spec is approved.

| Milestone | Scope | Done when |
| --- | --- | --- |
| M0 Skeleton | Maven project, main layout and navigation, login with seeded users and roles, H2 with Flyway, Dockerfile, CI, base Kubernetes manifests | `mvn spring-boot:run` starts the app. All seed users can sign in. The image builds in CI and runs in a local cluster. Health endpoint is up |
| M1 Core objects | Agents, skills and data sources as stored data. Wizards, pickers, search, filters, role checks, demo data | Every click-dummy screen for these areas works against H2. Permissions follow the matrix. Data survives a restart |
| M2 Guardrail management | Guardrails tab with check score, workspace policies, custom rules with versions and approval, audit log, records and requests | Four-eyes rules hold in tests. Every state change writes an audit entry. Policy switches adjust agents |
| M3 Runtime and upload | Model provider (Claude), prompt assembly, real test chat, guardrail engine in the chat, file upload and ingestion, keyword retrieval, pending actions, retention job | With an API key, the test chat answers from an uploaded PDF and names the source. The guardrail tests behave as specified. Expired conversations are deleted |
| M4 API and website | REST API with keys, message and check endpoints, approval polling, OpenAPI, rate limits, website connector | An external client gets decisions from the check endpoint. A test site syncs. Private addresses are refused |
| M5 Production readiness | PostgreSQL profile, multi-replica decision (sticky sessions or Kubernetes Kit), security review, runbook | A rolling update loses no data. Two replicas keep users signed in. The runbook is complete |
| M6 Open decisions | Decisions on registry, retention, malware scanning and the first integration; read-only spreadsheets (.xlsx, .csv) as data source files for the Read spreadsheets tool | Spreadsheet uploads are searchable by any cell value and refused when oversized or renamed. The decisions and their consequences are in the runbook and security review |
| M7 Governance | Approval lifecycle with versions for skills and agents, an Approvals screen with diffs, richer data source settings, enforcement labels on guardrails, skill test cases, audit search (see section "M7 specification") | A skill or agent change goes live only after a different Admin approves it. Only approved versions run. Every step is in the audit log |
| M8 Agent runs | The agent loop: an agent with a goal runs until the goal is met, decides which skills to use, reads data through tools the program runs for it, and shows every step (see section "M8 specification") | A user starts an agent with an input such as a customer id and gets a result built from a data source, with every step visible and audited. The run stops at its limits. Guardrails apply to every step |

**Test strategy**

- **Guardrail engine:** table-driven unit tests with edge cases for patterns, word boundaries, masking and order. Target 90 percent line coverage.
- **Services:** Spring Boot tests on H2, including the four-eyes and permission rules.
- **Security:** parameterised tests that call every view and endpoint as each role.
- **API:** contract tests against the OpenAPI description.
- **UI:** smoke tests for the main flows and an accessibility scan, run in CI.
- **Model calls:** tested with a fake provider. Real calls only in a manual check.

## M7 specification: Governance

M7 takes the approval ideas of the custom rules (versions, a note, a different Admin approves) and applies them to skills and agents. It adds the settings and labels that let a reviewer judge a change: data source trust, honest enforcement labels on guardrails, skill test cases and a searchable audit log. The idea comes from an earlier prototype specification for KYC reviews; its domain parts (document matrix, customers) are not taken over.

**Decisions taken for M7**

| Topic | Decision |
| --- | --- |
| Scope of the lifecycle | Skills and agents. Data sources keep settings without approval (changes are audited) |
| Approver | Any Admin who is not the author of the version. No new role |
| Auditor role | Not added |
| Existing content | Migrated to approved version 1, so nothing stops working |
| Agent run state | Pause and activate stay immediate and audited. Only the configuration is versioned |

### Lifecycle

- Status of a skill or agent: `DRAFT`, `IN_REVIEW`, `APPROVED`, `RETIRED`. A version of it is `DRAFT`, `PENDING`, `APPROVED`, `REJECTED` or `WITHDRAWN`.
- The live row always equals the newest approved version, as for custom rules. Runtime, API and agent pickers read only the live row.
- **New item:** created as version 0, status `DRAFT`. It cannot be used by an agent, activated or called through the API until its first version is approved. Its author may test it explicitly (see "Draft testing").
- **Edit:** an edit of an approved item creates or updates a draft version; the live version stays active. The editor shows a banner "Draft v3 based on v2". The author chooses Save draft, Submit for review (a change note is required), Discard draft or, while pending, Withdraw.
- **Review:** only one version per item may be pending. A pending version cannot be edited. Another Admin approves, or rejects with a required comment. The author cannot approve (service check and database constraint, as for rules).
- **Retire:** Admin only, immediate, audited. Blocked while an approved agent uses the skill, or while an API key of the agent is active. A retired item can be restored as a new draft.
- Pause and activate of an agent are not versioned: they take effect at once and are audited.
- Deleting (the open point "Delete") is allowed only for items with no approved version, or retired items; it follows the existing integrity rule.

**What is versioned**

| Item | Fields in a version |
| --- | --- |
| Skill | name, type, description, trigger text, instructions, tools, output format, compliance-relevant flag |
| Agent | name, job, instructions, autonomy, model tier, skills, data sources (the kind cannot be changed) |

Not versioned: an agent's guardrail controls (they are bounded by workspace policy, change at once and are audited), the owner, the run state and counters, test cases (see below).

An agent references skills, not skill versions. When a skill gets a new approved version, agents use it from then on. A skill that is not approved (draft, retired) is skipped by the runtime and shown in the agent as "not available".

### Approvals screen

- A new tab Approvals under Policies (`/policies/approvals`) lists everything in review: rules, skills and agents, with author, date and note. The open-items count in the navigation includes them.
- The detail view shows the change as old value to new value per field. For skills and data sources of an agent it lists what was added and removed. It also shows the author's note, the warnings (below) and the latest test case results of a skill.
- Actions: approve, reject with required comment. The author sees Withdraw instead. The approver never sees an approve button for their own change.
- Existing rule reviews move into the same screen; the rule sheets keep working.
- Each skill and agent page has a History tab: version, author, date, approver, note, status; choose two versions to see the diff.

### Draft testing

The live version runs everywhere, with one exception: on the page of a skill or agent, its author or an Admin can switch the test chat or Try it to "Draft" explicitly. The panel then shows a visible banner and the audit entry names the draft version. The API never runs drafts.

### Data source settings

New fields on a data source, editable by its owner and Admins, changes audited, no approval:

| Field | Meaning |
| --- | --- |
| Owner | Person or team responsible (text) |
| Trust level | `AUTHORITATIVE` or `INFORMATIONAL` (default Informational) |
| On failure | `USE_LAST_COPY` (default, today's behaviour) or `STOP_AND_REPORT`: when the source is in Error or older than its maximum age, retrieval skips it and the answer states that the source is unavailable |
| Maximum age | Days before a copy counts as stale (default 5, as today) |
| Review by | Optional date; a warning shows after it |
| Version info | Free text, for example the document version and the date it applied |

**Warning rule.** An agent with a skill marked compliance-relevant whose attached sources are all Informational (or has none) shows a warning in its Configure tab and in the review of any version that adds the skill or removes a source. It is a warning, not a block.

### Guardrail enforcement labels

Every built-in control and every custom rule shows how it is enforced, and a lock where a workspace policy fixes it:

| Label | Meaning | Shown on |
| --- | --- | --- |
| Platform | The platform enforces it in code; the model cannot bypass it | Personal data, special categories, location (EU only), record limit, approval actions, custom rules |
| Model | Only an instruction in the prompt; the model may ignore it | Purpose |
| Recorded only | Stored and audited, but not enforced yet | Human review for decisions about people (until it is built) |

Hidden-instruction protection and retention are labelled Platform (filter and scheduled deletion). The labels come from message bundles and one table in code, so they cannot drift from the controls. The guardrail check score does not change.

### Skill test cases

- A skill has test cases: name, sample input, and up to three checks: answer contains, answer does not contain, answer matches a pattern (same time-bounded matching as rules). Exact text match is deliberately not offered, because model answers vary.
- Run all runs each case with the model (the skill's Try it path, live or draft) and shows pass or fail per check. Without a model connection, cases show "not run".
- Submitting a skill version stores the latest result summary with it; the Approvals screen shows it and the approver can re-run. A failing or missing result does not block approval; the reviewer decides.
- Test cases are not versioned and belong to the skill; edits are audited.

### Audit log search

Search by text (event, agent, actor), filter by agent, by outcome (as today) and by date range. The CSV export applies the same filters. Lifecycle events (submit, approve, reject, withdraw, retire, draft test) are written with the item, the version and the actor.

### Data model changes (migration V7)

- `skill` and `agent`: `lifecycle_status`, `live_version` (number); `skill`: `compliance_relevant`.
- New `skill_version` and `agent_version`: item, version number, snapshot (JSON), author, note, status, approver, decided at, decision comment. Constraints as for `rule_version`: one pending per item, approver differs from author.
- New `skill_test_case` (skill, name, input, checks as JSON, created by) and `skill_test_result` (version, case, outcome, run at).
- `data_source`: `owner`, `trust_level`, `on_failure`, `max_age_days`, `review_by`, `version_info`.
- Migration of existing rows: each skill and agent gets an approved version 1 whose snapshot is its current content, author the creator, note "Migrated", and `lifecycle_status = APPROVED`.

### Integrity rules

- The live fields of a skill or agent equal its newest approved version. Only one version per item may be pending.
- A version's approver differs from its author (service check and database constraint).
- Skill and agent versions are never updated after approval or rejection and never deleted through the application.
- An approved agent never references a skill that is not approved; retiring blocks as described above.

### Tests

- Four-eyes for skills and agents: the author cannot approve, a second Admin can; the database constraint holds.
- Only approved versions run: a draft or retired item is skipped by chat and API; draft testing needs the explicit switch and is audited.
- Edit creates a draft and leaves the live version unchanged; approve switches the live fields at once; reject and withdraw leave them untouched.
- Retire is blocked while used; delete rules; migration gives every existing item an approved version 1.
- Diff output for changed fields and for added and removed skills and sources.
- Data source: warning rule, stop-on-failure behaviour in retrieval, review-by warning.
- Test case checks (contains, does not contain, pattern) with a fake model; the Approvals screen shows stored results.
- Audit search and CSV export with filters.
- The security matrix covers the new services (anonymous and API key are refused) and the permission matrix per role.

### Done when

A skill or agent change goes live only after a different Admin approves it, and the author cannot approve it. Chat and API run only approved versions. A reviewer sees every changed field and the test results before deciding. Existing data still works after the migration. Each step above is in the audit log, and the guardrail screen says honestly which controls are enforced by the platform.

### Built in M7

Everything above is implemented. Differences and details worth knowing:

- The lifecycle is shared code (`governance` package: `GovernedService`, `ItemVersion`, `ApprovalService`); skills and agents extend it. Content that existed before M7 gets an approved version 1 at start (`ApprovedBaseline`).
- Class-level security annotations are read from the class that declares a method, so the shared base class carries its own `@PreAuthorize` and `@Transactional`. The security matrix test now checks inherited methods too.
- A data source "Settings" sheet holds the new fields; the warning rule shows on the agent page and in the review.
- Skill test cases are limited to 10 per skill and 3 checks per case. Results are stored per version number.
- Audit search treats `%` and `_` in the search text as plain characters.
- Test cases without a model connection show "Not run".

### Not in M7 (later)

Agent teams and handovers, triggers and scheduled runs, run logs with typed entries, agent limits for cost and runtime, skill inputs and output schemas, skills declaring the data sources they need, an Auditor role, skill export as files, and skills calling skills.

### Open questions for M7

- [x] Agent guardrail controls are not versioned (see above). Should loosening a control below the recommended value need approval? **Decided: No; they change at once and are audited.**
- [x] A new agent cannot be activated before its first approval. Is that acceptable for quick experiments, or should owners be able to run their own drafts in the test chat only (as proposed)? **Decided: As proposed; owners test their draft in the test chat, the API runs approved agents only.**
- [x] Should rejecting require the author to start a new draft, or may they continue editing the rejected version? **Decided: A rejected or withdrawn version stays as history; the author continues in a new draft that starts from it.**

## M8 specification: Agent runs

Until M7 an agent is a chat assistant: one prompt, one answer. M8 makes it an agent in the full sense. The program is a loop around the model. The model only reads and writes text; the program builds the prompt, calls the model, reads the answer, checks any request the model makes, does the work itself and feeds the result back. The loop runs until the goal is met or a limit stops it.

**What the user sees.** A skill describes how to extract and work on data from a data source, in words. An agent has a goal and a set of skills. The user starts the agent with an input (for example a customer id). The agent decides which skills to use and when, works through the data and ends with a result. The user can watch every step and stop the run.

**Decisions taken for M8** (proposals marked "open" are in the questions at the end)

| Topic | Decision |
| --- | --- |
| Where it runs | In the Agents page as a new Run panel. The test chat stays as a quick single answer |
| Who starts a run | The owner or an Admin, on an approved agent. The owner or an Admin may also run the draft, as in the test chat |
| What a run reads | Only the data sources attached to the agent. All access is read-only in M8 |
| Who decides what happens | The model asks, the program decides. A request is checked against permissions and limits before the program does anything |
| Who decides it is done | The model, by calling the built-in `finish` tool with a result; the program may stop it earlier |

### The loop

1. **Build the prompt.** In this order: the agent's goal and instructions, its purpose, the list of its approved skills (name, when to use it), the tool definitions, the run input, then the conversation of the run so far (model answers and tool results).
2. **Call the model** with the tool definitions. The answer is either text or a tool request. A tool request is structured text, not an action.
3. **Check the request.** The program checks that the tool is offered to this agent, that the arguments are valid and inside the agent's permissions (only attached sources, size limits), and that no limit is used up. A refused request goes back to the model as an error result so it can correct itself; repeated refusals stop the run.
4. **Run the tool.** The program does the work itself: it searches, reads or looks up. The result is shortened to a size limit and goes through the guardrails (below), then into the prompt as a tool result.
5. **Repeat from step 2** until the model calls `finish`, or the program stops the run.

**Stop reasons** (stored with the run): Done (the model called `finish` and said the goal is met), Gave up (the model called `finish` and said it cannot meet the goal, with the reason), Step limit, Time limit, Token limit, Stopped by a user, Refused too often, Failed (model or system error), Interrupted (the replica running it was lost).

### Skills in a run

- A skill keeps its current fields (name, what it does, when to use it, instructions, tools, answer format). Its instructions say how to extract and work on data from a data source, for example "find the customer row by id, read the risk class and the last review date, list what is missing".
- The prompt lists each approved skill of the agent with its name and "when to use it". The model chooses a skill by calling the built-in `use_skill` tool with the skill name. The program answers with the skill's full instructions and answer format, which then guide the following steps. Only skills of the agent's live approved configuration can be used (a draft run also uses draft skills).
- A skill's allowed tools decide which data tools the model is offered while that skill is in use. The tools offered at any time are the union of the allowed tools of the skills loaded so far, plus `use_skill` and `finish`. A skill with no data tool can only guide the answer.
- A skill does not call another skill. Only the model, through `use_skill`, brings skills into the run.

### Tools in M8

| Tool | What the program does | Needs the skill tool |
| --- | --- | --- |
| `use_skill(name)` | Returns the instructions of that skill | always offered |
| `finish(status, result)` | Ends the run. Status is `done` or `cannot_complete` | always offered |
| `search_documents(source, query)` | Keyword search in one attached source, returns the best passages with document titles | Search documents |
| `read_document(source, title)` | Returns one document of an attached source, shortened to the size limit | Search documents |
| `lookup_rows(source, column, value)` | In an attached spreadsheet source, returns the rows whose column equals the value (for example a customer id) | Read spreadsheets |

Tools that change anything outside (Send email, Create ticket, Browse the web) stay stubs in M8. If a skill lists them, the model is not offered them and the review warns that the skill's tool is not available yet.

### Input

An agent has one run input, with a label the owner chooses (for example "Customer id"; default "Task"). The user types it when starting a run. The input is text of up to 1,000 characters, checked by the guardrails like a chat message, and appears in the prompt as the task to work on. (Open: several named inputs.)

### Guardrails inside the loop

- **Input:** the run input goes through the same input checks as a chat message (personal data, special categories, custom rules, approval actions). A block ends the run before the model is called.
- **Model calls:** personal data is masked in everything that goes to the model, including tool results, according to the agent's setting. A customer row with an email address reaches the model as `[email]` when masking is on. Region and EU-only rules apply to every call; a refusal ends the run.
- **Tool results are data, never instructions.** They are labelled as such in the prompt, filtered by hidden-instruction protection when it is on, and cannot change tool permissions or guardrails.
- **Output:** the `finish` result goes through the output checks (personal data, record limit, notes) before it is shown or stored.
- **Custom rules** that block or need approval stop the run at the step where they match. A "Needs approval" match pauses the run as a pending action; an Admin approves or denies from the audit log or the run page, and the run continues or ends.
- **Autonomy:** in M8 every tool reads only, so the autonomy setting has no effect on a run. It becomes the approval policy for tools that change something once those exist. (Open.)

### Limits

Per agent, with workspace defaults and a hard ceiling: steps per run (default 12, ceiling 30), run time (default 5 minutes, ceiling 15), tokens per run (default 100,000, ceiling 500,000), result size per tool call (default 8,000 characters), refused requests per run (3). An agent cannot raise a limit above the ceiling. At most 3 runs of one agent and 10 in the workspace run at the same time. The limits are enforced by the program, not by the prompt.

### Starting, watching, stopping

- **Start:** the Run panel on the agent page has the input field and a Start button. The run starts in the background and the page shows it live.
- **Watch:** a timeline of steps: model turn, skill chosen, tool request, tool result (shortened), refusal, guardrail decision, final result. Each step shows time, tokens and duration.
- **Stop:** a Stop button ends the run at the next step boundary with the reason "Stopped by a user".
- **Result:** the final result, the stop reason and a summary (steps, tokens, duration, skills and sources used).
- **Past runs:** a list per agent with input, who started it, outcome and time. Opening one shows its timeline.
- **Several replicas:** the replica that starts a run executes it and saves every step. Another replica can show it by reading the database. A watchdog marks a run that has not moved for 5 minutes as Interrupted (under the same database lock as the other jobs).

### API (second part of M8)

`POST /api/v1/agents/{id}/runs` with `{"input": "..."}` answers 202 with a run id. `GET /api/v1/runs/{id}` returns status, steps and result. Same keys, rate limits and error format as the other endpoints. Only approved agents. Errors and stop reasons use fixed codes.

### Data model changes (migration V8)

- `agent`: `goal` (text up to 1,000), `input_label`, and limits (`max_steps`, `max_seconds`, `max_tokens`). The goal and the input label are part of an agent version, the limits are not.
- New `agent_run`: agent, agent version number, started by (user or API key), input (masked), status (RUNNING, WAITING_FOR_APPROVAL, FINISHED), stop reason, result (masked), draft flag, tokens used, started at, ended at, expires at, last step at.
- New `agent_run_step`: run, ordinal, type (MODEL, SKILL, TOOL_CALL, TOOL_RESULT, REFUSED, GUARDRAIL, PENDING, FINISH), tool and arguments (masked), result excerpt (masked, shortened), tokens in and out, duration, skill and rule version involved.
- `pending_action` gets an optional run.
- Retention: a run follows the retention period of its agent. With "Do not store" the run keeps its status, counts and stop reason but no input, step texts or result after it ends. (Open.)

### Audit

Every run writes: started (agent, version, who), each guardrail decision, each refused request, pending actions, the stop reason, and tokens used. Audit entries never contain the input, tool results or the final result, only the decisions and counts, as for chat.

### Provider changes

The model provider must support tool use: tool definitions in the request, tool requests in the answer, and tool results in the conversation. The Claude provider reads tool requests already; the conversation model needs result messages. The fake model used in tests gets scripted answers (a list of tool requests and texts) so loops can be tested without a real model.

### Tests

- **Loop with a scripted model:** plain answer, one tool call then finish, several skills, unknown tool, invalid arguments, a source that is not attached, repeated refusals, finish with `cannot_complete`.
- **Limits:** step, time, token and result size limits stop the run with the right reason; the ceiling cannot be exceeded; concurrency caps.
- **Guardrails:** blocked input never reaches the model; personal data in tool results is masked before the next model call; hidden-instruction text in a result is ignored; a custom rule that needs approval pauses and resumes the run; EU-only refusal ends the run.
- **Skills and tools:** only approved skills; a draft run uses drafts; tools are offered by skill; `lookup_rows` finds the row of a spreadsheet by id and never reads another agent's source.
- **Run lifecycle:** stop by a user, watchdog marks an abandoned run, retention deletes expired runs, "Do not store" keeps no texts, permissions (owner, Admin, others), audit entries without personal data.
- **API:** start, poll, errors, rate limit, unapproved agents.
- **UI:** a browser test starts a run against a fake model and sees the timeline and the result.

### Done when

A user starts an approved agent with a customer id. The agent loads the skill it needs, looks up the customer in an attached spreadsheet, works through the data as the skill instructs, and ends with a result that meets its goal. Every step is visible in the run and written to the audit log without personal data. A run that does not finish stops at its limits with a clear reason. A blocked input, a forbidden source or a prompt injection in the data does not get through.

### Built in M8

Agent loop (`run` package: `RunExecutor`, `RunTools`, `RunPrompt`, `RunLauncher`, `RunService`, `RunMaintenance`), migration V8, tools `use_skill`, `finish`, `search_documents`, `read_document`, `lookup_rows` (all read-only), per-agent limits with ceilings (30 steps, 15 minutes, 500,000 tokens), guardrail checks on input, tool arguments and tool results, approval waiting, stop by user, watchdog for stale runs, retention (with "Do not store" only counts and stop reason remain), run panel in the agent screen (draft or approved version), and the run API (`POST /api/v1/agents/{id}/runs`, `GET /api/v1/runs/{id}`). Autonomy has no effect on runs yet because all tools are read-only. Verified against a mock Claude API; a run against the real API has not been done.

### Not in M8 (later)

Tools that change things (email, tickets, writing records) and the approval flow for them, scheduled and event triggers, agent teams and handovers, memory between runs, live connectors (databases, APIs), vector search, several inputs per agent, using the loop in the test chat.

### Open questions for M8

- [x] **How a skill is carried out.** Proposal (above): the model loads the skill's instructions with `use_skill` and then uses the data tools itself, all inside one loop. Alternative: the skill runs as its own small run with its own model call and hands back a result, which costs more calls but keeps skills self-contained. Which do you want? **Decided: The model loads the skill with `use_skill` and then uses the data tools itself, all inside one loop (no sub-runs).**
- [x] **Bound to a data source?** Proposal: a skill works on whatever sources the agent has attached; it is not bound to one source. Alternative: a skill names the source (or kind of source) it works on and is unavailable without it. **Decided: No; a skill works on the sources the agent has attached.**
- [x] **One input or several?** Proposal: one text input with a label. Alternative: the agent declares named inputs (for example customer id and review type). **Decided: One text input with a label.**
- [x] **Who judges that the goal is met?** Proposal: the model, through `finish`. Alternative: also a second check, a model call or a rule, that tests the result against criteria written in the goal. **Decided: The model, through `finish`. No second check in M8.**
- [x] **Chat and runs.** Proposal: the test chat keeps its single answer; runs are the new mode. Alternative: the chat uses the same loop and tools. **Decided: The test chat keeps its single answer; runs are the new mode.**
- [x] **Runs and retention** with "Do not store": proposal above (counts and stop reason only). Is that acceptable for users who need to see what happened? **Decided: With "Do not store" a run keeps counts and the stop reason only.**
- [x] **Limits.** Are 12 steps, 5 minutes and 100,000 tokens sensible defaults for your first agents? **Decided: Yes: 12 steps, 5 minutes, 100,000 tokens, with the ceilings given above.**
- [x] **API in M8 or later?** Proposal: second part of M8. It can also move to its own milestone. **Decided: Second part of M8.**

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

**Open points (work, not questions)**

- [ ] **Tools are not used by the model.** The prompt does not offer tool definitions and tool calls are not executed (section "Prompt assembly"). Search documents works because passages are always retrieved; Read spreadsheets works the same way; the other tools are stubs.
- [ ] **Human review for decisions about people** is stored and shown but nothing enforces it in the runtime.
- [ ] **Data subject requests: Find data** is not built (the screen says it arrives later). It must search all sources and stored conversations for the person.
- [ ] **Delete** for data sources is missing. Agents and skills can be deleted since M7 (only drafts that never went live, and retired items; conversations, keys and links go with an agent, audit entries stay).
- [ ] **Compliance records** are seeded only by demo data (on by default with the `h2` profile, off with `postgres`). A production database starts without the four example records; seed them with a migration or an Admin action.
- [ ] **Browser tests** for key flows (named in the test strategy) do not exist yet.
- [ ] Data model text: not every table has created and updated timestamps (join tables, `audit_entry` with `occurred_at`, `persistent_logins`, `job_lock`). Align the spec or the tables.
- [ ] Add JaCoCo to the Maven build and CI with a minimum coverage threshold. Measured at the end of M6: 51 % of lines overall, about 90 % outside the UI.
- [ ] Write the missing UI tests: smoke tests for the main flows and an accessibility scan, run in CI (the test strategy asks for them; the `ui` packages have 0 % coverage).
- [ ] Raise coverage of `compliance` (48 % lines) and add contract tests against the OpenAPI description.
- [ ] User management screen, single sign-on and multi-factor authentication.
- [ ] Shared rate limits and sign-in lockout across replicas.
- [ ] A rollout test on a real Kubernetes cluster; first runs of the PostgreSQL, CodeQL and manifest CI jobs; an external penetration test.
