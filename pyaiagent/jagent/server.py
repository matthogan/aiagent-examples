import asyncio
import json
import logging

import httpx
from a2a.server.agent_execution import AgentExecutor
from a2a.server.apps import A2AStarletteApplication
from a2a.server.apps.jsonrpc.jsonrpc_app import CallContextBuilder
from a2a.server.context import ServerCallContext
from a2a.server.request_handlers import DefaultRequestHandler
from a2a.server.tasks import InMemoryTaskStore
from a2a.types import (
    AgentCapabilities,
    AgentCard,
    AgentSkill,
    HTTPAuthSecurityScheme,
    InvalidParamsError,
    TaskNotCancelableError,
    TextPart,
)
from a2a.utils import new_agent_text_message
from a2a.utils.errors import ServerError
from langchain_core.messages import HumanMessage
from starlette.responses import JSONResponse
from starlette.routing import Route

from jagent.config import Settings
from jagent.graph import build_graph
from jagent.security import SecurityMiddleware
from jagent.tools import build_tools


class AuthenticatedContext(CallContextBuilder):
    def build(self, request):
        # Copy only verified identity and correlation ID, never bearer credentials.
        return ServerCallContext(
            state={"principal": request.state.principal, "request_id": request.state.request_id}
        )


class OperationsExecutor(AgentExecutor):
    def __init__(self, settings, transport=None):
        self.settings = settings
        self.transport = transport

    async def execute(self, context, event_queue):
        message = context.message
        if (
            not message
            or message.role != "user"
            or not message.parts
            or any(not isinstance(p.root, TextPart) for p in message.parts)
        ):
            raise ServerError(
                InvalidParamsError(message="A nonempty user text message is required")
            )
        question = context.get_user_input().strip()
        if not question or len(question) > 4000:
            raise ServerError(InvalidParamsError(message="Text must contain 1–4000 characters"))
        state = context.call_context.state
        try:
            async with asyncio.timeout(45):
                async with httpx.AsyncClient(
                    timeout=5,
                    follow_redirects=False,
                    trust_env=False,
                    transport=self.transport,
                ) as client:
                    tools = build_tools(
                        self.settings, state["principal"], client, state["request_id"]
                    )
                    result = await build_graph(self.settings, tools).ainvoke(
                        {"messages": [HumanMessage(content=question)], "rounds": 0},
                        {"recursion_limit": 12},
                    )
                    answer = result["messages"][-1].content
                    if not isinstance(answer, str):
                        answer = "The model did not return a text answer. Please retry."
        except Exception as exc:  # noqa: BLE001 -- sanitize all provider failures at this boundary
            # Never return/log exception text: upstream exceptions can contain secrets or prompts.
            logging.getLogger("jagent.audit").warning(
                json.dumps(
                    {
                        "event": "execution_failed",
                        "request_id": state["request_id"],
                        "error_type": type(exc).__name__,
                    }
                )
            )
            answer = "The request could not be completed. Retry or contact the service operator."
        await event_queue.enqueue_event(new_agent_text_message(answer))

    async def cancel(self, context, event_queue):
        raise ServerError(TaskNotCancelableError())


def create_app(settings=None, transport=None):
    settings = settings or Settings()
    card = AgentCard(
        name="Operations assistant",
        description="Read-only service health and runbook assistant",
        url=settings.agent_url,
        version="0.1.0",
        protocol_version="0.3.0",
        capabilities=AgentCapabilities(streaming=False, push_notifications=False),
        default_input_modes=["text/plain"],
        default_output_modes=["text/plain"],
        security_schemes={
            "bearer": HTTPAuthSecurityScheme(
                scheme="bearer",
                bearer_format="JWT" if settings.auth_mode == "jwt" else "opaque",
                description="Requires agent:invoke; tools require status:read / runbooks:read and services grants",
            )
        },
        security=[{"bearer": []}],
        skills=[
            AgentSkill(
                id="operations",
                name="Investigate service health",
                description="Read health and suggest runbook steps for payments or orders",
                tags=["operations", "read-only"],
                examples=["Why is payments degraded, and what should I check?"],
            )
        ],
    )
    handler = DefaultRequestHandler(OperationsExecutor(settings, transport), InMemoryTaskStore())
    app = A2AStarletteApplication(
        card, handler, context_builder=AuthenticatedContext(), max_content_length=16_384
    ).build()

    async def health(request):
        return JSONResponse({"status": "ok"})

    app.routes.append(Route("/healthz", health))
    app.add_middleware(SecurityMiddleware, settings=settings)
    return app


def main():
    import uvicorn

    logging.basicConfig(level=logging.INFO, format="%(message)s")
    # HTTP client debug logging can expose provider details; opt in deliberately if needed.
    logging.getLogger("httpx").setLevel(logging.WARNING)
    uvicorn.run(create_app(), host="127.0.0.1", port=8000)


if __name__ == "__main__":
    main()
