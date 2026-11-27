import asyncio
import hashlib
import json
import logging
from html.parser import HTMLParser
from pathlib import Path
from typing import Any, Optional

import httpx

from app.config import get_settings
from app.db.postgres import _connect
from app.rag.embeddings import embed_text
from app.schemas import ReindexResponse

logger = logging.getLogger("clucky.ai.indexer")
KNOWLEDGE_DIR = Path(__file__).resolve().parent.parent.parent / "knowledge"


class _HtmlStripper(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self._parts: list[str] = []

    def handle_data(self, data: str) -> None:
        if data:
            self._parts.append(data)

    def handle_starttag(self, tag: str, attrs: list[tuple[str, Optional[str]]]) -> None:
        if tag in {"p", "br", "li", "h1", "h2", "h3", "h4", "div", "tr"}:
            self._parts.append("\n")

    def text(self) -> str:
        lines = [" ".join(line.split()) for line in "".join(self._parts).splitlines()]
        return "\n".join(line for line in lines if line)


def strip_html(html: str) -> str:
    stripper = _HtmlStripper()
    stripper.feed(html or "")
    stripper.close()
    return stripper.text()


def chunk_text(text: str, max_chars: int = 900, overlap: int = 120) -> list[str]:
    clean = (text or "").strip()
    if not clean:
        return []
    if len(clean) <= max_chars:
        return [clean]
    chunks: list[str] = []
    start = 0
    while start < len(clean):
        end = min(len(clean), start + max_chars)
        if end < len(clean):
            split_pos = clean.rfind("\n", start + max_chars // 2, end)
            if split_pos == -1:
                split_pos = clean.rfind(" ", start + max_chars // 2, end)
            if split_pos > start:
                end = split_pos
        part = clean[start:end].strip()
        if part:
            chunks.append(part)
        if end >= len(clean):
            break
        start = max(start + 1, end - overlap)
    return chunks


def _vector_literal(vec: Optional[list[float]]) -> Optional[str]:
    if not vec:
        return None
    return "[" + ",".join(f"{float(v):.6f}" for v in vec) + "]"


def _upsert_document_sync(
    source_type: str,
    source_id: str,
    lang: str,
    title: str,
    content_hash: str,
    metadata: dict[str, Any],
    chunks_with_vecs: list[tuple[str, Optional[list[float]]]],
) -> tuple[bool, int]:
    with _connect() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, content_hash
                FROM ai_service.knowledge_documents
                WHERE source_type = %s AND source_id = %s AND lang = %s
                """,
                (source_type, source_id, lang),
            )
            existing = cur.fetchone()
            if existing and existing["content_hash"] == content_hash:
                cur.execute(
                    "SELECT COUNT(*) AS cnt, COUNT(embedding) AS vec_cnt FROM ai_service.knowledge_chunks WHERE document_id = %s",
                    (existing["id"],),
                )
                counts = cur.fetchone()
                has_vecs_needed = any(v is not None for _, v in chunks_with_vecs)
                if counts and counts["cnt"] > 0 and (not has_vecs_needed or counts["vec_cnt"] > 0):
                    return False, 0

            cur.execute(
                """
                INSERT INTO ai_service.knowledge_documents (source_type, source_id, lang, content_hash, title, metadata, updated_at)
                VALUES (%s, %s, %s, %s, %s, %s::jsonb, now())
                ON CONFLICT (source_type, source_id, lang)
                DO UPDATE SET content_hash = EXCLUDED.content_hash,
                              title = EXCLUDED.title,
                              metadata = EXCLUDED.metadata,
                              updated_at = now()
                RETURNING id
                """,
                (source_type, source_id, lang, content_hash, title, json.dumps(metadata, ensure_ascii=False)),
            )
            doc_id = cur.fetchone()["id"]
            cur.execute("DELETE FROM ai_service.knowledge_chunks WHERE document_id = %s", (doc_id,))

            for idx, (chunk_body, vec) in enumerate(chunks_with_vecs):
                vec_lit = _vector_literal(vec)
                cur.execute(
                    """
                    INSERT INTO ai_service.knowledge_chunks
                        (document_id, chunk_index, source_type, source_id, lang, content, embedding, metadata)
                    VALUES (%s, %s, %s, %s, %s, %s, %s::vector, %s::jsonb)
                    """,
                    (
                        doc_id,
                        idx,
                        source_type,
                        source_id,
                        lang,
                        chunk_body,
                        vec_lit,
                        json.dumps(metadata, ensure_ascii=False),
                    ),
                )
        conn.commit()
    return True, len(chunks_with_vecs)


async def _index_single_document(
    source_type: str,
    source_id: str,
    lang: str,
    title: str,
    body: str,
    metadata: dict[str, Any],
    force: bool = False,
) -> tuple[bool, int, int]:
    digest = hashlib.sha256(f"{title}\n{body}\n{json.dumps(metadata, sort_keys=True)}".encode("utf-8")).hexdigest()
    if force:
        digest = hashlib.sha256((digest + ":force").encode("utf-8")).hexdigest()[:64]
    chunks = chunk_text(body)
    if not chunks:
        return False, 0, 0

    chunks_with_vecs: list[tuple[str, Optional[list[float]]]] = []
    embedded_count = 0
    for c in chunks:
        vec = await embed_text(c, task_type="RETRIEVAL_DOCUMENT")
        if vec is not None:
            embedded_count += 1
        chunks_with_vecs.append((c, vec))

    updated, inserted_chunks = await asyncio.to_thread(
        _upsert_document_sync, source_type, source_id, lang, title, digest, metadata, chunks_with_vecs
    )
    return updated, inserted_chunks, embedded_count if updated else 0


async def _collect_knowledge_base_docs() -> list[dict[str, Any]]:
    docs: list[dict[str, Any]] = []
    for lang in ("vi", "en"):
        lang_dir = KNOWLEDGE_DIR / lang
        if not lang_dir.exists():
            continue
        for md_file in sorted(lang_dir.glob("*.md")):
            text = md_file.read_text(encoding="utf-8").strip()
            if not text:
                continue
            first_line = text.splitlines()[0].lstrip("# ").strip()
            docs.append({
                "source_type": "KNOWLEDGE_BASE",
                "source_id": md_file.stem,
                "lang": lang,
                "title": first_line or md_file.stem,
                "body": text,
                "metadata": {"file": md_file.name},
            })
    return docs


async def _collect_menu_docs(skipped: list[str]) -> list[dict[str, Any]]:
    settings = get_settings()
    docs: list[dict[str, Any]] = []
    timeout = httpx.Timeout(settings.http_timeout_seconds)

    async with httpx.AsyncClient(timeout=timeout) as client:
        for lang in ("vi", "en"):
            try:
                prod_resp = await client.get(
                    f"{settings.catalog_service_url}/api/catalog/products",
                    params={"activeOnly": "true", "lang": lang},
                )
                prod_resp.raise_for_status()
                products = prod_resp.json()
                if isinstance(products, dict):
                    products = products.get("content", [])
                for p in products:
                    tags = ", ".join(p.get("tags") or [])
                    opts = "; ".join(
                        f"{g.get('name')}: {', '.join(c.get('name', '') for c in (g.get('choices') or []))}"
                        for g in (p.get("options") or [])
                    )
                    tops = ", ".join(
                        f"{t.get('name')} (+{t.get('price', 0)} VND)" for t in (p.get("toppings") or [])
                    )
                    if lang == "vi":
                        body = (
                            f"Món ăn Clucky: {p.get('name')}\n"
                            f"Danh mục: {p.get('categoryName')}\n"
                            f"Mô tả: {p.get('description')}\n"
                            f"Giá gốc: {p.get('basePrice')} VND | Calo: {p.get('kcal')} kcal | Độ cay: {p.get('spicyLevel')}/3\n"
                            f"Tags: {tags or 'không'}\n"
                            f"Tùy chọn: {opts or 'không'}\n"
                            f"Topping thêm: {tops or 'không'}"
                        )
                    else:
                        body = (
                            f"Clucky Dish: {p.get('name')}\n"
                            f"Category: {p.get('categoryName')}\n"
                            f"Description: {p.get('description')}\n"
                            f"Base price: {p.get('basePrice')} VND | Calories: {p.get('kcal')} kcal | Spice level: {p.get('spicyLevel')}/3\n"
                            f"Tags: {tags or 'none'}\n"
                            f"Options: {opts or 'none'}\n"
                            f"Toppings: {tops or 'none'}"
                        )
                    docs.append({
                        "source_type": "MENU_PRODUCT",
                        "source_id": str(p.get("id")),
                        "lang": lang,
                        "title": str(p.get("name") or f"Product #{p.get('id')}"),
                        "body": body,
                        "metadata": {"productId": p.get("id"), "slug": p.get("slug"), "tags": p.get("tags") or []},
                    })
            except Exception as exc:
                skipped.append(f"catalog_products_{lang}: {exc}")

            try:
                combo_resp = await client.get(
                    f"{settings.catalog_service_url}/api/catalog/combos",
                    params={"activeOnly": "true", "lang": lang},
                )
                combo_resp.raise_for_status()
                for c in combo_resp.json():
                    parts = ", ".join(
                        f"{it.get('quantity', 1)}x {it.get('productName')}" for it in (c.get("items") or [])
                    )
                    if lang == "vi":
                        body = (
                            f"Combo Clucky: {c.get('name')}\n"
                            f"Mô tả: {c.get('description')}\n"
                            f"Giá combo: {c.get('comboPrice')} VND\n"
                            f"Gồm các món: {parts}"
                        )
                    else:
                        body = (
                            f"Clucky Combo: {c.get('name')}\n"
                            f"Description: {c.get('description')}\n"
                            f"Combo price: {c.get('comboPrice')} VND\n"
                            f"Includes: {parts}"
                        )
                    docs.append({
                        "source_type": "MENU_COMBO",
                        "source_id": str(c.get("id")),
                        "lang": lang,
                        "title": str(c.get("name") or f"Combo #{c.get('id')}"),
                        "body": body,
                        "metadata": {"comboId": c.get("id"), "slug": f"combo-{c.get('id')}"},
                    })
            except Exception as exc:
                skipped.append(f"catalog_combos_{lang}: {exc}")
    return docs


async def _collect_legal_docs(skipped: list[str]) -> list[dict[str, Any]]:
    settings = get_settings()
    docs: list[dict[str, Any]] = []
    timeout = httpx.Timeout(settings.http_timeout_seconds)

    async with httpx.AsyncClient(timeout=timeout) as client:
        for doc_type in ("TERMS_OF_SERVICE", "PRIVACY_POLICY"):
            try:
                resp = await client.get(f"{settings.identity_service_url}/api/legal/public/{doc_type}")
                if resp.status_code == 404:
                    skipped.append(f"legal_{doc_type}: not_published")
                    continue
                resp.raise_for_status()
                payload = resp.json()
                for lang, title_key, html_key in (("vi", "titleVi", "htmlVi"), ("en", "titleEn", "htmlEn")):
                    title = payload.get(title_key) or doc_type
                    plain = strip_html(payload.get(html_key) or "")
                    if plain:
                        docs.append({
                            "source_type": "LEGAL",
                            "source_id": doc_type,
                            "lang": lang,
                            "title": title,
                            "body": f"{title}\n\n{plain}",
                            "metadata": {"version": payload.get("version"), "publishedAt": payload.get("publishedAt")},
                        })
            except Exception as exc:
                skipped.append(f"legal_{doc_type}: {exc}")
    return docs


async def run_reindex(force: bool = False) -> ReindexResponse:
    skipped: list[str] = []
    all_docs: list[dict[str, Any]] = []
    all_docs.extend(await _collect_knowledge_base_docs())
    all_docs.extend(await _collect_menu_docs(skipped))
    all_docs.extend(await _collect_legal_docs(skipped))

    docs_indexed = 0
    chunks_indexed = 0
    embeddings_generated = 0

    for doc in all_docs:
        updated, chunks_count, emb_count = await _index_single_document(
            source_type=doc["source_type"],
            source_id=doc["source_id"],
            lang=doc["lang"],
            title=doc["title"],
            body=doc["body"],
            metadata=doc["metadata"],
            force=force,
        )
        if updated:
            docs_indexed += 1
            chunks_indexed += chunks_count
            embeddings_generated += emb_count

    logger.info(
        "Reindex complete: docs=%d chunks=%d embeddings=%d skipped=%d",
        docs_indexed,
        chunks_indexed,
        embeddings_generated,
        len(skipped),
    )
    return ReindexResponse(
        status="OK",
        documentsIndexed=docs_indexed,
        chunksIndexed=chunks_indexed,
        embeddingsGenerated=embeddings_generated,
        skippedReasons=skipped,
    )
