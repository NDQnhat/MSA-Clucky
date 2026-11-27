import logging
import unicodedata
from typing import Any, Optional

import httpx

from app.config import get_settings
from app.schemas import Locale, ProductCardDto
from app.security.auth import Actor
from app.security.guardrails import sanitize_untrusted_text

logger = logging.getLogger("clucky.ai.tools")


def _norm(text: Optional[str]) -> str:
    if not text:
        return ""
    decomposed = unicodedata.normalize("NFD", text.replace("đ", "d").replace("Đ", "D"))
    stripped = "".join(ch for ch in decomposed if unicodedata.category(ch) != "Mn")
    return stripped.lower().strip()


def _to_product_card(
    item: dict[str, Any],
    item_type: str = "PRODUCT",
    store_id: Optional[int] = None,
    quantity_sold: Optional[int] = None,
) -> ProductCardDto:
    item_id = int(item.get("id") or item.get("itemRefId") or 0)
    if item_type == "COMBO":
        slug = f"combo-{item_id}"
        price = int(float(item.get("comboPrice") or item.get("price") or item.get("basePrice") or 0))
    else:
        slug = str(item.get("slug") or item_id)
        price = int(float(item.get("price") or item.get("basePrice") or 0))
    return ProductCardDto(
        id=item_id,
        itemType="COMBO" if item_type == "COMBO" else "PRODUCT",
        slug=slug,
        name=str(item.get("name") or f"#{item_id}"),
        price=price,
        imageUrl=item.get("imageUrl") or item.get("image"),
        isSoldOut=bool(item.get("isSoldOut", False)) or (item.get("isAvailable") is False),
        kcal=item.get("kcal"),
        spicyLevel=item.get("spicyLevel"),
        tags=list(item.get("tags") or []),
        rating=item.get("rating"),
        reviewCount=item.get("reviewCount"),
        quantitySold=quantity_sold,
        storeId=store_id,
    )


async def get_store_best_sellers(
    store_id: Optional[int] = None,
    days: int = 30,
    limit: int = 5,
    lang: Locale = "vi",
) -> tuple[dict[str, Any], list[ProductCardDto]]:
    settings = get_settings()
    days = max(1, min(int(days or 30), 90))
    limit = max(1, min(int(limit or 5), 15))
    params: dict[str, Any] = {"days": days, "limit": limit, "lang": lang}
    if store_id is not None and store_id > 0:
        params["storeId"] = store_id

    timeout = httpx.Timeout(settings.http_timeout_seconds)
    async with httpx.AsyncClient(timeout=timeout) as client:
        bs_resp = await client.get(f"{settings.review_report_service_url}/api/reports/public/best-sellers", params=params)
        bs_resp.raise_for_status()
        best_rows = bs_resp.json()

        cat_params: dict[str, Any] = {"activeOnly": "true", "lang": lang}
        if store_id is not None and store_id > 0:
            cat_params["storeId"] = store_id
        prod_resp = await client.get(f"{settings.catalog_service_url}/api/catalog/products", params=cat_params)
        combo_resp = await client.get(f"{settings.catalog_service_url}/api/catalog/combos", params=cat_params)
        products = prod_resp.json() if prod_resp.status_code == 200 else []
        combos = combo_resp.json() if combo_resp.status_code == 200 else []

    if isinstance(products, dict):
        products = products.get("content", [])
    prod_map = {int(p["id"]): p for p in products if p.get("id") is not None}
    combo_map = {int(c["id"]): c for c in combos if c.get("id") is not None}

    items: list[dict[str, Any]] = []
    cards: list[ProductCardDto] = []
    for row in best_rows:
        item_type = str(row.get("itemType") or "PRODUCT").upper()
        ref_id = int(row["itemRefId"])
        qty = int(row.get("quantitySold") or 0)
        catalog_item = combo_map.get(ref_id) if item_type == "COMBO" else prod_map.get(ref_id)
        if catalog_item:
            card = _to_product_card(catalog_item, item_type=item_type, store_id=store_id, quantity_sold=qty)
            cards.append(card)
            items.append({
                "itemType": item_type,
                "id": ref_id,
                "name": card.name,
                "price": card.price,
                "isSoldOut": card.isSoldOut,
                "quantitySold": qty,
                "slug": card.slug,
            })
        else:
            items.append({
                "itemType": item_type,
                "id": ref_id,
                "name": row.get("itemName"),
                "quantitySold": qty,
            })

    return {"storeId": store_id, "days": days, "bestSellers": items}, cards


