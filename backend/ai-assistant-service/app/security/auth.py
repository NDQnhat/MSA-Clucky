from dataclasses import dataclass
from typing import Optional
import jwt
from fastapi import Header, HTTPException, status
import redis.asyncio as redis_async

from app.config import get_settings

VALID_ROLES = {"CUSTOMER", "STAFF", "STORE_MANAGER", "ADMIN"}
_redis_client: Optional[redis_async.Redis] = None


def get_redis() -> redis_async.Redis:
    global _redis_client
    if _redis_client is None:
        settings = get_settings()
        _redis_client = redis_async.Redis(
            host=settings.redis_host,
            port=settings.redis_port,
            password=settings.redis_password or None,
            decode_responses=True,
            socket_connect_timeout=2,
            socket_timeout=2,
        )
    return _redis_client


@dataclass(frozen=True)
class Actor:
    user_id: Optional[int]
    role: str
    store_id: Optional[int]
    raw_token: Optional[str] = None

    @property
    def is_authenticated(self) -> bool:
        return self.user_id is not None and self.role != "GUEST"


GUEST_ACTOR = Actor(user_id=None, role="GUEST", store_id=None, raw_token=None)


async def _check_session_not_revoked(claims: dict) -> None:
    sid = claims.get("sid")
    iat = claims.get("iat")
    sub = claims.get("sub")
    if not sid or not isinstance(sid, str) or not sid.strip() or iat is None or sub is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing session claims")

    issued_ns = claims.get("issuedAtNs")
    if isinstance(issued_ns, (int, float)):
        issued_ns_val = int(issued_ns)
    else:
        issued_ns_val = int(float(iat) * 1_000_000_000)
    issued_ms_val = int(float(iat) * 1000)

    r = get_redis()
    try:
        sid_revoked, user_ms_raw, user_ns_raw = await r.mget(
            f"auth:revoked:sid:{sid}",
            f"auth:revoked:user:{sub}",
            f"auth:revoked:user-ns:{sub}",
        )
    except Exception as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Session validation failed") from exc

    if sid_revoked is not None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Revoked session")
    if user_ns_raw and user_ns_raw != "0":
        if issued_ns_val <= int(user_ns_raw):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Revoked session")
    elif user_ms_raw and user_ms_raw != "0":
        if issued_ms_val <= int(user_ms_raw):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Revoked session")


async def resolve_optional_actor(authorization: Optional[str] = Header(default=None)) -> Actor:
    if not authorization:
        return GUEST_ACTOR
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid authorization header")

    token = authorization[7:].strip()
    if not token:
        return GUEST_ACTOR

    settings = get_settings()
    try:
        claims = jwt.decode(
            token,
            settings.jwt_secret,
            algorithms=["HS256"],
            audience=settings.jwt_audience,
            issuer=settings.jwt_issuer,
            options={"require": ["sub", "exp", "iat"]},
        )
    except jwt.PyJWTError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid access token") from exc

    if str(claims.get("typ", "")).lower() == "refresh":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Refresh token cannot access resources")

    role = claims.get("role")
    if role not in VALID_ROLES:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid role claim")

    try:
        user_id = int(claims.get("sub"))
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid subject claim") from exc
    if user_id <= 0:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid subject claim")

    store_raw = claims.get("storeId")
    store_id: Optional[int] = None
    if store_raw is not None:
        try:
            store_id = int(store_raw)
        except (TypeError, ValueError) as exc:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid storeId claim") from exc
        if store_id <= 0:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid storeId claim")

    if role in {"STAFF", "STORE_MANAGER"} and store_id is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing storeId claim")

    await _check_session_not_revoked(claims)
    return Actor(user_id=user_id, role=role, store_id=store_id, raw_token=token)


async def require_admin_actor(authorization: Optional[str] = Header(default=None)) -> Actor:
    if not authorization:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Authentication required")
    actor = await resolve_optional_actor(authorization)
    if not actor.is_authenticated:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Authentication required")
    if actor.role != "ADMIN":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin role required")
    return actor
