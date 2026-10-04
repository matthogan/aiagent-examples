# Configurable prompts and message templates

Application-owned prompts, message text, tool schemas and JSON payloads live in
[`src/main/resources/templates/messages.json`](../src/main/resources/templates/messages.json).
Java code selects a named template and supplies its data. The catalog is loaded
once at startup; changing an external file requires a process restart, not a rebuild.

## Select an override file

Set the location in application or deployment YAML:

```yaml
agent:
  templates:
    location: file:config/messages.example.json
```

Alternatively use the bundled environment placeholder:

```powershell
$env:MESSAGE_TEMPLATES_LOCATION="file:config/messages.example.json"
java -jar target/javaaiagent-0.1.0.jar
```

In bash, use `export MESSAGE_TEMPLATES_LOCATION=file:config/messages.example.json`.
The server also accepts `--agent.templates.location=file:config/messages.example.json`.
Relative file paths are resolved against the process working directory. Run these
examples from `javaaiagent`, or use an absolute `file:` URI.

Only `classpath:` and `file:` resources are supported. The default is
`classpath:templates/messages.json`. The standalone client and mock commands load
the same setting through Spring's YAML/environment configuration loader. Their
template catalog is independent of the running server's catalog; configure each
process that should use the override.

An external file is a **partial override catalog**. Each supplied top-level key
replaces that entire template; omitted keys retain bundled defaults. Unknown keys
are rejected to catch spelling mistakes. See the ready-to-edit
[`config/messages.example.json`](../config/messages.example.json) for an example.

## Text and JSON templates

Text templates are JSON strings. JSON payload templates are objects or arrays of
data, with placeholders in string values. This example preserves a downstream
object as a nested JSON object:

```json
{
  "user-message": "Operator question: {{text}}",
  "tool-result": {
    "kind": "service-evidence",
    "data": "{{data}}"
  }
}
```

A placeholder occupying the entire value, such as `"{{data}}"`, keeps the supplied
JSON type: object, array, number, boolean or string. A placeholder embedded in
text, such as `"Operator question: {{text}}"`, inserts text. Jackson serializes the
result, escaping quotes, backslashes and control characters. Do not assemble JSON
with string concatenation or add an extra layer of JSON escaping around placeholders.

Substitution is a single pass. If an operator's question itself contains
`{{data}}`, that text remains data and is not evaluated again. The renderer supports
no expressions, scripts, file includes or environment/secret lookups. Placeholder
names are case-sensitive and cannot appear in JSON field names. Each template must
retain its specified set of placeholders; they may be repeated or rearranged.

## Template catalog

| Entries | Kind | Available placeholders |
| --- | --- | --- |
| `system-prompt` | System instruction text for the LLM | None |
| `final-answer-contract` | Additional system instruction defining the required final JSON proposal | None |
| `final-round-instruction` | Answer-only instruction appended to the single system message when no tools are available | None |
| `evidence-rejected`, `evidence-none`, `evidence-demo`, `evidence-disclaimer`, `evidence-overflow` | Safe evidence-checking messages and attribution guidance | None |
| `evidence-status` | Verified health with quoted source and source-reported summary | `service`, `status`, `source`, `summary` |
| `evidence-runbook` | Source-provided steps, quoted for human review | `service`, `source`, `steps` |
| `evidence-missing` | Tool/service without verified data | `tool`, `service` |
| `evidence-answer` | Final assembly of evidence-rendered text | `results` |
| `user-message` | Presentation of user text sent to the LLM | `text` |
| `assistant-message` | Presentation of the final A2A reply | `text` |
| `tool-result` | Validated downstream JSON sent back as a tool result; default is the unchanged object | `data` |
| `service-schema` | Advertised JSON input schema shared by both tools | None |
| `status-description`, `runbook-description` | Descriptions advertised to the LLM | None |
| `error-invalid-tool`, `error-denied-tool`, `error-unavailable-tool`, `error-unknown-tool` | JSON tool failure results | None |
| `graph-limit`, `graph-too-many`, `graph-invalid`, `execution-failed` | Safe fallback message text | None |
| `demo-call` | Scripted tool-call arguments | `service` |
| `demo-answer` | Legacy entry retained for override compatibility; final replies now use `evidence-*` | `results` |
| `demo-unknown`, `client-question` | Legacy demo guidance (no longer rendered) and default client question | None |
| `rpc-invalid-json`, `rpc-invalid-envelope`, `rpc-unsupported-method`, `rpc-invalid-params`, `rpc-interrupted` | JSON-RPC error message text | None |
| `error-busy`, `error-oversized` | JSON transport failure bodies | None |
| `agent-name`, `agent-description`, `security-description`, `skill-name`, `skill-description` | Agent Card descriptive text | None |
| `demo-status` | Synthetic status API JSON | `service`, `status`, `summary` |
| `demo-runbook` | Synthetic runbook API JSON | `service` |
| `demo-degraded-summary`, `demo-healthy-summary` | Synthetic status summary text | None |
| `demo-unauthorized`, `demo-method`, `demo-not-found` | Synthetic API error JSON | None |

Tool schemas and descriptions explain how to call an existing tool. Editing them
does not add tools or grant access to another service. Update the implementation
and its tests when extending supported services or argument shapes.

## Adding a template in code

`TemplateContract` defines each supported key, its type and required parameters in
one place. Add the contract and the bundled JSON entry together when introducing
a new message. Deployment overrides customize existing entries without changing
their contracts. Placeholder syntax is validated during loading; rendering checks
runtime parameter names against the already-defined contract.

## Validation and ownership

Catalogs must be valid JSON with no duplicate keys or trailing documents. Files are
limited to 64 KiB, and each template to 16 KiB of serialized JSON (JSON payload
templates additionally must fit 8 KiB; text must fit 16,000 characters). Loading checks
names, template types and placeholder contracts. Text entries cannot be blank;
`error-*` objects require a text `error` field; the tool schema must describe an
object with properties. Missing files and invalid catalogs fail startup without
printing the file contents.

Rendering retains output bounds: text is limited to 16,000 characters, JSON output
to 8 KiB. These same limits apply to the JSON-tree renderer; choosing a different
accessor does not bypass the template contract. Wrappers count toward these
limits. Keep templates concise, especially wrappers around remote data or model
answers. Incoming request, tool authorization, remote-response and graph limits
remain enforced in code.

The SDK still builds JSON-RPC/A2A and provider protocol envelopes: roles, request
ids, tool-call ids, method names, error codes and capabilities are protocol logic.
User input, actual downstream observations and model-generated answers remain
runtime data. Templates control their application-owned presentation; they do not
replace runtime evidence or rewrite the model's conversation history.

Templates are trusted deployment configuration. Review prompt changes as you would
code changes, and do not place credentials in prompts: model-facing content is sent
to the configured provider. Prompt wording guides behavior; permissions and tool
execution remain guarded by the application.

Final model JSON is checked against immutable evidence before rendering; see
[evidence.md](evidence.md). Keep `final-answer-contract` consistent with that code
contract. `tool-result` affects what the model sees, not the recorded evidence.
Preserve source attribution and untrusted-text labels when overriding `evidence-*`
templates; the checker does not validate claims added by trusted operator templates.

The [provider integration test](../src/test/java/com/example/javaaiagent/api/A2aIntegrationTest.java)
shows a custom prompt, user-message wrapper, tool description and nested tool-result
JSON reaching a simulated provider through the real Spring AI adapter.
