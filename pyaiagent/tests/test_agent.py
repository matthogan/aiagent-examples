import json
import time

import httpx
import jwt
import pytest
from a2a.types import SendMessageRequest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from langchain_core.messages import AIMessage, HumanMessage
from pydantic import ValidationError
from starlette.testclient import TestClient

from jagent.config import Settings
from jagent.graph import build_graph
from jagent.mock_services import create_service
from jagent.security import Principal
from jagent.server import create_app
from jagent.tools import build_tools

AUTH = {"Authorization": "Bearer local-demo-client-token"}


def settings(**kwargs):
    return Settings(_env_file=None, **kwargs)


def message(text="Investigate payments"):
    return {
        "jsonrpc": "2.0",
        "id": "test-1",
        "method": "message/send",
        "params": {
            "message": {
                "messageId": "message-1",
                "role": "user",
                "parts": [{"kind": "text", "text": text}],
            }
        },
    }


def remote(request):
    service = request.url.path.split("/")[-1]
    if request.url.port == 8001:
        assert request.headers["authorization"] == "Bearer local-demo-status-token"
        return httpx.Response(
            200,
            json={
                "service": service,
                "status": "degraded",
                "summary": "Elevated latency",
                "source": "status:payments",
            },
        )
    assert request.headers["authorization"] == "Bearer local-demo-runbook-token"
    return httpx.Response(
        200,
        json={
            "service": service,
            "steps": ["Check upstream latency"],
            "source": "runbook:payments",
        },
    )


def test_a2a_through_graph_and_both_remote_tools():
    calls = []

    def record(request):
        calls.append(request)
        return remote(request)

    with TestClient(create_app(settings(), httpx.MockTransport(record))) as client:
        card = client.get("/.well-known/agent-card.json", headers=AUTH).json()
        assert card["protocolVersion"] == "0.3.0"
        assert card["security"] == [{"bearer": []}]
        # SDK serialization can include null context/task fields on new messages.
        payload = SendMessageRequest.model_validate(message()).model_dump(
            mode="json", by_alias=True
        )
        response = client.post("/", headers=AUTH, json=payload)
    assert response.status_code == 200
    body = response.json()
    assert "error" not in body, body
    text = body["result"]["parts"][0]["text"]
    assert "Elevated latency" in text and "Check upstream latency" in text
    assert len(calls) == 2
    assert all(r.headers["x-request-id"] == response.headers["x-request-id"] for r in calls)


@pytest.mark.parametrize("path", ["/", "/.well-known/agent-card.json"])
def test_requires_auth(path):
    with TestClient(create_app(settings())) as client:
        response = client.get(path)
        assert response.status_code == 401
        assert response.headers["www-authenticate"] == "Bearer"
        assert client.get("/healthz").status_code == 200


def test_rejects_oversize_and_state_reuse():
    with TestClient(create_app(settings())) as client:
        assert client.post("/", headers=AUTH, content=b"x" * 16385).status_code == 413
        payload = message()
        payload["params"]["message"]["contextId"] = "another-user-context"
        assert client.post("/", headers=AUTH, json=payload).status_code == 400
        assert "error" in client.post("/", headers=AUTH, json=message("")).json()
        payload = message()
        payload["params"]["message"]["parts"] = [
            {"kind": "data", "data": {"url": "file:///secret"}}
        ]
        assert "error" in client.post("/", headers=AUTH, json=payload).json()


@pytest.fixture
def jwt_config(tmp_path):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    path = tmp_path / "public.pem"
    path.write_bytes(
        key.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        )
    )
    return key, settings(auth_mode="jwt", jwt_public_key_file=str(path))


def signed(key, **overrides):
    claims = {
        "sub": "operator-1",
        "iss": "https://identity.example.com/",
        "aud": "jagent",
        "iat": int(time.time()),
        "exp": int(time.time()) + 300,
        "scope": "agent:invoke status:read",
        "services": ["payments"],
    }
    return jwt.encode(claims | overrides, key, algorithm="RS256")


