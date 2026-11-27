import asyncio
import re
from typing import Any

from app.db.postgres import _connect
from app.rag.embeddings import embed_text
from app.schemas import Locale


def _vector_search_sync(vec_literal: str, lang: Locale, top_k: int) -> list[dict[str, Any]]:
    with _connect() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT source_type, source_id, lang, content, metadata,
                       1 - (embedding <=> %s::vector) AS score
                FROM ai_service.knowledge_chunks
                WHERE lang = %s AND embedding IS NOT NULL
                ORDER BY embedding <=> %s::vector ASC
                LIMIT %s
                """,
                (vec_literal, lang, vec_literal, top_k),
            )
            return list(cur.fetchall())


def _keyword_search_sync(tokens: list[str], lang: Locale, top_k: int) -> list[dict[str, Any]]:
    if not tokens:
        return []
    conditions = " OR ".join("content ILIKE %s" for _ in tokens)
    score_expr = " + ".join(f"(CASE WHEN content ILIKE %s THEN 1 ELSE 0 END)" for _ in tokens)
    patterns = [f"%{tok}%" for tok in tokens]
    params = tuple(patterns) + (lang,) + tuple(patterns) + (top_k,)
    with _connect() as conn:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                SELECT source_type, source_id, lang, content, metadata,
                       ({score_expr})::float AS score
                FROM ai_service.knowledge_chunks
                WHERE lang = %s AND ({conditions})
                ORDER BY score DESC, id ASC
                LIMIT %s
                """,
                params,
            )
            return list(cur.fetchall())


async def retrieve_knowledge(query: str, lang: Locale, top_k: int = 5) -> list[dict[str, Any]]:
    query_vec = await embed_text(query, task_type="RETRIEVAL_QUERY")
    if query_vec is not None:
        vec_lit = "[" + ",".join(f"{float(v):.6f}" for v in query_vec) + "]"
        rows = await asyncio.to_thread(_vector_search_sync, vec_lit, lang, top_k)
        if rows:
            return rows

    raw_tokens = re.findall(r"[0-9A-Za-zÀ-ỹ]{2,}", query.lower())
    stop_words = {"của", "cho", "với", "những", "nào", "the", "and", "for", "with", "what", "which", "clucky"}
    tokens = [tok for tok in dict.fromkeys(raw_tokens) if tok not in stop_words][:8]
    if not tokens:
        tokens = [query.strip()[:40]] if query.strip() else []
    return await asyncio.to_thread(_keyword_search_sync, tokens, lang, top_k)
