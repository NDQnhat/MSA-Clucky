import re
import unicodedata
from dataclasses import dataclass
from typing import Literal, Optional

from app.config import get_settings
from app.schemas import Locale

IntentVerdict = Literal["IN_SCOPE", "GREETING", "OUT_OF_SCOPE", "INJECTION"]

REFUSAL_MESSAGES: dict[Locale, str] = {
    "vi": (
        "Xin lỗi, tôi là trợ lý đặt món của Clucky nên chỉ có thể hỗ trợ thông tin về thực đơn, "
        "combo, món bán chạy, khuyến mãi, chi nhánh, chính sách giao hàng/hoàn tiền và tra cứu đơn hàng Clucky của bạn."
    ),
    "en": (
        "Sorry, I'm the Clucky ordering assistant and can only help with Clucky's menu, combos, "
        "best sellers, promotions, branch locations, delivery/refund policies, and your Clucky orders."
    ),
}

TOO_LONG_MESSAGES: dict[Locale, str] = {
    "vi": "Tin nhắn của bạn hơi dài (tối đa 800 ký tự). Vui lòng rút gọn câu hỏi để tôi hỗ trợ nhanh hơn nhé.",
    "en": "Your message is too long (maximum 800 characters). Please shorten it so I can help you faster.",
}

_CONTROL_CHAR_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")

_INJECTION_PATTERNS = [
    re.compile(r"ignore\s+(all\s+|previous\s+|prior\s+|above\s+)?(instructions|rules|prompts?)", re.I),
    re.compile(r"disregard\s+(all\s+|previous\s+)?(instructions|rules|prompts?)", re.I),
    re.compile(r"(reveal|show|print|leak|dump|repeat)\s+(your\s+)?(system\s+prompt|hidden\s+prompt|instructions)", re.I),
    re.compile(r"\b(developer\s+mode|jailbreak|dan\s+mode)\b", re.I),
    re.compile(r"(pretend|act\s+as|roleplay)\s+(to\s+be\s+)?(a\s+)?(programmer|developer|hacker|sysadmin|sql|linux|assistant\s+without)", re.I),
    re.compile(r"</?(user_query|knowledge_context|tool_data|system_prompt)>", re.I),
    re.compile(r"bỏ\s*qua\s*(mọi|tất\s*cả|các)?\s*(chỉ\s*thị|quy\s*tắc|hướng\s*dẫn|lệnh\s*trước)", re.I),
    re.compile(r"(tiết\s*lộ|in\s*ra|xem)\s*(system\s*prompt|prompt\s*hệ\s*thống|câu\s*lệnh\s*gốc)", re.I),
    re.compile(r"(chế\s*độ\s*nhà\s*phát\s*triển|bẻ\s*luật|giả\s*sử\s*bạn\s*là\s*lập\s*trình\s*viên)", re.I),
    re.compile(r"<\s*script\b|<!doctype\s+html|<\s*html\b|javascript:|union\s+select\b|drop\s+table\b|;\s*select\b", re.I),
]

_OUT_OF_SCOPE_PATTERNS = [
    re.compile(r"(tạo|viết|làm|code)\s+(1|một)?\s*(trang|file|web|website|code|script|app)?\s*(html|css|js|javascript|python|java|react|sql|php|c\+\+)", re.I),
    re.compile(r"(write|create|build|generate|give\s+me)\s+(a\s+|an\s+)?(html|css|js|javascript|python|java|sql|react|code|script|website|webpage)", re.I),
    re.compile(r"(chỉ\s+trả\s+lời\s+bằng|respond\s+only\s+in)\s+(html|json|sql|python|code)", re.I),
    re.compile(r"(giải\s+bài\s+tập|solve\s+(this|my)\s+(math|homework|assignment|equation))", re.I),
    re.compile(r"\b(kfc|jollibee|lotteria|mcdonald|popeyes|texas\s+chicken|burger\s+king)\b", re.I),
    re.compile(r"(tư\s+vấn\s+(y\s+tế|bệnh|thuốc|chứng\s+khoán|đầu\s+tư|luật)|medical\s+advice|financial\s+advice|legal\s+advice)", re.I),
    re.compile(r"(chính\s+trị|bầu\s+cử|tôn\s+giáo|politics|election)", re.I),
]

