# Configuration

The server loads Spring Boot configuration from
[`src/main/resources/application.yaml`](../src/main/resources/application.yaml).
The standalone `client` command loads the same YAML configuration without starting
the server or creating an LLM. Existing environment variable names remain supported.

## Load deployment YAML

The bundled application defaults to local development: `ENVIRONMENT=local`,
`MODEL_MODE=openai`, `LLM_MODEL=qwen3.5-9b`, and
`OPENAI_BASE_URL=http://localhost:6666`. Start the tool-calling local model server
separately. The model name is its API alias, not the weights filename; set
`OPENAI_API_KEY` if its credential differs from the bundled local-development key.
The agent does not silently fall back to a scripted model when the server is absent.

`AUTH_MODE=demo` controls local client authentication independently of model mode.
For a scripted no-LLM run, explicitly select `MODEL_MODE=demo` or
`--agent.model-mode=demo`. Status/runbook tools use the local mock APIs on ports
8081/8082 in either mode.

Application, Spring AI and graph logging default to `INFO`. Opt into debugging
with `AGENT_LOG_LEVEL`, `AI_LOG_LEVEL`, or `GRAPH_LOG_LEVEL`; review SDK logs before
sharing because they may include request data.

Keep deployment overrides outside the JAR. For example, create `config/local.yaml`:

```yaml
agent:
  timeouts:
    connect: 3s
    tool-call: 8s
    llm-call: 30s
    execution: 90s
    client-discovery: 10s
    client-request: 100s
```

From the `javaaiagent` directory, start the server with:

```powershell
java -jar target/javaaiagent-0.1.0.jar --spring.config.additional-location=file:config/local.yaml
```

For the standalone client, select the same file through the environment:

```powershell
$env:SPRING_CONFIG_ADDITIONAL_LOCATION="file:config/local.yaml"
java -jar target/javaaiagent-0.1.0.jar client "Why is payments degraded?"
```

In bash, use `export SPRING_CONFIG_ADDITIONAL_LOCATION=file:config/local.yaml`.
Client arguments are question text, so pass configuration through environment
variables, external YAML, or JVM `-D` properties before `-jar`, rather than client arguments.

External YAML overrides bundled defaults. The server also accepts command-line
properties such as `--agent.timeouts.llm-call=30s`. The bundled YAML and production
template use environment placeholders; if an external YAML file replaces a setting
with a literal, that literal replaces the placeholder too. Keep placeholders in
deployment files where environment-based overrides are wanted.

For production, copy
[`config/production.example.yaml`](../config/production.example.yaml) to a deployment
file, replace the example URLs and key path, and supply `LLM_MODEL`, `OPENAI_API_KEY`,
`STATUS_TOKEN`, and `RUNBOOK_TOKEN` through the environment. The production example
uses a 20-second model-call timeout; bundled local YAML uses 60 seconds. Other
outbound timeout defaults are the same. Never commit deployment secrets.

## OpenAI-compatible endpoint

The model client defaults to `http://localhost:6666` plus `/v1/chat/completions`.
Override it in your deployment YAML:

```yaml
agent:
  model-mode: openai
  openai:
    base-url: http://127.0.0.1:1234
    completions-path: /v1/chat/completions
  llm-model: your-provider-model
  openai-api-key: ${OPENAI_API_KEY}
```

Or set `OPENAI_BASE_URL` and, if needed, `OPENAI_COMPLETIONS_PATH`. Restart the
agent after changes. The base URL is the server root (or gateway prefix), not the
complete chat URL: do not append `/v1` when using the default completions path.
For example, base `https://gateway.example.com/llm` and path `/v1/chat/completions`
produce `https://gateway.example.com/llm/v1/chat/completions`.

The endpoint must implement OpenAI-compatible Chat Completions with tool calling.
Changing the URL does not switch to the Responses API or Azure-specific authentication.
The existing API key is sent as a bearer token to this configured endpoint. Local
mode permits HTTP; production requires HTTPS. Credentials, query strings and
fragments are rejected in the base URL; the completions path cannot specify a host,
query or fragment. A nonblank API key remains required in `openai` mode, including
for local providers (use a nonsecret placeholder if the provider ignores keys).

This project constructs the Spring AI client itself, so use these `agent.openai.*`
settings rather than Spring AI auto-configuration properties.

## Outbound and execution timeouts

All properties below are under `agent.timeouts`. Values are Spring durations:
`250ms`, `5s`, `2m`, `1h`, or ISO-8601 such as `PT1.5S`. A bare number means
milliseconds; explicit units are recommended. Each value must be between **1ms
and 1h**, inclusive. Missing individual properties use the documented defaults;
malformed, zero, negative, or out-of-range values fail binding before calls begin.

