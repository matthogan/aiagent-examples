# Java agent security design

This mirrors the [Python example's trust boundaries](../../pyaiagent/docs/security.md).
It is an instructional single-organization service, not a turnkey multi-tenant platform.

## Enforced in code

Final model claims are checked against request-local tool evidence before being
rendered. Invented citations and mismatched health values are rejected; source
text is quoted and attributed rather than treated as instructions. This proves
consistency with source data, not upstream truth. See [evidence.md](evidence.md).

| Boundary | Controls |
| --- | --- |
| Client → agent | Spring Security stateless bearer authentication; discovery protected; only liveness public |
| JWT | Pinned operator-supplied RSA key; RS256 only; issuer, audience, subject and time-claim checks; Spring's 60-second clock tolerance |
| Authorization | `agent:invoke` at HTTP boundary; `status:read` / `runbooks:read` and allowed services at each tool call |
| Model → tools | Explicit LangGraph4j tool loop; internal Spring AI tool execution disabled; fixed tool names; strict JSON arguments capped at 4,096 characters |
| Agent → APIs | Distinct credentials; configured URLs; verified TLS; no redirects or system proxies; only GET |
| Remote data → model | Maximum 8 KiB response; strict JSON, schema and resource-match checks; nonblank sources and bounded runbook steps; tool data treated as untrusted |
| Model output | Assistant role required; text capped at 16,000 characters; tool call identifiers and argument lengths checked before dispatch |
| Resource usage | 16 KiB JSON request, 4,000-character question, configurable concurrent requests (eight by default), bounded worker pool with no work queue |
| Execution | Validated round/fan-out budgets (defaults: four model calls, two tools per round); final round is answer-only; graph iteration cap; configurable execution deadline (45s default) and cooperative cancellation |
| HTTP timing | Configurable connection, total tool-call and total LLM-call deadlines (5s / 5s / 60s in local YAML; production example uses 20s for LLM calls); overall execution deadline also applies; no model retries |
| Logs | Server-generated correlation ID; request status/duration, tool name/resource/outcome; no application prompt/result/token logging |
| Isolation | Fresh graph state; no persisted conversations or background tasks; no write tools or arbitrary URLs |
| Startup | Production refuses demo modes, HTTP service URLs, weak/default/shared downstream credentials |

Spring AI only proposes tool calls. The tool callbacks capture the **verified
Caller**, not values from a model-generated `ToolContext`. The graph serializes
only locally constructed state objects; no Java serialized bytes are accepted
from HTTP or remote tools.

Request cancellation interrupts the worker and checks the deadline before each
node/tool. Underlying network operations also have timeouts; interruption is
cooperative, not an OS-level execution sandbox. Configure ingress deadlines and
resource quotas. Tomcat defaults to a 10-second connection/idle timeout, which is not
a total upload deadline against clients that trickle data continuously.

See [configuration.md](configuration.md) for YAML timeout settings and how the
per-call limits interact with the overall execution deadline.

The no-key mode uses public local demo tokens. The mock servers refuse
`ENVIRONMENT=production`. They are synthetic fixtures, not hardened replacements
for enterprise data services. Each verifies its own credential and only serves
`payments` / `orders` GET requests.

## Enterprise deployment work

- Terminate TLS at a trusted ingress, configure the advertised HTTPS URL and
  restrict direct access to the backend. There is no application-layer TLS listener.
- Apply per-principal global rate limits, quotas, upload deadlines and network
  controls at the gateway. The local semaphore is not a distributed rate limiter.
- Load secrets from a managed secret store. Prefer workload identity or short-lived
  OAuth service tokens to the sample's static tool tokens. Never forward arbitrary
  client bearer tokens to tools. Add token exchange if acting on behalf of users.
- Mount the issuer's public key from trusted deployment configuration. Rotation is
  by restart. Add an issuer-pinned, cached JWKS integration if automatic rotation is
  needed; do not accept token-specified key URLs or algorithms.
- Ensure the issuer controls `scope` and `services` claims. For multi-tenancy, add
  tenant-aware checks and data partitioning in both the agent and each downstream
  API. A signed service grant alone is not a tenant isolation design.
- Restrict egress to approved data and model endpoints. Configured URLs prevent
  model-driven SSRF but do not protect against compromised DNS or configuration.
- Approve provider data handling and redaction before supplying real records.
  Tool results enter model prompts. Review framework/provider logs and optional
  tracing: application audit policy cannot govern every third-party logger.
- Route audit events to a protected sink and add an approved caller identifier,
  distributed trace integration, metrics, alerts and retention policy.
  [Agent diagnostics](observability.md) correlate requests, model rounds, tools and
  terminal reasons without storing content; this is not compliance-grade attribution.
- Pin/scanning policies for dependencies and image digests belong in your build
  pipeline. Maven pins direct dependencies and BOM versions; review updates and
  generate an SBOM as part of the organization's release process.
- Add adversarial/domain evaluations and human approval before enabling write
  actions. Prompt instructions alone do not establish an injection boundary.
- Before adding A2A task retrieval, streaming, callbacks or resumption, implement
  persistent owner/tenant authorization on every operation and callback egress rules.

The agent suggests investigation. It cannot authorize or execute infrastructure changes.
