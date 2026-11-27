import logging
from typing import Optional
import httpx

from app.config import get_settings

logger = logging.getLogger("clucky.ai.embeddings")


async def embed_text(text: str, task_type: str = "RETRIEVAL_DOCUMENT") -> Optional[list[float]]:
    settings = get_settings()
    if not settings.llm_configured or not text.strip():
        return None

    provider = settings.llm_provider.strip().lower()
    timeout = httpx.Timeout(settings.llm_timeout_seconds)

    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            if provider == "openai":
                base = (settings.llm_base_url or "https://api.openai.com/v1").rstrip("/")
                resp = await client.post(
                    f"{base}/embeddings",
                    headers={"Authorization": f"Bearer {settings.llm_api_key}"},
                    json={
                        "model": settings.embedding_model or "text-embedding-3-small",
                        "input": text[:6000],
                        "dimensions": settings.embedding_dim,
                    },
                )
                resp.raise_for_status()
                data = resp.json()
                vec = data["data"][0]["embedding"]
                return [float(x) for x in vec]

            # Default: Gemini REST API
            base = (settings.llm_base_url or "https://generativelanguage.googleapis.com/v1beta").rstrip("/")
            model = settings.embedding_model or "text-embedding-004"
            if not model.startswith("models/"):
                model = f"models/{model}"
            resp = await client.post(
                f"{base}/{model}:embedContent",
                params={"key": settings.llm_api_key},
                json={
                    "model": model,
                    "content": {"parts": [{"text": text[:6000]}]},
                    "taskType": task_type,
                    "outputDimensionality": settings.embedding_dim,
                },
            )
            resp.raise_for_status()
            data = resp.json()
            vec = data["embedding"]["values"]
            return [float(x) for x in vec]
    except Exception as exc:
        logger.warning("Embedding API call failed; continuing without vector: %s", exc)
        return None