| YAML key | Environment variable | Default | What it limits |
| --- | --- | --- | --- |
| `connect` | `HTTP_CONNECT_TIMEOUT` | `5s` | Establishing a new outbound HTTP connection for tools, LLM calls and the client; reused connections do not reconnect |
| `tool-call` | `TOOL_CALL_TIMEOUT` | `5s` | Each status/runbook HTTP request, including consuming the entire response body |
| `llm-call` | `LLM_CALL_TIMEOUT` | `60s` | Each complete provider invocation, including response body consumption and conversion; also applied to the provider HTTP read timeout |
| `execution` | `AGENT_EXECUTION_TIMEOUT` | `45s` | Overall server execution across model rounds and tool calls; controls both the request's worker wait and graph deadline |
| `client-discovery` | `CLIENT_DISCOVERY_TIMEOUT` | `10s` | The standalone client's complete Agent Card discovery request |
| `client-request` | `CLIENT_REQUEST_TIMEOUT` | `60s` | The standalone client's complete A2A `message/send` request |

For example, `$env:LLM_CALL_TIMEOUT="30s"` changes the default provider-call limit
without editing YAML. Restart the process to apply configuration changes.

The table describes bundled YAML. Binding `TimeoutSettings` without YAML uses a
20-second model-call fallback, also used and recorded by the isolated evaluation
suite. Local YAML explicitly selects 60 seconds, subject to the overall deadline.

The first applicable deadline wins. Increasing `llm-call` alone does not increase
the overall `execution` budget. That budget covers all configured model calls (four by default) and
intervening tool calls; it need not allow the worst-case sum. Set `client-request`
longer than `execution` to leave room for HTTP transport and response serialization.
Each discovery request and message request has its own client budget. There is no
automatic ordering constraint between the six settings.

Tool timeouts return the existing safe unavailable-data result. LLM failures and
execution timeouts return the existing safe A2A failure text. Client timeouts fail
the command. Provider calls are not retried. Demo mode performs no LLM network call.

Timeouts cancel pending futures and request interruption. Provider execution uses
a bounded pool with no queue so an unresponsive provider cannot create unlimited
background work. Cancellation is cooperative; an underlying operation that ignores
interruption may retain a worker until it terminates. Graph deadlines are also
checked before model/tool steps. The overall execution budget starts after request
parsing and authentication; it is not a total inbound upload deadline.

## Inbound server connections

These are Tomcat settings, separate from the validated outbound timeout group:

| YAML property | Environment variable | Default |
| --- | --- | --- |
| `server.tomcat.connection-timeout` | `SERVER_CONNECTION_TIMEOUT` | `60s` |
| `server.tomcat.keep-alive-timeout` | `SERVER_KEEP_ALIVE_TIMEOUT` | `60s` |

They control inbound connection/idle waiting, not the agent's execution budget or
a total upload deadline. Apply ingress limits for slow uploads and align proxy
response timeouts with the selected execution budget.

## Message templates

Set `agent.templates.location` (environment: `MESSAGE_TEMPLATES_LOCATION`) to a
`file:` or `classpath:` JSON catalog. The default is `classpath:templates/messages.json`.
An external file overrides selected entries without duplicating the whole catalog.
See [templates.md](templates.md) for the schema, placeholders, validation and examples.

## Other settings

The [README configuration table](../README.md#configuration) lists model, endpoint,
authentication and credential environment variables. Their YAML names are shown
in `application.yaml`. URLs and tokens are validated at startup, with stricter
production requirements. See [security.md](security.md) for the trust boundaries
and deployment responsibilities. The standalone synthetic `mock` commands retain
their environment/command-line interface; they have no outbound calls. They also load the configured message catalog for
their synthetic responses.

## Diagnostics

Agent diagnostic events default to INFO (`AGENT_DIAGNOSTICS_LOG_LEVEL`). Optional
paired `agent.diagnostics.input-usd-per-million` and
`agent.diagnostics.output-usd-per-million` prices enable token-cost estimates.
Neither price is set by default. See [observability.md](observability.md) for the
event schema, missing-usage semantics and A2A error contract.

## Runtime budgets

`agent.runtime` is bound and validated at startup. Invalid values fail startup;
there is no silent clamping. Existing settings and environment names are unchanged.

| Property | Environment variable | Default | Allowed range |
| --- | --- | --- | --- |
| `max-model-rounds` | `AGENT_MAX_MODEL_ROUNDS` | 4 | 2–16 |
| `max-tool-calls-per-round` | `AGENT_MAX_TOOL_CALLS_PER_ROUND` | 2 | 1–8 |
| `max-output-tokens` | `LLM_MAX_OUTPUT_TOKENS` | 1000 | 1–16000 |
| `workers` | `AGENT_WORKERS` | 8 | 1–64 |

The last model round always has tools disabled. The graph iteration ceiling is
derived from the round budget. `workers` sets HTTP admission, request execution,
and provider execution capacity consistently; request and provider pools remain
separate, with no waiting queue. It is per-process capacity, not a global quota.
`max-output-tokens` is sent to the model provider and does not increase the
16,000-character response safety limit. Raising budgets does not extend deadlines.

For example:

```yaml
agent:
  runtime:
    max-model-rounds: 6
    max-tool-calls-per-round: 2
    max-output-tokens: 2048
    workers: 4
```

Safety/protocol bounds (request size, tool argument size, evidence schemas and
allowed service names) remain code-defined. They are not deployment tuning knobs.
The live evaluation harness explicitly uses its fixed baseline defaults, rather
than inheriting production environment overrides, to keep reports comparable.
