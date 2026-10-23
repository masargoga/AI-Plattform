# Security review (M5)

Scope: the whole application at the end of M5. Method: review of the code against the threat list below, automated checks (`SecurityMatrixTest`, `SessionAndSecurityTest`, guardrail and API tests), CodeQL and Dependabot in CI. No external penetration test has been done; do one before a public launch.

## Findings and what was done

| # | Area | Finding | Resolution |
|---|---|---|---|
| 1 | Authorization | Role checks sit on the services, not only on views | Every service has `@PreAuthorize`; `SecurityMatrixTest` calls every public method as anonymous and as an API key and expects a refusal |
| 2 | Authorization | Owners must edit only their own skills, sources and agents | Ownership checks in the services (`CurrentUser`), covered by `CoreObjectsServiceTest` |
| 3 | Four-eyes | Rule approval must be by a different Admin | Enforced in the service and by the database check constraint `ck_rule_version_four_eyes`; tested in `GuardrailManagementTest` |
| 4 | Sessions | Sessions in memory lose users on failover | Persistent remember-me tokens in PostgreSQL: rotated on use, deleted at sign-out and password change, reuse detected, HttpOnly, Secure over HTTPS, SameSite Lax, cleaned up daily |
| 5 | Sign-in | Unlimited password guessing | Lock after 5 failures for 15 minutes; BCrypt; same message for unknown name and wrong password |
| 6 | Passwords | All seed users shared one password | "Change password" for every user (12 to 72 characters, audited, signs out other devices) |
| 7 | Headers | No hardening headers | CSP (`frame-ancestors 'self'`, `object-src 'none'`, `base-uri 'self'`, `form-action 'self'`), `nosniff`, `X-Frame-Options: SAMEORIGIN`, `Referrer-Policy: same-origin`, `Permissions-Policy`, `Cache-Control: no-store` for responses |
| 8 | Information leak | Error pages could show messages or stack traces | `server.error.include-*: never`; API errors have one fixed format |
| 9 | Management endpoints | Metrics reachable from the internet | Separate management port, not published by the Service; its own filter chain denies everything except health, info, prometheus |
| 10 | API | Key guessing | 8-character prefix lookup, SHA-256 hash comparison in constant time, failed attempts limited per address, keys limited per minute |
| 11 | API | Resource exhaustion | Body size limit (413), chunked bodies refused (411), 4 concurrent streams per key, per-key rate limit |
| 12 | Chat | Message flooding from a signed-in user | 30 messages per minute and user |
| 13 | SSRF | Website connector could reach internal services | Domain allow list, DNS resolved once and checked (no internal, loopback, link-local, embedded IPv4 forms), redirects re-checked, size/time/page limits; NetworkPolicy blocks internal ranges as a second layer |
| 14 | Regex DoS | Custom rules with catastrophic patterns | Time-bounded matching (`CatchPattern`) |
| 15 | Uploads | Hostile documents | Type and size checks, parsed in memory (PDFBox for PDF), only extracted text is stored. **Accepted (M6 decision): no malware scan.** Files are never opened by a viewer or executed; spreadsheets are read as data only, formulas are not run, macros (.xlsm) and legacy .xls are refused by type. Anyone who downloads originals from elsewhere should scan them there |
| 16 | Secrets | Secrets in config or logs | Environment/Secret only; logs are written without message texts, keys or passwords (by design and code review, not by an automated test); the API key is shown once |
| 17 | Audit | Audit log changeable | Append-only in the application (no update or delete path in the code); database administrators can still change rows (see runbook). **Open:** a database trigger or write-once export |
| 18 | Multi-replica | Duplicate background jobs | `job_lock` table; expired locks are taken over |
| 19 | Supply chain | Outdated libraries | Dependabot (Maven, Actions, Docker) and CodeQL |
| 20 | Container | Privileges | Non-root user 10001, no privilege escalation, all capabilities dropped, read-only root file system (production), seccomp RuntimeDefault, NetworkPolicy |

## M7 additions

| # | Area | Finding | Resolution |
|---|---|---|---|
| 21 | Authorization | The shared lifecycle base class had no method security: Spring reads class-level `@PreAuthorize` and `@Transactional` from the class that declares the method, so inherited methods were open and not transactional | Found by `SecurityMatrixTest` (now also checks inherited methods). The base class carries both annotations |
| 22 | Four-eyes | Skills and agents change the behaviour of the runtime | Versions with approval by a different Admin, service check plus database constraint, only approved versions run, drafts are tested only by owner or Admin and the audit log says so |
| 23 | API | A draft or retired agent could answer through its key | The API refuses non-approved agents (409 `agent_not_approved`) and lists none |
| 24 | Data quality | An outdated or failed source gave confident answers | Per source "stop and report" leaves it out and the model is told to say so |
| 25 | Transparency | Guardrails looked all equally strong | Each control is labelled Platform, Model or Recorded only; human review is "Recorded only" until built |

## M8 additions

| # | Area | Finding | Resolution |
|---|---|---|---|
| 26 | Agent loop | Runaway loops and cost | Step, time and token limits with ceilings, concurrent-run caps, stop button, watchdog |
| 27 | Prompt injection | Data returned by tools may contain instructions | Tool results are checked and masked by the guardrail engine and marked as data in the prompt; tools are read-only and limited to the agent's own sources and skills |
| 28 | Privacy | Run input may hold personal data | Raw input stays in memory; the database stores the masked input; "Do not store" keeps counts only; audit entries carry no text |
| 29 | Access | Runs start only for approved agents (drafts for owner or Admin) | `RunService` is method-secured; API keys are per agent |

## Accepted risks and follow-ups

- Audit log is kept forever (M6 decision), so it grows; see the runbook for archiving.

- Rate limits and sign-in lockout are per replica (documented). A shared counter in PostgreSQL or Redis would make them exact.
- CSP does not restrict scripts or styles: Vaadin needs inline styles and its own scripts. A nonce-based policy needs a framework change.
- Remember-me cookie is the only credential that survives a replica; stealing it equals stealing the session. Mitigated by HTTPS-only, HttpOnly, rotation and theft detection.
- `removeUserTokens` at sign-out signs the user out on all devices (Spring Security behaviour). Accepted.
- No user management screen, no single sign-on, no multi-factor authentication. Recommended before real customer data is processed.
- The model provider receives message texts (that is the product). Choose the endpoint according to your data policy; EU-only fails closed.