async def query_menu_items(
    store_id: Optional[int] = None,
    category_id: Optional[int] = None,
    sort_by: Optional[str] = None,
    max_price: Optional[int] = None,
    min_price: Optional[int] = None,
    max_spicy: Optional[int] = None,
    max_kcal: Optional[int] = None,
    tag: Optional[str] = None,
    keyword: Optional[str] = None,
    include_combos: bool = True,
    limit: int = 8,
    lang: Locale = "vi",
) -> tuple[dict[str, Any], list[ProductCardDto]]:
    settings = get_settings()
    limit = max(1, min(int(limit or 8), 20))
    params: dict[str, Any] = {"activeOnly": "true", "lang": lang}
    if store_id is not None and store_id > 0:
        params["storeId"] = store_id
    if category_id is not None and category_id > 0:
        params["categoryId"] = category_id
    if tag:
        params["tag"] = tag.strip().lower()

    timeout = httpx.Timeout(settings.http_timeout_seconds)
    async with httpx.AsyncClient(timeout=timeout) as client:
        prod_resp = await client.get(f"{settings.catalog_service_url}/api/catalog/products", params=params)
        prod_resp.raise_for_status()
        raw_products = prod_resp.json()
        if isinstance(raw_products, dict):
            raw_products = raw_products.get("content", [])

        combos: list[dict[str, Any]] = []
        if include_combos and category_id is None and not tag and max_spicy is None and max_kcal is None:
            combo_params: dict[str, Any] = {"activeOnly": "true", "lang": lang}
            if store_id is not None and store_id > 0:
                combo_params["storeId"] = store_id
            combo_resp = await client.get(f"{settings.catalog_service_url}/api/catalog/combos", params=combo_params)
            if combo_resp.status_code == 200:
                combos = combo_resp.json()

    candidates: list[tuple[dict[str, Any], str]] = [(p, "PRODUCT") for p in raw_products]
    candidates.extend((c, "COMBO") for c in combos)

    kw_norm = _norm(keyword)
    tag_norm = _norm(tag)
    filtered: list[dict[str, Any]] = []
    cards: list[ProductCardDto] = []

    for item, item_type in candidates:
        price = int(float(item.get("comboPrice") if item_type == "COMBO" else (item.get("price") or item.get("basePrice") or 0)))
        if max_price is not None and price > max_price:
            continue
        if min_price is not None and price < min_price:
            continue
        spicy = item.get("spicyLevel")
        if max_spicy is not None and item_type == "PRODUCT" and spicy is not None and int(spicy) > max_spicy:
            continue
        kcal = item.get("kcal")
        if max_kcal is not None and item_type == "PRODUCT" and kcal is not None and int(kcal) > max_kcal:
            continue
        if tag_norm:
            tags_lower = [_norm(t) for t in (item.get("tags") or [])]
            if tag_norm not in tags_lower:
                continue
        if kw_norm:
            haystack = " ".join([
                _norm(item.get("name")),
                _norm(item.get("description")),
                _norm(item.get("categoryName")),
                " ".join(_norm(t) for t in (item.get("tags") or [])),
            ])
            if kw_norm not in haystack:
                continue

        card = _to_product_card(item, item_type=item_type, store_id=store_id)
        cards.append(card)
        filtered.append({
            "id": card.id,
            "itemType": card.itemType,
            "slug": card.slug,
            "name": card.name,
            "categoryName": item.get("categoryName") or ("Combo" if item_type == "COMBO" else ""),
            "description": sanitize_untrusted_text(str(item.get("description") or ""), max_len=200),
            "price": card.price,
            "isSoldOut": card.isSoldOut,
            "kcal": card.kcal,
            "spicyLevel": card.spicyLevel,
            "tags": card.tags,
        })

    if sort_by == "price_asc":
        filtered.sort(key=lambda x: (x["isSoldOut"], x["price"], x["id"]))
        cards.sort(key=lambda x: (x.isSoldOut, x.price, x.id))
    elif sort_by == "price_desc":
        filtered.sort(key=lambda x: (x["isSoldOut"], -x["price"], x["id"]))
        cards.sort(key=lambda x: (x.isSoldOut, -x.price, x.id))
    elif sort_by == "kcal_asc":
        filtered.sort(key=lambda x: (x["kcal"] is None, x["kcal"] or 99999, x["price"]))
        cards.sort(key=lambda x: (x.kcal is None, x.kcal or 99999, x.price))

    return {
        "storeId": store_id,
        "totalMatched": len(filtered),
        "items": filtered[:limit],
    }, cards[:4]


