"""Check client/server interoperability; add --with-go after building the Go binary."""

import argparse
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--with-go", action="store_true", help="Include Go and use its mock APIs")
    args = parser.parse_args()
    go_binary = ROOT / "goaiagent/bin" / ("goaiagent.exe" if os.name == "nt" else "goaiagent")
    if args.with_go and not go_binary.is_file():
        raise SystemExit("Build Go first: cd goaiagent; go build -o bin/ ./cmd/goaiagent")
    jar = ROOT / "javaaiagent/target/javaaiagent-0.1.0.jar"
    if not jar.is_file():
        raise SystemExit("Build Java first: mvn -f javaaiagent/pom.xml package")
    java_home = os.environ.get("JAVA_HOME")
    java = (
        str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java"))
        if java_home
        else shutil.which("java")
    )
    if not java:
        raise SystemExit("Java 21+ is required")
    ports = []
    for _ in range(5 if args.with_go else 4):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            ports.append(listener.getsockname()[1])
    java_port, python_port, status_port, runbook_port = ports[:4]
    env = dict(
        os.environ,
        ENVIRONMENT="local",
        MODEL_MODE="demo",
        AUTH_MODE="demo",
        DEMO_TOKEN="local-demo-client-token",
        STATUS_TOKEN="local-demo-status-token",
        RUNBOOK_TOKEN="local-demo-runbook-token",
        SERVER_ADDRESS="127.0.0.1",
        STATUS_URL=f"http://127.0.0.1:{status_port}",
        RUNBOOK_URL=f"http://127.0.0.1:{runbook_port}",
    )
    env.pop("A2A_TOKEN", None)
    processes = []
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with tempfile.TemporaryFile() as logs:
        try:
            mock_command = [str(go_binary)] if args.with_go else [java, "-jar", str(jar)]
            commands = [
                ([*mock_command, "mock", "status", str(status_port)], env, ROOT),
                ([*mock_command, "mock", "runbook", str(runbook_port)], env, ROOT),
                (
                    [java, "-jar", str(jar)],
                    dict(
                        env, SERVER_PORT=str(java_port), AGENT_URL=f"http://127.0.0.1:{java_port}/"
                    ),
                    ROOT,
                ),
                (
                    [
                        sys.executable,
                        "-m",
                        "uvicorn",
                        "jagent.server:create_app",
                        "--factory",
                        "--host",
                        "127.0.0.1",
                        "--port",
                        str(python_port),
                    ],
                    dict(env, AGENT_URL=f"http://127.0.0.1:{python_port}/"),
                    ROOT / "pyaiagent",
                ),
            ]
            if args.with_go:
                go_port = ports[4]
                commands.append(
                    (
                        [str(go_binary)],
                        dict(
                            env, SERVER_PORT=str(go_port), AGENT_URL=f"http://127.0.0.1:{go_port}/"
                        ),
                        ROOT,
                    )
                )
            for command, process_env, cwd in commands:
                processes.append(
                    subprocess.Popen(command, env=process_env, cwd=cwd, stdout=logs, stderr=logs)
                )
            for port in ports:
                deadline = time.monotonic() + 45
                while time.monotonic() < deadline:
                    if any(p.poll() is not None for p in processes):
                        raise RuntimeError("A server exited during startup")
                    try:
                        with opener.open(f"http://127.0.0.1:{port}/healthz", timeout=1):
                            break
                    except urllib.error.HTTPError:
                        break  # Mock services have no health endpoint.
                    except (urllib.error.URLError, TimeoutError):
                        time.sleep(0.1)
                else:
                    raise RuntimeError("Server startup timed out")
            checks = [
                ("Python client -> Java agent", [sys.executable, "-m", "jagent.client"], java_port),
                ("Java client -> Python agent", [java, "-jar", str(jar), "client"], python_port),
            ]
            if args.with_go:
                checks.extend(
                    [
                        (
                            "Python client -> Go agent",
                            [sys.executable, "-m", "jagent.client"],
                            go_port,
                        ),
                        ("Java client -> Go agent", [java, "-jar", str(jar), "client"], go_port),
                        ("Go client -> Python agent", [str(go_binary), "client"], python_port),
                        ("Go client -> Java agent", [str(go_binary), "client"], java_port),
                    ]
                )
            for label, command, port in checks:
                result = subprocess.run(
                    command,
                    cwd=ROOT / "pyaiagent",
                    env=dict(env, AGENT_URL=f"http://127.0.0.1:{port}/"),
                    capture_output=True,
                    text=True,
                    timeout=60,
                )
                if result.returncode or not all(
                    source in result.stdout
                    for source in ("demo-status:payments", "demo-runbook:payments")
                ):
                    raise RuntimeError(f"{label} failed:\n{result.stdout}\n{result.stderr}")
                print(f"PASS: {label}, discovery + both remote tools")
        finally:
            for process in processes:
                process.terminate()
            for process in processes:
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


if __name__ == "__main__":
    main()
