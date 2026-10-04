# Evidence-checked final answers

The Java agent remains a single agent. Its model chooses read-only tools and
proposes a final result; deterministic code checks and renders that result. No
second model or judge is involved.

## Final model contract

After tool calls, the model must return one JSON object, without markdown or prose:

```json
{
  "observations": [
    {"service": "payments", "source": "demo-status:payments", "status": "degraded"}
  ],
  "runbooks": [
    {"service": "payments", "source": "demo-runbook:payments"}
  ]
}
```

Both arrays are required. Each entry must match a successful tool result from this
request. All latest successful tool/service results must appear exactly once;
array order is irrelevant. No additional fields, generated summaries, diagnosis,
or generated actions are accepted. Empty arrays are valid only when there is no
corresponding successful evidence. Malformed JSON, duplicate keys, repeated
entries, wrong services, wrong health values, invented sources, and omissions
produce a safe rejection message rather than exposing the proposed answer.

`SpringAiAgentModel` combines the `final-answer-contract` instruction and the
configured system prompt into one leading system message for compatibility with
chat templates that only accept one. The deterministic demo produces the same JSON
contract and passes through the same checker. Provider-native structured-output
support is not required; application validation is mandatory regardless of model.

## Evidence ownership

`RemoteTools` authorizes the caller, bounds and validates the remote response, and
creates immutable `ToolEvidence`. `EvidenceTool.Outcome` carries it separately
from the model-facing `tool-result` template. `OperationsGraph` records it in
request-local `Turn.Result` state. Provider messages contain presentation data,
not a writable evidence channel. Ordinary callbacks without the evidence contract
can return tool text but cannot support final observations.

`EvidenceAnswer` checks the proposal and renders health from the validated status
enum. Summaries and runbook steps come directly from evidence, with explicit
source attribution and JSON quoting of untrusted strings. The model cannot supply
alternative steps or append unverified prose. A failed or denied tool has no
evidence; its raw error text is never rendered as a fact. Failures are reported
even when other tools succeed. For repeated calls to the same tool/service, the
latest outcome supersedes previous evidence, including when that outcome fails.
No evidence persists between requests.

The assembled answer remains bounded to 16,000 characters. An overflow produces
a safe request to narrow the question; the outer A2A wrapper retains its own
existing size check. Existing graph and execution-limit messages remain trusted
application fallbacks and do not contain model-generated claims.

## Limits and configuration

This establishes consistency with retrieved evidence, not truth of the upstream
service. A compromised API can return misleading data. Retrieved text remains
untrusted and runbook steps require human review. The agent does not infer root
causes or generate unsupported remediation advice. Questions that require those
capabilities receive source observations rather than speculative conclusions.

Templates remain trusted operator configuration: review changes to prompts,
`evidence-*` messages, and the outer `assistant-message` wrapper as code. A template
can change wording; its author is responsible for preserving attribution and not
adding unsupported claims. Changing model-facing templates cannot forge the
separate evidence objects or bypass the application checker.

Tests cover hallucinated health, invented or stale citations, missing evidence,
partial failure, cross-request isolation, invalid output, output bounds and the
real Spring AI adapter against a simulated provider (including a fabricated health
claim). These deterministic checks do not replace real-model quality evaluations.

## Final-round budget

The last model call (fourth by default) receives all prior evidence but no tool callbacks.
The adapter sends `tool_choice: none` and appends the configurable
`final-round-instruction` to the existing system message. It must produce the
same JSON proposal as any earlier final answer; evidence validation is unchanged.
If it still requests tools, the graph ends with `ROUND_LIMIT` before executing
them. There is no additional model call beyond the configured budget and no unconsumed final batch of tool results.
Answers returned earlier still finish immediately.
