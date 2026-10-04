# Java agent architecture

The project is a single deployable Spring Boot application, organized into
package modules. Maven keeps one executable artifact and one verification command;
ArchUnit tests enforce acyclic package dependencies and keep entry points and
provider adapters out of application logic.

## Source layout

Under `src/main/java/com/example/javaaiagent`:

| Package | Responsibility |
| --- | --- |
| Root | `AgentApplication`: process entry point, Spring scanning and command dispatch |
| `bootstrap` | Compose model and tool implementations and manage their HTTP client lifecycles |
| `config` | Typed settings, validated durations, standalone client YAML loading and input validation |
| `api` | A2A discovery, JSON-RPC envelopes and single-turn message validation |
| `application` | Model and tool-provider interfaces, immutable conversation state, graph and bounded execution service |
| `evidence` | Immutable validated observations and the evidence-bearing tool contract |
| `model` | Deterministic demo implementation, Spring AI provider adapter and bounded LLM execution |
| `tools` | Authorized status/runbook callbacks and remote response validation |
| `concurrent` | Bounded worker execution, cancellation and shutdown shared by the request and LLM pools |
| `http` | Bounded HTTP response handling and strict JSON readers |
| `templates` | Startup-validated text/JSON catalogs and single-pass data substitution |
| `security` | Verified caller identity, authentication, authorization and admission control |
| `cli` | Standalone authenticated A2A client |
| `demo` | Standalone synthetic downstream APIs |

`src/test/java` mirrors the relevant packages. The API integration suite starts
the real Spring server and synthetic APIs; security tests exercise signed JWTs;
application, configuration and remote-boundary tests cover failure cases.
`ArchitectureTest` prevents package cycles and server dependencies on demo commands.

## Request flow and ownership

1. Security establishes an immutable `Caller` and checks `agent:invoke`.
2. The API reads at most 16 KiB and parses exactly one JSON document. Duplicate
   keys, trailing documents, nontext parts and conversation reuse are rejected.
3. `AgentExecutionService` obtains caller-bound tools through `ToolProvider` and
   submits work to a configurable pool (eight workers by default) with no queue.
   It owns cancellation. `ToolConfiguration` owns HTTP tool composition and cleanup.
4. `OperationsGraph` creates fresh history and applies the configured round and
   fan-out budgets (four model calls and two tools per round by default). The last round is reserved
   for a final evidence-checked answer; unexpected tool calls are rejected before I/O.
   Model replies are checked before tool dispatch or returning output.
5. Tools check scopes and service grants before HTTP, then validate bounded
   responses. Errors return safe messages without exposing tokens or remote bodies.
6. Successful tools attach immutable evidence separately from model-facing text.
   `EvidenceAnswer` checks the final JSON proposal against the latest result for
   each tool/service and renders only source-backed observations and runbook steps.
   See [evidence.md](evidence.md) for the contract and trust boundaries.

The model interface deliberately uses Spring AI tool callbacks: this is a Spring
AI example, not a framework-independent domain library. Provider-specific message
conversion stays in `model`. The execution service assembles caller-bound tools;
the graph itself only sees the model interface and callbacks.

## Development conventions

- Run `mvn spotless:apply` to format Java source and tests; `mvn verify spotless:check` checks
  formatting, architecture rules, tests and packaging. Formatting uses pinned
  Google Java Format with four-space AOSP indentation.
- Keep transport parsing in `api`, model conversions in `model`, and tool access
  policy in `tools`. Add tests at the boundary where a new behavior is enforced.
- New tools need fixed schemas, independent service credentials, authorization
  before I/O, response-size/time limits and schema validation.
- Add new model implementations behind `AgentModel` and select them in
  `bootstrap/ModelConfiguration`. The graph owns execution of tool calls.
- Keep settings errors specific to property names and never include secret values.
  Existing environment names and `--agent.*` properties remain the public contract.

See [configuration.md](configuration.md) for timeout settings and
[security.md](security.md) for deployment responsibilities and limits. These
package modules can become separate Maven artifacts if independent releases or
reuse later justify that additional build structure.

## Simplicity decisions

The request worker and LLM worker use the same `BoundedExecutor` implementation,
but separate instances. They have distinct deadlines; sharing one pool would let
requests occupy the workers needed to complete their nested model calls.

`TemplateContract` is the single registry for catalog keys, types and parameters.
The renderer handles only recursive JSON data and single-pass substitutions;
there is no expression language, hot reload, or pluggable template engine.

The existing Spring AI adapter and LangGraph4j flow remain explicit teaching
boundaries. There is no additional provider framework, persistence layer or
multi-module build: none is needed for the current single-turn example.

The `diagnostics` package owns provider-neutral execution outcomes and request-local
event accounting. Context passes explicitly through model workers; `EvidenceAnswer`
returns a typed decision, and A2A maps failed decisions to JSON-RPC errors.
See [observability.md](observability.md) for operational behavior.
