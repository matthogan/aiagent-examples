# A2A agent examples: Python, Java and Go

Three equivalent, independently runnable operations agents. All expose **A2A 0.3
JSON-RPC**, use an explicit model → tools → model graph, and read from remote
service-status and runbook APIs. All have a scripted no-key demo mode and a real
LLM mode. None performs infrastructure changes.

| | Python | Java | Go |
| --- | --- | --- | --- |
| Project | [pyaiagent](pyaiagent/README.md) | [javaaiagent](javaaiagent/README.md) | [goaiagent](goaiagent/README.md) |
| Runtime | Python 3.11+ | Java 21+ | Go 1.26.5+ |
| Model integration | LangChain / ChatOpenAI | Spring AI / OpenAiChatModel | Eino OpenAI adapter |
| Graph | LangGraph | LangGraph4j | Eino |
| HTTP server | Starlette + A2A SDK handler | Spring MVC + A2A SDK types | net/http + A2A SDK types |
| Authentication | PyJWT | Spring Security | golang-jwt |
| Default agent port | 8000 | 8080 | 8090 |
| Default status / runbook ports | 8001 / 8002 | 8081 / 8082 | 8091 / 8092 |
| Build / tests | `uv sync --locked`, `uv run pytest -q` | `mvn verify` | `go build -o bin/ ./cmd/goaiagent`, `go test ./...` |

```text
jagent/
├── pyaiagent/       # Original Python project; Python package remains named jagent
├── javaaiagent/     # Spring Boot, Spring AI and LangGraph4j equivalent
├── goaiagent/       # Go, Eino and A2A equivalent
├── scripts/        # Cross-language interoperability smoke test
└── .github/        # CI jobs for all three projects
```

All implement the same `message/send` single-turn profile, Agent Card discovery,
bearer authentication, read-only tool names, `payments` / `orders` resources,
scope claims and remote API schemas. A2A is used between a client and an agent;
the two tools use ordinary HTTP. LangGraph4j is a separate Java library inspired
by LangGraph, rather than the Python package embedded in Java. Eino provides
the equivalent explicit graph orchestration in Go.

Start with the individual READMEs for build, startup and real-model configuration.
Java defaults to a real local LLM (`qwen3.5-9b` at `http://localhost:6666`);
select `MODEL_MODE=demo` explicitly for Java's scripted no-LLM mode.
Security design and deployment requirements are in
[Python security](pyaiagent/docs/security.md) and
[Java security](javaaiagent/docs/security.md), and
[Go security](goaiagent/docs/security.md).

## Cross-language check

From this root directory, after installing Java 21+, Maven and uv:

```powershell
mvn -f javaaiagent/pom.xml package
uv sync --project pyaiagent --locked
uv run --project pyaiagent python scripts/verify_interop.py
```

The script starts both agents and two Java mock APIs on temporary loopback ports.
It checks Python client → Java agent and Java client → Python agent, including
discovery and both tools, then stops its own server processes. It makes no paid
LLM calls. `JAVA_HOME`, when set, selects the Java runtime.

To include Go, first build its binary from `goaiagent`:

```powershell
cd goaiagent
go build -o bin/ ./cmd/goaiagent
cd ..
uv run --project pyaiagent python scripts/verify_interop.py --with-go
```

With `--with-go`, the script starts all three agents and uses Go's two mock APIs.
It checks all six cross-language client/agent pairings, including discovery and
both tools. The existing invocation without the flag remains a Python/Java check.

The Python project was moved without renaming its import package or commands.
Run its existing commands **from `pyaiagent`**. CI remains at the repository root
with separate working directories for Python, Java and Go.
