"""Two separately hosted remote APIs with synthetic data; local development only."""

import argparse
import hmac

from starlette.applications import Starlette
from starlette.responses import JSONResponse
from starlette.routing import Route

from jagent.config import Settings


def create_service(kind: str, settings=None):
    settings = settings or Settings()
    if settings.environment != "local":
        raise ValueError("Synthetic services are for local development only")
    if kind not in {"status", "runbook"}:
        raise ValueError("Unknown service kind")
    token = settings.status_token if kind == "status" else settings.runbook_token

    async def get_data(request):
        expected = f"Bearer {token.get_secret_value()}"
        if not hmac.compare_digest(
            request.headers.get("authorization", "").encode(), expected.encode()
        ):
            return JSONResponse(
                {"error": "Unauthorized"}, status_code=401, headers={"WWW-Authenticate": "Bearer"}
            )
        service = request.path_params["service"]
        if service not in {"payments", "orders"}:
            return JSONResponse({"error": "Unknown service"}, status_code=404)
        data = {"service": service, "source": f"demo-{kind}:{service}"}
        if kind == "status":
            data.update(
                status="degraded" if service == "payments" else "healthy",
                summary="Synthetic fixture: elevated gateway latency"
                if service == "payments"
                else "Synthetic fixture: all checks passing",
            )
        else:
            data["steps"] = [
                "Review latency and error dashboards.",
                "Check recent deployments and upstream provider status.",
                "Escalate to the on-call engineer before making changes.",
            ]
        return JSONResponse(data)

    return Starlette(routes=[Route("/services/{service}", get_data)])


def main():
    import uvicorn

    parser = argparse.ArgumentParser()
    parser.add_argument("kind", choices=["status", "runbook"])
    args = parser.parse_args()
    uvicorn.run(
        create_service(args.kind), host="127.0.0.1", port=8001 if args.kind == "status" else 8002
    )


if __name__ == "__main__":
    main()
