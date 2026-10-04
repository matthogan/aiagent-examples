# Refactoring review

The review focused on the current request flow, model adapter, concurrency,
templates, configuration and tool boundaries. The changes preserve existing YAML
keys, message catalog keys, A2A endpoints and the single-turn workflow.

## Findings addressed

| Finding | Change | Principle |
| --- | --- | --- |
| Template keys, value types and required parameters were maintained in three separate registries | `TemplateContract` defines each contract once; catalog validation and rendering use it | DRY |
| Every render walked the template again to discover already-validated placeholders | Syntax is checked during loading; runtime arguments are checked against the contract | KISS |
| Request and model workers repeated pool construction, timed waits, cancellation and shutdown | `BoundedExecutor` owns those mechanics; callers retain their own pools and exception mapping | DRY |
| The general template renderer could bypass the narrower text/JSON output limits | Every accessor now uses the same contract-based output checks | KISS |
| A valid model answer plus a configured wrapper could exceed the reply limit and escape as HTTP 500 | The controller returns a safe JSON-RPC internal error with the original request id | Correctness |
| Numeric `2.0` was accepted as a JSON-RPC version because the parser coerced it to text | Version validation requires the JSON string `"2.0"` | Correctness |
| Tool definitions repeatedly serialized an unchanged schema during model calls and dispatch | Each caller-bound callback constructs its immutable definition once | KISS |

The worker utility is shared because two existing callers need exactly these
mechanics. It does not add scheduling, retries, queues, metrics or a plugin system.
Separating the request and model pools remains necessary: combining them could
leave requests occupying every worker while waiting for nested model calls.

## Boundaries retained deliberately

- LangGraph4j remains the explicit model/tool orchestration example. Replacing it
  with a loop would reduce lines but remove one of the project's teaching goals.
- Spring AI continues to convert messages and propose tool calls. Authorization,
  permitted resources and actual tool execution remain application decisions.
- Template substitution remains a small data renderer without an expression
  language, includes, hot reload or arbitrary runtime lookup.
- No repository layer, history store, additional Maven modules, generic provider
  framework or new production dependency was introduced.

## Verification and limits

Regression coverage includes worker saturation and shutdown, cancellation,
template output limits through each accessor, oversized reply wrappers, strict
JSON-RPC versions and the existing API/provider integration tests. Architecture
tests continue to check package cycles and dependency direction.

Run `mvn verify spotless:check` on Java 21. The current build keeps formatter
checking separate from `verify`; `mvn spotless:apply` applies formatting.

This is a focused code review and refactor, not a production security certification
or a load test. Provider cancellation remains cooperative, and the documented
deployment requirements in [security.md](security.md) still apply.
