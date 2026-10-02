"""Exercise the documented CLI against three real HTTP server processes."""

import os
import socket
import subprocess
import sys
import time

import httpx


def test_live_client_and_remote_services():
    ports = []
    for _ in range(3):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            ports.append(listener.getsockname()[1])
    env = dict(
        os.environ,
        MODEL_MODE="demo",
        AUTH_MODE="demo",
        ENVIRONMENT="local",
        AGENT_URL=f"http://127.0.0.1:{ports[0]}/",
        STATUS_URL=f"http://127.0.0.1:{ports[1]}",
        RUNBOOK_URL=f"http://127.0.0.1:{ports[2]}",
        DEMO_TOKEN="local-demo-client-token",
        STATUS_TOKEN="local-demo-status-token",
        RUNBOOK_TOKEN="local-demo-runbook-token",
    )
    env.pop("A2A_TOKEN", None)
    commands = [
        "from jagent.server import create_app; import uvicorn; "
        f"uvicorn.run(create_app(),host='127.0.0.1',port={ports[0]},log_level='error')"
    ]
    for kind, port in zip(("status", "runbook"), ports[1:], strict=True):
        commands.append(
            "from jagent.mock_services import create_service; import uvicorn; "
            f"uvicorn.run(create_service('{kind}'),host='127.0.0.1',port={port},log_level='error')"
        )
    processes = []
    try:
        for code in commands:
            processes.append(
                subprocess.Popen(
                    [sys.executable, "-c", code],
                    env=env,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
            )
        with httpx.Client(trust_env=False) as client:
            for process, port in zip(processes, ports, strict=True):
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline:
                    assert process.poll() is None, "Server process failed to start"
                    try:
                        # A 404 is sufficient for readiness of the fixture APIs.
                        client.get(f"http://127.0.0.1:{port}/healthz", timeout=1)
                        break
                    except httpx.TransportError:
                        time.sleep(0.1)
                else:
                    raise AssertionError("Server startup timed out")
        result = subprocess.run(
            [sys.executable, "-m", "jagent.client"],
            env=env,
            capture_output=True,
            text=True,
            timeout=60,
        )
        assert result.returncode == 0, result.stderr
        assert "Discovered: Operations assistant" in result.stdout
        assert "demo-status:payments" in result.stdout
        assert "demo-runbook:payments" in result.stdout
    finally:
        for process in processes:
            process.terminate()
        for process in processes:
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
