# A2A agent examples: Python and Java

Two equivalent, independently runnable operations agents. Both expose **A2A 0.3
JSON-RPC**, use an explicit model → tools → model graph, and read from remote
service-status and runbook APIs. Both have a scripted no-key demo mode and a real
LLM mode. Neither performs infrastructure changes.

| | Python | Java |
| --- | --- | --- |
| Project | [pyaiagent](pyaiagent/README.md) | [javaaiagent](javaaiagent/README.md) |
| Runtime | Python 3.11+ | Java 21+ |
| Model integration | LangChain / ChatOpenAI | Spring AI / OpenAiChatModel |
| Graph | LangGraph | LangGraph4j |
| HTTP server | Starlette + A2A SDK handler | Spring Boot MVC + A2A SDK wire types |
| Authentication | PyJWT | Spring Security resource server |
| Default agent port | 8000 | 8080 |
| Default status / runbook ports | 8001 / 8002 | 8081 / 8082 |
| Build / tests | `uv sync --locked`, `uv run pytest -q` | `mvn verify` |

```text
jagent/
├── pyaiagent/       # Original Python project; Python package remains named jagent
├── javaaiagent/     # Spring Boot, Spring AI and LangGraph4j equivalent
├── scripts/        # Cross-language interoperability smoke test
└── .github/        # CI jobs for both projects
```

Both implement the same `message/send` single-turn profile, Agent Card discovery,
bearer authentication, read-only tool names, `payments` / `orders` resources,
scope claims and remote API schemas. A2A is used between a client and an agent;
the two tools use ordinary HTTP. LangGraph4j is a separate Java library inspired
by LangGraph, rather than the Python package embedded in Java.

Start with the individual READMEs for build, startup and real-model configuration.
Security design and deployment requirements are in
[Python security](pyaiagent/docs/security.md) and
[Java security](javaaiagent/docs/security.md).

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

The Python project was moved without renaming its import package or commands.
Run its existing commands **from `pyaiagent`**. CI remains at the repository root
with separate working directories for Python and Java.
