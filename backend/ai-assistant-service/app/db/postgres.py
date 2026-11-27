import asyncio
import logging
from pathlib import Path
from typing import Any

import psycopg
from psycopg.rows import dict_row

from app.config import get_settings

logger = logging.getLogger("clucky.ai.db")
SQL_INIT_PATH = Path(__file__).resolve().parent.parent.parent / "sql" / "init_ai_service.sql"


def _connect():
    settings = get_settings()
    return psycopg.connect(
        host=settings.postgres_host,
        port=settings.postgres_port,
        user=settings.postgres_user,
        password=settings.postgres_password,
        dbname=settings.db_name,
        row_factory=dict_row,
        connect_timeout=5,
    )


def bootstrap_schema_sync() -> None:
    sql_text = SQL_INIT_PATH.read_text(encoding="utf-8")
    with _connect() as conn:
        with conn.cursor() as cur:
            cur.execute(sql_text)
        conn.commit()
    logger.info("Bootstrapped ai_service schema and pgvector extension.")


async def bootstrap_schema() -> None:
    await asyncio.to_thread(bootstrap_schema_sync)


def db_health_sync() -> dict[str, Any]:
    with _connect() as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT extversion FROM pg_extension WHERE extname = 'vector'")
            ext_row = cur.fetchone()
            cur.execute("SELECT COUNT(*) AS cnt FROM ai_service.knowledge_chunks")
            chunk_row = cur.fetchone()
            cur.execute("SELECT COUNT(*) AS cnt FROM ai_service.knowledge_chunks WHERE embedding IS NOT NULL")
            vec_row = cur.fetchone()
    return {
        "pgvectorVersion": ext_row["extversion"] if ext_row else None,
        "indexedChunks": int(chunk_row["cnt"]) if chunk_row else 0,
        "embeddedChunks": int(vec_row["cnt"]) if vec_row else 0,
    }


async def db_health() -> dict[str, Any]:
    return await asyncio.to_thread(db_health_sync)


def execute_sync(query: str, params: tuple[Any, ...] = ()) -> list[dict[str, Any]]:
    with _connect() as conn:
        with conn.cursor() as cur:
            cur.execute(query, params)
            rows = cur.fetchall() if cur.description else []
        conn.commit()
    return list(rows)


async def execute(query: str, params: tuple[Any, ...] = ()) -> list[dict[str, Any]]:
    return await asyncio.to_thread(execute_sync, query, params)
