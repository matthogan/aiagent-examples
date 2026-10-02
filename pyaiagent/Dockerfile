FROM python:3.11-slim
COPY --from=ghcr.io/astral-sh/uv:0.11.23 /uv /usr/local/bin/uv
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project
COPY jagent ./jagent
RUN useradd --uid 10001 --create-home agent
USER 10001
EXPOSE 8000
CMD ["/app/.venv/bin/uvicorn", "jagent.server:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000", "--no-proxy-headers"]
