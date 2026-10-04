# Reading the example

This walkthrough follows one request: **“Why is payments degraded?”** Start with
the demo model so the complete flow works without a provider key. Then switch to
OpenAI mode to see how the same application boundaries support a real model.

## 1. Follow the HTTP request

Open [`A2aController`](../src/main/java/com/example/javaaiagent/api/A2aController.java).
The `send` method bounds the upload, parses the envelope, obtains the verified
caller and delegates execution. Its helpers keep protocol response construction
and execution-error mapping separate from that main flow.

[`JsonRpcRequestParser`](../src/main/java/com/example/javaaiagent/api/JsonRpcRequestParser.java)
checks three distinct layers: JSON syntax, the JSON-RPC envelope, and A2A message
parameters. Keeping these separate explains the different error codes and why
some errors can echo the request id while others return a null id.

[`A2aMessageParser`](../src/main/java/com/example/javaaiagent/api/A2aMessageParser.java)
supports new user text messages only. A task or context id is rejected because
resuming a conversation would require persistent ownership checks. This is an
intentional boundary of the example, not a general A2A limitation.

## 2. Find where identity becomes trustworthy

[`SecurityConfiguration`](../src/main/java/com/example/javaaiagent/security/SecurityConfiguration.java)
constructs the JWT decoder at startup, verifies incoming tokens, and converts
verified claims into an immutable `Caller`. The controller does not accept caller
permissions from the message body. Authentication answers who supplied the token;
the `agent:invoke` authority and tool grants determine what that caller can do.

Notice that decoder construction, required-claim validation and grant extraction
are separate methods. Each represents a different security decision.

## 3. Follow one model/tool round

[`AgentExecutionService`](../src/main/java/com/example/javaaiagent/application/AgentExecutionService.java)
owns the worker pool and request timeout. Its queue has no storage: saturation is
reported promptly instead of allowing an unlimited backlog. `RuntimeSettings`
controls capacity and execution budgets. Caller-bound tools arrive through the
`ToolProvider` interface; `ToolConfiguration` constructs the HTTP implementation
and closes its shared client when the application stops.

[`OperationsGraph`](../src/main/java/com/example/javaaiagent/application/OperationsGraph.java)
creates fresh history, calls the model, validates its proposed output, and routes
either to tools or completion. `modelStep` and `toolStep` return state updates.
`CompletableFuture.completedFuture` adapts those synchronous steps to the graph
API; the execution service supplies the worker thread.

[`SpringAiAgentModel`](../src/main/java/com/example/javaaiagent/model/SpringAiAgentModel.java)
converts between local `Turn` values and provider messages. Tool-call identifiers
must survive this conversion so the model can associate each result with its
original request. Internal Spring AI tool execution is disabled: the graph must
remain in control of authorization and loop limits.

## 4. Inspect the tool boundary

Read `invoke` in
[`RemoteTools`](../src/main/java/com/example/javaaiagent/tools/RemoteTools.java).
Its order is deliberate:

1. Parse the model's arguments and allow only supported service names.
2. Check both the operation scope and the resource grant.
3. Fetch from a configured URL using the tool's own credential.
4. Validate the returned resource and schema before sharing data with the model.
5. Audit the outcome without logging credentials or response bodies.

The advertised tool schema helps the model construct a call. Runtime checks are
still necessary because a model can return arguments that violate that schema.
Likewise, HTTP 200 does not prove that downstream data is safe or well formed.

## 5. Understand the two kinds of time limits

[`BoundedHttp`](../src/main/java/com/example/javaaiagent/http/BoundedHttp.java)
bounds response bytes as they arrive and waits for the entire response within a
deadline. Checking size only after downloading would be too late to bound memory.

[`TimedAgentModel`](../src/main/java/com/example/javaaiagent/model/TimedAgentModel.java)
bounds a complete provider invocation, while the execution service bounds the
whole sequence of model and tool calls. Both request cancellation on timeout;
interruption remains cooperative. See [configuration.md](configuration.md) for
the settings and their interaction.

## 6. Customize content without rebuilding Java code

Prompts, schemas, error payloads and demo data are in a
[message catalog](../src/main/resources/templates/messages.json).
`MessageTemplates` validates the catalog at startup and substitutes values into
JSON nodes. Whole-value placeholders preserve object and array types; Jackson
escapes text. This separates editable content from protocol and authorization
logic. Follow [templates.md](templates.md) to provide external overrides.

## 7. Read the tests alongside the code

The API integration tests demonstrate wire requests and error responses. Security
tests show signed JWTs with valid and invalid claims. Remote-boundary tests show
that invalid input and denied access do not reach HTTP. Timeout tests deliberately
stall operations to demonstrate cancellation. Architecture tests guard the
package dependencies as the example grows.

When extending the example, keep each method focused on a named decision or stage.
Use comments for the reason behind ordering, ownership and trust boundaries;
prefer a descriptive helper name over comments that merely repeat a statement.