async def get_product_highlights_and_reviews(
    product_id: int,
    lang: Locale = "vi",
) -> tuple[dict[str, Any], list[ProductCardDto]]:
    settings = get_settings()
    timeout = httpx.Timeout(settings.http_timeout_seconds)
    async with httpx.AsyncClient(timeout=timeout) as client:
        hi_resp = await client.get(f"{settings.review_report_service_url}/api/reports/public/highlights")
        rev_resp = await client.get(f"{settings.review_report_service_url}/api/reviews/products/{int(product_id)}")

    highlights = hi_resp.json() if hi_resp.status_code == 200 else {}
    ratings_map = highlights.get("ratings") or {}
    rating_info = ratings_map.get(str(product_id)) or ratings_map.get(product_id) or {}

    reviews_payload = rev_resp.json() if rev_resp.status_code == 200 else {}
    raw_reviews = reviews_payload.get("reviews") or reviews_payload.get("items") or []
    safe_reviews = []
    for rv in raw_reviews[:5]:
        safe_reviews.append({
            "rating": rv.get("rating"),
            "comment": sanitize_untrusted_text(str(rv.get("comment") or ""), max_len=240),
            "createdAt": rv.get("createdAt"),
        })

    return {
        "productId": int(product_id),
        "averageRating": reviews_payload.get("averageRating", rating_info.get("averageRating")),
        "reviewCount": reviews_payload.get("totalReviews", rating_info.get("reviewCount", 0)),
        "recentReviews": safe_reviews,
    }, []


async def get_active_promotions(
    store_id: Optional[int] = None,
    lang: Locale = "vi",
) -> tuple[dict[str, Any], list[ProductCardDto]]:
    settings = get_settings()
    params: dict[str, Any] = {"lang": lang}
    if store_id is not None and store_id > 0:
        params["storeId"] = store_id

    timeout = httpx.Timeout(settings.http_timeout_seconds)
    async with httpx.AsyncClient(timeout=timeout) as client:
        resp = await client.get(
            f"{settings.promotion_service_url}/api/promotions/vouchers/available",
            params=params,
        )
        resp.raise_for_status()
        vouchers = resp.json()

    public_list = []
    for v in vouchers:
        code = v.get("code")
        if code and str(code).upper().startswith("CKP1-"):
            continue
        public_list.append({
            "id": v.get("id"),
            "code": code,
            "campaignKind": v.get("campaignKind"),
            "title": sanitize_untrusted_text(str(v.get("title") or ""), max_len=160),
            "description": sanitize_untrusted_text(str(v.get("description") or ""), max_len=240),
            "discountType": v.get("discountType"),
            "discountValue": v.get("discountValue"),
            "minOrderAmount": v.get("minOrderAmount"),
            "maxDiscountAmount": v.get("maxDiscountAmount"),
            "endAt": v.get("endAt"),
        })

    return {"storeId": store_id, "promotions": public_list[:10]}, []


