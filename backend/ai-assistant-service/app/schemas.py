from typing import Literal, Optional
from pydantic import BaseModel, Field


Locale = Literal["vi", "en"]


class ChatRequest(BaseModel):
    message: str = Field(..., min_length=1, max_length=2000)
    sessionId: Optional[str] = Field(default=None, max_length=128)
    storeId: Optional[int] = Field(default=None, gt=0)
    locale: Optional[Locale] = None


class ProductCardDto(BaseModel):
    id: int
    itemType: Literal["PRODUCT", "COMBO"] = "PRODUCT"
    slug: str
    name: str
    price: int
    imageUrl: Optional[str] = None
    isSoldOut: bool = False
    kcal: Optional[int] = None
    spicyLevel: Optional[int] = None
    tags: list[str] = Field(default_factory=list)
    rating: Optional[float] = None
    reviewCount: Optional[int] = None
    quantitySold: Optional[int] = None
    storeId: Optional[int] = None


class ChatResponse(BaseModel):
    sessionId: str
    reply: str
    locale: Locale
    blocked: bool = False
    reason: Optional[str] = None
    recommendations: list[ProductCardDto] = Field(default_factory=list)
    suggestions: list[str] = Field(default_factory=list)
    fallback: bool = False


class HistoryMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str
    recommendations: list[ProductCardDto] = Field(default_factory=list)
    timestamp: str


class HistoryResponse(BaseModel):
    sessionId: str
    messages: list[HistoryMessage] = Field(default_factory=list)


class ReindexResponse(BaseModel):
    status: str
    documentsIndexed: int
    chunksIndexed: int
    embeddingsGenerated: int
    skippedReasons: list[str] = Field(default_factory=list)
