"""Minimal A2A JSON-RPC client, with SDK-validated request and response types."""

import argparse
import asyncio
import json
import os
from uuid import uuid4

import httpx
from a2a.types import AgentCard, MessageSendParams, SendMessageRequest, SendMessageResponse

from jagent.config import Settings


async def ask(question):
    settings = Settings()
    token = os.getenv("A2A_TOKEN")
    if not token and settings.auth_mode == "demo":
        token = settings.demo_token.get_secret_value()
    if not token:
        raise ValueError("Set A2A_TOKEN to an access token issued for this agent")
    async with httpx.AsyncClient(
        timeout=60,
        follow_redirects=False,
        trust_env=False,
        headers={"Authorization": f"Bearer {token}"},
    ) as client:
        discovery = await client.get(
            settings.agent_url.rstrip("/") + "/.well-known/agent-card.json"
        )
        discovery.raise_for_status()
        card = AgentCard.model_validate(discovery.json())
        print(f"Discovered: {card.name} (A2A {card.protocol_version})")
        request = SendMessageRequest(
            id=str(uuid4()),
            params=MessageSendParams.model_validate(
                {
                    "message": {
                        "role": "user",
                        "messageId": str(uuid4()),
                        "parts": [{"kind": "text", "text": question}],
                    }
                }
            ),
        )
        # Use the configured URL; discovery must not redirect credentials to another host.
        response = await client.post(
            settings.agent_url,
            json=request.model_dump(mode="json", by_alias=True, exclude_none=True),
        )
        response.raise_for_status()
        result = SendMessageResponse.model_validate(response.json())
        print(
            json.dumps(result.model_dump(mode="json", by_alias=True, exclude_none=True), indent=2)
        )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "question", nargs="?", default="Why is payments degraded, and what should I check?"
    )
    asyncio.run(ask(parser.parse_args().question))


if __name__ == "__main__":
    main()