async def find_stores(
    keyword: Optional[str] = None,
    district: Optional[str] = None,
    open_only: bool = True,
    lang: Locale = "vi",
) -> tuple[dict[str, Any], list[ProductCardDto]]:
    settings = get_settings()
    params: dict[str, Any] = {"activeOnly": "true" if open_only else "false", "lang": lang}
    timeout = httpx.Timeout(settings.http_timeout_seconds)
    async with httpx.AsyncClient(timeout=timeout) as client:
        resp = await client.get(f"{settings.catalog_service_url}/api/stores", params=params)
        resp.raise_for_status()
        stores = resp.json()

    kw_norm = _norm(keyword)
    dist_norm = _norm(district)
    matched = []
    for s in stores:
        if open_only and s.get("status") != "OPEN":
            continue
        haystack = " ".join([
            _norm(s.get("name")),
            _norm(s.get("address")),
            _norm(s.get("district")),
            _norm(s.get("city")),
            _norm(s.get("deliveryArea")),
        ])
        if dist_norm and dist_norm not in haystack:
            continue
        if kw_norm and kw_norm not in haystack:
            continue
        matched.append({
            "id": s.get("id"),
            "name": s.get("name"),
            "address": s.get("address"),
            "district": s.get("district"),
            "city": s.get("city"),
            "phone": s.get("phone"),
            "openTime": s.get("openTime"),
            "closeTime": s.get("closeTime"),
            "status": s.get("status"),
            "isOpenNow": s.get("isOpenNow"),
            "deliveryRadiusKm": s.get("deliveryRadiusKm"),
            "deliveryArea": s.get("deliveryArea"),
        })

    return {"stores": matched[:10]}, []


async def get_my_recent_orders(
    actor: Actor,
    limit: int = 5,
    lang: Locale = "vi",
) -> tuple[dict[str, Any], list[ProductCardDto]]:
    if not actor.is_authenticated or not actor.raw_token:
        return {
            "authenticated": False,
            "error": "CUSTOMER_LOGIN_REQUIRED",
        }, []
    if actor.role != "CUSTOMER":
        return {
            "authenticated": True,
            "error": "ONLY_CUSTOMER_ORDERS_SUPPORTED",
        }, []

    settings = get_settings()
    limit = max(1, min(int(limit or 5), 10))
    timeout = httpx.Timeout(settings.http_timeout_seconds)
    async with httpx.AsyncClient(timeout=timeout) as client:
        resp = await client.get(
            f"{settings.cart_order_service_url}/api/orders/my",
            params={"page": 0, "size": limit},
            headers={
                "Authorization": f"Bearer {actor.raw_token}",
                "Accept-Language": lang,
            },
        )
        if resp.status_code in (401, 403):
            return {"authenticated": False, "error": "UNAUTHORIZED"}, []
        resp.raise_for_status()
        page = resp.json()

    orders_summary = []
    for o in page.get("content") or []:
        items = [
            f"{it.get('quantity', 1)}x {it.get('itemName')}"
            for it in (o.get("items") or [])
        ]
        orders_summary.append({
            "orderCode": o.get("orderCode"),
            "storeId": o.get("storeId"),
            "type": o.get("type"),
            "status": o.get("status"),
            "paymentMethod": o.get("paymentMethod"),
            "paymentStatus": o.get("paymentStatus"),
            "totalAmount": o.get("totalAmount"),
            "createdAt": o.get("createdAt"),
            "items": items,
        })

    return {"authenticated": True, "orders": orders_summary}, []