@pytest.mark.parametrize(
    "claims,expected",
    [
        ({"aud": "other"}, 401),
        ({"iss": "https://attacker.example"}, 401),
        ({"exp": 1}, 401),
        ({"scope": "status:read"}, 403),
        ({"services": "payments"}, 401),
    ],
)
def test_jwt_rejects_bad_identity_and_scope(jwt_config, claims, expected):
    key, config = jwt_config
    with TestClient(create_app(config)) as client:
        response = client.post(
            "/", json=message(), headers={"Authorization": f"Bearer {signed(key, **claims)}"}
        )
    assert response.status_code == expected


def test_jwt_enforces_tool_and_resource_permissions(jwt_config):
    key, config = jwt_config
    calls = []

    def record(request):
        calls.append(request)
        return remote(request)

    with TestClient(create_app(config, httpx.MockTransport(record))) as client:
        headers = {"Authorization": f"Bearer {signed(key)}"}
        body = client.post("/", headers=headers, json=message()).json()
        assert "Access denied" in json.dumps(body)
        assert len(calls) == 1  # No runbook permission.
        body = client.post("/", headers=headers, json=message("Investigate orders")).json()
        assert "Access denied" in json.dumps(body)
        assert len(calls) == 1  # No orders resource grant.


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(302, headers={"location": "https://evil.example"}),
        httpx.Response(500),
        httpx.Response(200, content="x" * 8193),
        httpx.Response(
            200, json={"service": "orders", "status": "healthy", "summary": "", "source": "x"}
        ),
    ],
)
async def test_remote_errors_and_redirects_fail_closed(response):
    calls = []

    def record(request):
        calls.append(request)
        return response

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(record), follow_redirects=False
    ) as client:
        tools = build_tools(
            settings(),
            Principal("test", frozenset({"status:read"}), frozenset({"payments"})),
            client,
            "test",
        )
        data = await tools[0].ainvoke({"service": "payments"})
        assert "error" in data
        assert len(calls) == 1
        with pytest.raises(ValidationError):
            await tools[0].ainvoke({"service": "https://evil.example"})


@pytest.mark.asyncio
async def test_graph_stops_repeated_tool_calls():
    class LoopingModel:
        async def ainvoke(self, messages):
            return AIMessage(
                content="",
                tool_calls=[
                    {"name": "unknown", "args": {}, "id": str(len(messages)), "type": "tool_call"}
                ],
            )

    graph = build_graph(settings(), [], model=LoopingModel())
    result = await graph.ainvoke({"messages": [HumanMessage(content="loop")], "rounds": 0})
    assert result["rounds"] == 4
    assert "limit reached" in result["messages"][-1].content


def test_production_rejects_demo_configuration():
    with pytest.raises(ValidationError):
        settings(environment="production")


def test_unadvertised_methods_cannot_start_work():
    def forbidden(request):
        pytest.fail("Unsupported protocol method must not invoke tools")

    with TestClient(create_app(settings(), httpx.MockTransport(forbidden))) as client:
        for method in ("message/stream", "tasks/get", "tasks/pushNotificationConfig/set"):
            payload = message()
            payload["method"] = method
            result = client.post("/", headers=AUTH, json=payload).json()
            assert result["error"]["code"] == -32601


def test_jwt_rejects_wrong_signing_key(jwt_config):
    _, config = jwt_config
    wrong_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    with TestClient(create_app(config)) as client:
        response = client.post(
            "/", json=message(), headers={"Authorization": f"Bearer {signed(wrong_key)}"}
        )
        assert response.status_code == 401


def test_mock_service_requires_its_own_credential():
    with TestClient(create_service("status", settings())) as client:
        assert client.get("/services/payments", headers=AUTH).status_code == 401
        assert (
            client.get(
                "/services/payments", headers={"Authorization": "Bearer local-demo-status-token"}
            ).status_code
            == 200
        )
