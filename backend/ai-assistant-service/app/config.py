from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    server_port: int = 8080

    postgres_host: str = "postgres"
    postgres_port: int = 5432
    postgres_user: str = "clucky"
    postgres_password: str = "change-me"
    db_name: str = "clucky"

    redis_host: str = "redis"
    redis_port: int = 6379
    redis_password: str = ""

    jwt_secret: str = "clucky-dev-secret-jwt-key-2026-auth-token-32bytes-min"
    jwt_issuer: str = "clucky-identity"
    jwt_audience: str = "clucky-api"

    catalog_service_url: str = "http://catalog-store-service:8080"
    review_report_service_url: str = "http://review-report-service:8080"
    promotion_service_url: str = "http://promotion-loyalty-service:8080"
    cart_order_service_url: str = "http://cart-order-service:8080"
    identity_service_url: str = "http://identity-service:8080"

    llm_provider: str = "gemini"
    llm_api_key: str = ""
    llm_model: str = "gemini-2.5-flash"
    embedding_model: str = "text-embedding-004"
    llm_base_url: str = ""
    llm_timeout_seconds: float = 20.0
    http_timeout_seconds: float = 5.0

    rate_limit_guest_per_min: int = 20
    rate_limit_user_per_min: int = 30
    max_input_chars: int = 800
    max_history_turns: int = 10
    history_ttl_seconds: int = 86400
    embedding_dim: int = 768

    @property
    def postgres_dsn(self) -> str:
        return (
            f"postgresql://{self.postgres_user}:{self.postgres_password}"
            f"@{self.postgres_host}:{self.postgres_port}/{self.db_name}"
        )

    @property
    def llm_configured(self) -> bool:
        key = (self.llm_api_key or "").strip()
        return bool(key) and not key.startswith("replace-") and not key.startswith("change-")


@lru_cache
def get_settings() -> Settings:
    return Settings()
