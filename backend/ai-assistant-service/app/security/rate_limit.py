import time
from fastapi import HTTPException, status

from app.config import get_settings
from app.security.auth import Actor, get_redis


async def enforce_rate_limit(actor: Actor, client_key: str) -> None:
    settings = get_settings()
    limit = settings.rate_limit_user_per_min if actor.is_authenticated else settings.rate_limit_guest_per_min
    identity = f"user:{actor.user_id}" if actor.is_authenticated else f"guest:{client_key}"
    bucket = int(time.time() // 60)
    redis_key = f"ai:ratelimit:{identity}:{bucket}"

    try:
        r = get_redis()
        count = await r.incr(redis_key)
        if count == 1:
            await r.expire(redis_key, 90)
        if count > limit:
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail="Too many assistant requests. Please try again in a minute.",
            )
    except HTTPException:
        raise
    except Exception:
        # Fail-open only if Redis is transiently unreachable, never blocking valid chat during recovery.
        return
