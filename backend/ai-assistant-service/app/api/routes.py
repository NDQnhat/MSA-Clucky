import uuid
from typing import Optional
from fastapi import APIRouter, Depends, Header, Query, Request

from app.config import get_settings
from app.db.postgres import db_health
from app.llm.orchestrator import clear_history, handle_chat, load_history
from app.rag.indexer import run_reindex
from app.schemas import ChatRequest, ChatResponse, HistoryResponse, Locale, ReindexResponse
from app.security.auth import Actor, require_admin_actor, resolve_optional_actor
from app.security.rate_limit import enforce_rate_limit

router = APIRouter(prefix="/api/assistant", tags=["assistant"])


def _resolve_locale(req_locale: Optional[Locale], accept_language: Optional[str]) -> Locale:
    if req_locale in ("vi", "en"):
        return req_locale
    if accept_language and accept_language.strip().lower().startswith("en"):
        return "en"
    return "vi"


def _resolve_client_key(request: Request, x_guest_session_id: Optional[str], x_clucky_client_ip: Optional[str]) -> str:
    if x_guest_session_id and x_guest_session_id.strip():
        return x_guest_session_id.strip()[:64]
    if x_clucky_client_ip and x_clucky_client_ip.strip():
        return x_clucky_client_ip.strip()[:45]
    if request.client and request.client.host:
        return request.client.host
    return "anonymous"


@router.get("/health")
async def health() -> dict:
    settings = get_settings()
    try:
        db = await db_health()
        db_ok = True
    except Exception as exc:
        db = {"error": str(exc)}
        db_ok = False
    return {
        "status": "UP" if db_ok else "DEGRADED",
        "llmConfigured": settings.llm_configured,
        "llmProvider": settings.llm_provider,
        **db,
    }


@router.post("/chat", response_model=ChatResponse)
async def chat(
    body: ChatRequest,
    request: Request,
    actor: Actor = Depends(resolve_optional_actor),
    accept_language: Optional[str] = Header(default=None),
    x_guest_session_id: Optional[str] = Header(default=None),
    x_clucky_client_ip: Optional[str] = Header(default=None),
) -> ChatResponse:
    client_key = _resolve_client_key(request, x_guest_session_id, x_clucky_client_ip)
    await enforce_rate_limit(actor, client_key)

    locale = _resolve_locale(body.locale, accept_language)
    session_id = (body.sessionId or "").strip() or str(uuid.uuid4())
    return await handle_chat(
        message=body.message,
        locale=locale,
        session_id=session_id,
        store_id=body.storeId,
        actor=actor,
        client_key=client_key,
    )


@router.get("/history", response_model=HistoryResponse)
async def get_history(
    request: Request,
    sessionId: str = Query(..., min_length=1, max_length=128),
    actor: Actor = Depends(resolve_optional_actor),
    x_guest_session_id: Optional[str] = Header(default=None),
    x_clucky_client_ip: Optional[str] = Header(default=None),
) -> HistoryResponse:
    client_key = _resolve_client_key(request, x_guest_session_id, x_clucky_client_ip)
    messages = await load_history(actor, client_key, sessionId.strip())
    return HistoryResponse(sessionId=sessionId.strip(), messages=messages)


@router.delete("/history")
async def delete_history(
    request: Request,
    sessionId: str = Query(..., min_length=1, max_length=128),
    actor: Actor = Depends(resolve_optional_actor),
    x_guest_session_id: Optional[str] = Header(default=None),
    x_clucky_client_ip: Optional[str] = Header(default=None),
) -> dict:
    client_key = _resolve_client_key(request, x_guest_session_id, x_clucky_client_ip)
    await clear_history(actor, client_key, sessionId.strip())
    return {"cleared": True}


@router.post("/admin/reindex", response_model=ReindexResponse)
async def admin_reindex(
    force: bool = Query(default=False),
    _: Actor = Depends(require_admin_actor),
) -> ReindexResponse:
    return await run_reindex(force=force)
