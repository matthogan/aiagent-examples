"""Authentication and admission checks independent of model behavior."""

import asyncio
import hmac
import json
import logging
import time
from dataclasses import dataclass
from pathlib import Path
from uuid import uuid4

import jwt
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import JSONResponse

from jagent.config import Settings

audit = logging.getLogger("jagent.audit")


@dataclass(frozen=True)
class Principal:
    subject: str
    scopes: frozenset[str]
    services: frozenset[str]


class Authenticator:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.public_key = (
            Path(settings.jwt_public_key_file).read_text() if settings.auth_mode == "jwt" else None
        )

    def authenticate(self, authorization: str) -> Principal:
        scheme, _, token = authorization.partition(" ")
        if scheme.lower() != "bearer" or not token:
            raise ValueError("Missing bearer token")
        if self.settings.auth_mode == "demo":
            if not hmac.compare_digest(
                token.encode(), self.settings.demo_token.get_secret_value().encode()
            ):
                raise ValueError("Invalid token")
            return Principal(
                "local-demo",
                frozenset({"agent:invoke", "status:read", "runbooks:read"}),
                frozenset({"payments", "orders"}),
            )
        claims = jwt.decode(
            token,
            self.public_key,
            algorithms=["RS256"],
            issuer=self.settings.jwt_issuer,
            audience=self.settings.jwt_audience,
            options={"require": ["exp", "iat", "iss", "aud", "sub"]},
        )
        scope, services = claims.get("scope", ""), claims.get("services", [])
        if (
            not isinstance(scope, str)
            or not isinstance(services, list)
            or not all(isinstance(s, str) for s in services)
            or not isinstance(claims["sub"], str)
            or not claims["sub"]
        ):
            raise ValueError("Invalid authorization claims")
        return Principal(claims["sub"], frozenset(scope.split()), frozenset(services))


class SecurityMiddleware(BaseHTTPMiddleware):
    """Bound request size and concurrency before handing a request to the SDK."""

    def __init__(self, app, settings: Settings):
        super().__init__(app)
        self.auth = Authenticator(settings)
        self.active = 0

    async def dispatch(self, request, call_next):
        request_id = str(uuid4())  # Never trust caller-provided audit identifiers.
        request.state.request_id = request_id
        started = time.monotonic()
        status = 500

        def error(code, message):
            headers = {"WWW-Authenticate": "Bearer"} if code == 401 else {}
            return JSONResponse({"error": message}, status_code=code, headers=headers)

        async def handle():
            # Liveness contains no configuration, credentials, or downstream data.
            if request.url.path == "/healthz" and request.method == "GET":
                return await call_next(request)
            try:
                request.state.principal = self.auth.authenticate(
                    request.headers.get("authorization", "")
                )
            except (ValueError, jwt.PyJWTError):
                return error(401, "Invalid or missing bearer token")
            if "agent:invoke" not in request.state.principal.scopes:
                return error(403, "agent:invoke scope required")
            if request.method == "POST" and request.url.path == "/":
                body = bytearray()
                try:
                    async with asyncio.timeout(10):
                        async for chunk in request.stream():
                            body.extend(chunk)
                            if len(body) > 16_384:
                                return error(413, "Request exceeds 16 KiB")
                except TimeoutError:
                    return error(408, "Request body timeout")
                # Replay the bounded body to Starlette's downstream request.
                request._body = bytes(body)
                try:
                    payload = json.loads(body)
                except (ValueError, UnicodeDecodeError):
                    return error(400, "Invalid JSON")
                # Enforce the advertised single-turn profile, including against clients
                # that ignore the Agent Card's streaming/push capability flags.
                if isinstance(payload, dict) and payload.get("method") != "message/send":
                    rpc_id = payload.get("id")
                    if not isinstance(rpc_id, (str, int)):
                        rpc_id = None
                    return JSONResponse(
                        {
                            "jsonrpc": "2.0",
                            "id": rpc_id,
                            "error": {"code": -32601, "message": "Only message/send is supported"},
                        }
                    )
                if isinstance(payload, dict) and payload.get("method") == "message/send":
                    params = payload.get("params")
                    message = params.get("message") if isinstance(params, dict) else None
                    if isinstance(message, dict) and any(
                        message.get(k) is not None
                        for k in (
                            "taskId",
                            "contextId",
                            "referenceTaskIds",
                            "task_id",
                            "context_id",
                            "reference_task_ids",
                        )
                    ):
                        return error(400, "This example accepts new single-turn messages only")
                if self.active >= 8:
                    return error(429, "Agent is busy; retry later")
                self.active += 1
                try:
                    return await call_next(request)
                finally:
                    self.active -= 1
            return await call_next(request)

        try:
            response = await handle()
            status = response.status_code
            response.headers["X-Request-ID"] = request_id
            response.headers["Cache-Control"] = "no-store"
            response.headers["X-Content-Type-Options"] = "nosniff"
            return response
        finally:
            audit.info(
                json.dumps(
                    {
                        "event": "http_request",
                        "request_id": request_id,
                        "status": status,
                        "duration_ms": round((time.monotonic() - started) * 1000),
                    }
                )
            )
