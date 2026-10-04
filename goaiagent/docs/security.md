# Go agent security design

The Go implementation follows the same single-organization, read-only design as
the [Python](../../pyaiagent/docs/security.md) and
[Java](../../javaaiagent/docs/security.md) agents. It is a teaching project with
enforced boundaries, not a complete enterprise deployment platform.

## Controls implemented

| Boundary | Enforcement |
| --- | --- |
| HTTP authentication | Bearer headers; constant-time local token comparison or RS256 JWT verification |
| JWT policy | Operator-pinned RSA key (2048+ bits); fixed RS256 algorithm, issuer/audience, required expiry/issued-at/subject; no clock leeway |
| Authorization | `agent:invoke` at ingress; `status:read` / `runbooks:read` plus allowed service before every remote call |
| Model → tools | Fixed tool names, strict argument decoding, payments/orders allowlist; no model-supplied URLs or credentials |
| Outbound HTTP | Fixed operator configuration, separate tool credentials, verified TLS, no redirects, no environment proxies |
| Remote responses | At most 8 KiB read, strict known fields/types, service identity check, bounded source and summary strings |
| Input | 16 KiB request body; 4,000 Unicode-character question; text-only new user messages |
| Execution | Four model calls; two tool calls per round; 12 graph steps; eight concurrent authenticated requests |
| Timeouts | 45-second graph context, 20-second model HTTP timeout, five-second total tool timeout including body |
| HTTP server | Five-second header deadline, ten-second whole-request read deadline, 60-second write deadline, 16 KiB header limit |
| Audit | Structured JSON events with generated request ID, status/duration, tool/resource/outcome; no application token, prompt or result logging |
| Isolation | No cross-request memory, task store, callbacks or write actions; identity stays outside LLM-visible graph state |
| Startup | Production rejects demo modes, non-HTTPS advertised/tool URLs, default/weak/shared tool credentials |

Request cancellation flows through Go contexts to graph nodes, the model and
remote HTTP requests. Admission slots remain held until the handler returns.
Cancellation is cooperative; custom future model/tool implementations must
honor contexts. No program can safely force-stop arbitrary Go code in a goroutine.

Tool text is explicitly labelled untrusted in descriptions and the system
prompt. That instruction is not a prompt-injection guarantee. Security depends
on the narrow read-only surface and verified authorization, not model obedience.
Provider output tokens are requested to be at most 1,000; the graph also bounds
final text. This does not replace process-level memory and CPU quotas.

## Before enterprise deployment

- Run behind TLS ingress and restrict backend access. `AGENT_URL=https://...`
  advertises that ingress; the Go listener itself serves HTTP.
- Enforce distributed per-principal rate limits, cost quotas, request deadlines
  and network policy. The process semaphore is not a distributed rate limiter.
- Inject credentials from a managed secret store and mount the trusted public
  key. Prefer workload identity / short-lived downstream tokens where possible.
  The sample's static tool tokens do not implement OAuth token exchange.
- Provision an issuer that owns `scope` and `services` grants. Add tenant-aware
  checks and data partitioning in both agent and data services before exposing
  multiple tenants. A service-name grant alone is not tenant isolation.
- Rotate the pinned issuer key by redeploy/restart. For automated JWKS rotation,
  use an operator-allowlisted issuer URL, caching and a fixed algorithm policy;
  never honor a key URL supplied in an untrusted token header.
- Restrict egress to approved data and model services. Configured URLs prevent
  model-selected SSRF but cannot prevent a compromised deployment or DNS.
- Approve provider residency, retention and data handling. Authorized tool
  records become model input. Govern framework logs, callbacks and optional
  tracing separately from the application's audit logger.
- Send audit events to a protected sink; add an approved stable caller identifier,
  trace propagation, metrics, alerting and retention controls. Request IDs support
  correlation but are not a full identity-attributed compliance audit.
- Review dependency and image updates, scan artifacts, and pin image digests for
  releases. `go.sum` verifies module content; it is not a vulnerability assessment.
- Add domain-specific and adversarial evaluations. Require action authorization
  and human review if introducing write tools.
- Add persistent owner/tenant checks before implementing A2A task retrieval,
  cancellation, streaming, resumption or push callbacks. Those methods currently
  return an unsupported-method error.

Local demo tokens are public, and the synthetic API commands refuse production
mode. The generated advice never authorizes or executes operational changes.
