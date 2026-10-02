# Security design and enterprise deployment

## Trust boundaries

1. **Client → agent.** All discovery and protocol endpoints require a bearer
   token and `agent:invoke`. Only `/healthz` is public. Local mode uses a public
   demo token; production mode rejects that authentication mode. JWT verification
   uses a pinned operator-supplied key, not a URL or key supplied in the token.
2. **LLM → tools.** The model chooses tool names and typed arguments. Code checks
   verified scopes and allowed service names on every invocation. Neither the
   prompt nor a tool result can supply identity, credentials, or an HTTP URL.
3. **Agent → data services.** Separate read-only service credentials are used for
   status and runbook APIs. Caller tokens are never forwarded. URLs are supplied
   by the operator. TLS certificate verification remains enabled, redirects are
   disabled and HTTP proxy environment variables are ignored for tool requests.
4. **Data → LLM.** Tool results are untrusted input. Responses are bounded and
   schema-validated; the system prompt asks the model to use them only as data.
   These measures do not prove prompt-injection resistance. Enforcement relies
   on the narrow, read-only tools and permission checks, not on model obedience.

## Included controls

| Control | Behavior |
| --- | --- |
| Authentication | RS256 signature, issuer, audience, expiration and required claims |
| Authorization | `agent:invoke`, per-tool scopes, per-service grants |
| Request limits | 16 KiB JSON body, 10-second body deadline, 4,000-character question |
| Execution limits | Eight concurrent protocol POSTs per worker, four LLM rounds, two calls per round |
| Time limits | 45-second graph deadline, 20-second model timeout, 5-second tool I/O timeout |
| Data limits | 8 KiB tool response, typed schemas, 1,000 requested LLM output tokens |
| Error handling | Upstream exception details never included in agent responses or app audit events |
| Audit | Server-generated request IDs, status/duration, tool/scope/resource/outcome, error class |
| Isolation | No conversation reuse, persisted history, arbitrary URLs, file tools or write actions |
| Secrets | Environment/secret injection; ignored `.env`; no secrets in Agent Card or prompts |
| Supply chain | Checked-in `uv.lock`; CI runs tests and lint against the locked dependencies |

Audit events omit prompts, tool data, credentials and raw JWT claims. Request
correlation links tool calls to HTTP requests. This minimal example does not
provide a complete identity-attributed compliance audit; add an approved stable
or pseudonymous subject identifier, trace propagation and a protected audit sink
for your organization. Review SDK, provider, proxy and tracing configuration too:
the application cannot govern logs emitted by every external component.

## Before an enterprise deployment

- Put the agent behind an authenticated API gateway / ingress with TLS, request
  deadlines, global per-principal rate limits, quotas and network policy. The
  in-process concurrency limit is not a distributed rate limiter or DDoS defense.
  Apply limits at ingress to every route, including discovery and health checks.
- Set `ENVIRONMENT=production`, `MODEL_MODE=openai`, `AUTH_MODE=jwt`, HTTPS
  `AGENT_URL`, `STATUS_URL`, `RUNBOOK_URL`, a trusted issuer/public key, and distinct
  randomly generated tool secrets of at least 32 characters. Startup validates
  these basics. The local synthetic APIs intentionally refuse production mode.
- Use a secret manager and workload identity / short-lived downstream credentials
  where supported. The sample's static service bearer tokens illustrate separate
  identities but do not implement OAuth token exchange or automatic rotation.
- Pin the issuer key through deployment configuration. The example loads it at
  startup; rotate it by deploying/restarting. For automatic key rotation, add a
  cached JWKS resolver with an operator-allowlisted URL and an explicit algorithm
  policy. Never follow untrusted token-header key URLs.
- Restrict egress at the network layer to approved LLM and data-service hosts.
  Configuration-only tool URLs prevent model-driven SSRF; they do not protect
  against a compromised operator configuration, DNS or trusted service.
- Make real data APIs enforce their own service identity permissions. If they
  return tenant or user data, implement tenant-aware authorization at both agent
  and API layers, and token exchange/on-behalf-of authorization where appropriate.
  The supplied `services` claim is a small single-organization example.
- Approve provider data handling, region, retention and redaction. Tool outputs
  become model input. Do not send confidential data just because a tool is read-only.
  Disable or govern optional LangSmith/provider tracing and SDK debug logging.
- Deploy with least-privilege OS identity, read-only filesystem where feasible,
  resource quotas and restricted secret mounts. A container recipe is included;
  termination of TLS belongs at ingress. Configure trusted proxy headers only for
  known proxies, not all sources.
- Add metrics, distributed tracing, dependency/image scanning, alerting, incident
  procedures and domain-specific evaluations (including injection and data leakage).
  Readiness should reflect your actual upstream dependencies; `/healthz` is liveness.
- If adding writes, add explicit policy checks and human approval at the action
  boundary. If adding A2A tasks/history, persist them securely and enforce owner
  and tenant checks on every lookup, stream, cancellation and resumption.

None of these controls makes generated advice authoritative. The assistant
reports evidence and suggests investigation; humans own operational changes.
