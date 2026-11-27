import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.routes import router
from app.db.postgres import bootstrap_schema
from app.rag.indexer import run_reindex

logging.basicConfig(
    level=logging.INFO,
    format="%(levelname)s [service=ai-assistant-service] %(name)s: %(message)s",
)
logger = logging.getLogger("clucky.ai.main")


async def _initial_index_task() -> None:
    # Allow peer Java services (catalog/identity) a short window to become ready on cold start.
    for attempt in range(8):
        try:
            result = await run_reindex(force=False)
            catalog_skipped = any(s.startswith("catalog_") for s in result.skippedReasons)
            if not catalog_skipped:
                return
        except Exception as exc:
            logger.warning("Startup index attempt %d failed: %s", attempt + 1, exc)
        await asyncio.sleep(15)


@asynccontextmanager
async def lifespan(_: FastAPI):
    await bootstrap_schema()
    task = asyncio.create_task(_initial_index_task())
    try:
        yield
    finally:
        task.cancel()


app = FastAPI(
    title="Clucky AI Assistant Service",
    version="1.0.0",
    docs_url=None,
    redoc_url=None,
    lifespan=lifespan,
)
app.include_router(router)


@app.get("/actuator/health")
async def actuator_health() -> dict:
    return {"status": "UP"}
