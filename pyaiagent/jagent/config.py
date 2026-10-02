from typing import Literal
from urllib.parse import urlsplit

from pydantic import SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    environment: Literal["local", "production"] = "local"
    model_mode: Literal["demo", "openai"] = "demo"
    llm_model: str = "gpt-4.1-mini"
    openai_api_key: SecretStr | None = None
    agent_url: str = "http://127.0.0.1:8000/"
    auth_mode: Literal["demo", "jwt"] = "demo"
    demo_token: SecretStr = SecretStr("local-demo-client-token")
    jwt_public_key_file: str | None = None
    jwt_issuer: str = "https://identity.example.com/"
    jwt_audience: str = "jagent"
    status_url: str = "http://127.0.0.1:8001"
    runbook_url: str = "http://127.0.0.1:8002"
    status_token: SecretStr = SecretStr("local-demo-status-token")
    runbook_token: SecretStr = SecretStr("local-demo-runbook-token")

    @model_validator(mode="after")
    def validate_deployment(self):
        for address in (self.agent_url, self.status_url, self.runbook_url):
            parsed = urlsplit(address)
            if (
                parsed.scheme not in {"http", "https"}
                or not parsed.hostname
                or parsed.username
                or parsed.password
                or parsed.query
                or parsed.fragment
            ):
                raise ValueError("Service URLs must be HTTP(S) URLs without credentials or queries")
            if self.environment == "production" and parsed.scheme != "https":
                raise ValueError("Production URLs require HTTPS")
        if self.auth_mode == "jwt" and not self.jwt_public_key_file:
            raise ValueError("JWT_PUBLIC_KEY_FILE is required for JWT authentication")
        if self.model_mode == "openai" and not self.openai_api_key:
            raise ValueError("OPENAI_API_KEY is required in openai mode")
        if self.environment == "production":
            if self.auth_mode != "jwt" or self.model_mode != "openai":
                raise ValueError("Production requires JWT authentication and a real LLM")
            tokens = [self.status_token.get_secret_value(), self.runbook_token.get_secret_value()]
            if any(len(t) < 32 or t.startswith("local-demo-") for t in tokens):
                raise ValueError("Production requires separate strong tool credentials")
            if tokens[0] == tokens[1]:
                raise ValueError("Tool credentials must be distinct")
        return self