_IN_SCOPE_KEYWORDS = (
    "clucky", "menu", "thực đơn", "món", "gà", "chicken", "burger", "combo", "trà", "tea", "nước",
    "đồ uống", "drink", "beverage", "món phụ", "fries", "khoai", "topping", "size", "kích thước",
    "giá", "price", "rẻ", "cheapest", "đắt", "expensive", "cay", "spicy", "kcal", "calo", "calorie",
    "mát", "lạnh", "cold", "mùa hè", "summer", "refreshing", "ít béo", "nhẹ", "light", "trẻ em", "kid",
    "best seller", "bán chạy", "nổi bật", "đánh giá", "review", "rating", "sao", "ngon",
    "chi nhánh", "cửa hàng", "store", "branch", "địa chỉ", "address", "giờ mở", "open", "quận", "district",
    "khuyến mãi", "ưu đãi", "voucher", "mã giảm", "promotion", "discount", "sale",
    "tích điểm", "điểm", "hạng", "thành viên", "loyalty", "point", "silver", "gold", "member",
    "giao hàng", "ship", "delivery", "pickup", "nhận tại", "phí ship", "shipping",
    "thanh toán", "payment", "cod", "vietqr", "chuyển khoản", "bank transfer",
    "đơn hàng", "order", "trạng thái", "status", "hủy đơn", "cancel", "hoàn tiền", "refund",
    "điều khoản", "chính sách", "bảo mật", "terms", "privacy", "policy", "faq", "đặt món", "giỏ hàng", "cart",
)

_GREETING_PATTERNS = [
    re.compile(r"^(xin\s+chào|chào(\s+bạn|\s+clucky|\s+shop)?|hi|hello|hey|good\s+(morning|afternoon|evening)|cảm\s+ơn|thanks?|thank\s+you)[\s!.,?]*$", re.I),
]

_LEAK_PATTERNS = [
    re.compile(r"ai_service\.|review_report_service\.|catalog_service\.|promotion_service\.|cart_order_service\.|identity_service\.", re.I),
    re.compile(r"http://[a-z-]+-service:8080", re.I),
    re.compile(r"</?(user_query|knowledge_context|tool_data)>", re.I),
]


@dataclass(frozen=True)
class GuardrailResult:
    allowed: bool
    cleaned_text: str
    intent: IntentVerdict
    refusal_reply: Optional[str] = None
    reason: Optional[str] = None


def clean_user_input(raw: str) -> str:
    normalized = unicodedata.normalize("NFKC", raw or "")
    cleaned = _CONTROL_CHAR_RE.sub("", normalized)
    return re.sub(r"[ \t]+", " ", cleaned).strip()


def sanitize_untrusted_text(raw: str, max_len: int = 300) -> str:
    if not raw:
        return ""
    cleaned = clean_user_input(raw)
    cleaned = cleaned.replace("<", "(").replace(">", ")")
    for pat in _INJECTION_PATTERNS:
        cleaned = pat.sub("[filtered]", cleaned)
    return cleaned[:max_len]


def check_layer1_static(raw: str, locale: Locale) -> GuardrailResult:
    settings = get_settings()
    cleaned = clean_user_input(raw)
    if not cleaned:
        return GuardrailResult(
            allowed=False,
            cleaned_text="",
            intent="OUT_OF_SCOPE",
            refusal_reply=REFUSAL_MESSAGES[locale],
            reason="EMPTY_INPUT",
        )
    if len(cleaned) > settings.max_input_chars:
        return GuardrailResult(
            allowed=False,
            cleaned_text=cleaned[: settings.max_input_chars],
            intent="OUT_OF_SCOPE",
            refusal_reply=TOO_LONG_MESSAGES[locale],
            reason="INPUT_TOO_LONG",
        )

    for pattern in _INJECTION_PATTERNS:
        if pattern.search(cleaned):
            return GuardrailResult(
                allowed=False,
                cleaned_text=cleaned,
                intent="INJECTION",
                refusal_reply=REFUSAL_MESSAGES[locale],
                reason="PROMPT_INJECTION",
            )

    for pattern in _OUT_OF_SCOPE_PATTERNS:
        if pattern.search(cleaned):
            return GuardrailResult(
                allowed=False,
                cleaned_text=cleaned,
                intent="OUT_OF_SCOPE",
                refusal_reply=REFUSAL_MESSAGES[locale],
                reason="OUT_OF_SCOPE",
            )

    for pattern in _GREETING_PATTERNS:
        if pattern.match(cleaned):
            return GuardrailResult(allowed=True, cleaned_text=cleaned, intent="GREETING")

    lowered = cleaned.lower()
    if any(kw in lowered for kw in _IN_SCOPE_KEYWORDS):
        return GuardrailResult(allowed=True, cleaned_text=cleaned, intent="IN_SCOPE")

    # Ambiguous query — allow Layer 1 LLM classifier (if configured) or conservative check.
    return GuardrailResult(allowed=True, cleaned_text=cleaned, intent="IN_SCOPE", reason="NEEDS_INTENT_CHECK")


def sanitize_output_reply(reply: str, locale: Locale) -> str:
    if not reply or not reply.strip():
        return REFUSAL_MESSAGES[locale]
    for pattern in _LEAK_PATTERNS:
        if pattern.search(reply):
            return REFUSAL_MESSAGES[locale]
    return reply.strip()
