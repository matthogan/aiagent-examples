# Agent diagnostics

Every admitted A2A execution emits JSON event payloads to the `agent.diagnostics`
logger at INFO. Spring Boot adds its normal log prefix. Use the server-generated
`X-Request-ID` response header to find the same `request_id` across execution,
model, tool, and HTTP audit events. This is a single-agent execution trace; it
does not create additional agents or save conversation content.

## Diagnosing a request

- `run_started` and `run_configuration`: execution start, configured model alias,
  model mode, and SHA-256 of the effective message templates.
- `model_started` / `model_finished`: one-based round, elapsed milliseconds,
  provider-call outcome, input tokens, and output tokens. A completed call means
  the provider returned; final answer acceptance is reported separately.
- `tool_started` / `tool_finished`: one-based call, allow-listed tool name,
  duration, and whether validated evidence was obtained. Existing `agent.audit`
  `tool_call` events include service and `ok`, `denied`, `invalid_arguments`, or
  `unavailable` outcome, now with duration. `unavailable` groups transport, HTTP,
  and response validation failures without retaining upstream error bodies.
- `run_finished`: exactly one terminal reason, success flag, execution duration,
  attempted model/tool counts, aggregate usage completeness, tokens, and optional
  estimated USD cost. A started call without a finished event identifies the
  operation in flight when the execution deadline expired.
- `response_failed` in `agent.audit`: transport rendering or execution failure
  delivered to the client. A wrapper overflow can occur after a successful graph
  execution; diagnose delivery using this event as well as `run_finished`.

For example, `EVIDENCE_REJECTED` with completed model calls means the model
returned a final proposal which failed evidence validation. `ROUND_LIMIT` means
the model requested tools in its final, answer-only round (fourth by default) (or the round cap was reached). `MODEL_TIMEOUT` is the individual provider-call
limit; `EXECUTION_TIMEOUT` is the enclosing execution deadline. If both limits
could expire, the first observed at the execution boundary wins.

Other reasons are `SUCCESS`, `PARTIAL_EVIDENCE`, `NO_EVIDENCE`, `OUTPUT_LIMIT`,
`TOOL_LIMIT`, `INVALID_MODEL_OUTPUT`, `PROVIDER_ERROR`, `CANCELLED`,
`CAPACITY_REJECTED`, and `INTERNAL_ERROR`. Partial/no evidence are valid bounded
answers that explicitly disclose missing evidence; they are separate from fully
evidenced answers for monitoring. Reason codes are independent of message templates.

## Usage and cost

Token counts come from provider response metadata. Unsupported/missing usage,
scripted responses, failed provider calls without usage, and calls still running
at timeout are unknown, represented by JSON `null`. If any call has incomplete
usage, aggregate totals and cost are null; completed per-call events remain useful.
A run with no model calls has zero tokens and no estimated cost.

Cost is disabled by default. To estimate it, configure **both** prices for the
selected model in USD per million tokens (nonnegative decimal values):

```yaml
agent:
  diagnostics:
    input-usd-per-million: 0
    output-usd-per-million: 0
```

Use zero only when you deliberately want a zero token-price estimate for a local
model. This does not measure electricity or hardware cost. Standard Spring binding
also supports `AGENT_DIAGNOSTICS_INPUTUSDPERMILLION` and
`AGENT_DIAGNOSTICS_OUTPUTUSDPERMILLION`. Estimates use input/output token counts;
there is no cache-tier or other provider-specific billing calculation.

## Collection and privacy

Send these logs to your existing protected collector. Group terminal events by
reason to measure rejection, timeout and failure rates; use durations for latency
percentiles and per-call events to locate slow tools or models. Group partial/no
evidence separately from full success. Do not use request IDs as metric labels.

Request context is passed explicitly through the execution and model workers,
not stored in a global mutable current-run field or an unpropagated thread-local.
Terminal closure is synchronized: late completions cannot modify usage or emit
new diagnostic events for a closed run. A tool's independent audit event can still
arrive later if its underlying I/O ignores cancellation.

Diagnostic events exclude prompts, final answers, tool arguments, source text,
caller identities, credentials, and provider exception messages/causes. Unknown
model-selected tool names are logged as `unknown`. Provider exceptions are replaced
with typed safe failures before the graph library can log them. The configured
model alias and template fingerprint are operator-controlled metadata.

`AGENT_DIAGNOSTICS_LOG_LEVEL=OFF` disables diagnostic event output. Audit logging
is separate. Review third-party DEBUG/wire logging before enabling it: this policy
does not redact arbitrary framework logs. There is no new public metrics endpoint,
OpenTelemetry exporter, or distributed trace propagation. Collection, retention,
alerts and cross-process trace integration remain deployment responsibilities.

## A2A failure contract

Execution failures and rejected final answers return a JSON-RPC `error`, never a
successful agent `Message` containing failure prose. The HTTP status remains 200
for JSON-RPC errors, with the original RPC `id`, code `-32603`, a configured safe
message, and `error.data.reason` / `error.data.requestId`. Clients must check the
JSON-RPC envelope, not HTTP status alone. Admission/capacity rejection retains
HTTP 429; body limits retain HTTP 413. Authentication and parsing rejections happen
before agent execution and remain visible through HTTP audit events.

Successful, partial-evidence and no-evidence answers preserve the existing A2A
Message wire format. The CLI and evaluation string helpers preserve their existing
text API; typed outcomes are exposed by `OperationsGraph.execute` and
`AgentExecutionService.execute`.
