import request from "./api";

export type LoyaltyPolicy = {
  version: number; spendPerPoint: number; pointValue: number;
  silverThreshold: number; goldThreshold: number; silverDiscount: number; goldDiscount: number;
};
export type LoyaltyOverview = {
  points: { customerId: number; totalPoints: number; availablePoints: number; reservedPoints: number; lifetimePoints: number; tier: "MEMBER" | "SILVER" | "GOLD" };
  policy: LoyaltyPolicy;
  transactions: { id: number; orderId: number | null; change: number; balanceAfter: number; type: string; reason: string | null; createdAt: string }[];
  page: number; size: number; totalElements: number;
};
export type LoyaltyLiveCode = {
  sessionId: string;
  loyaltyTextCode: string;
  loyaltyQrToken: string;
  issuedAt: string;
  expiresAt: string;
  serverTime: string;
};

export const loyaltyApi = {
  policy: () => request<LoyaltyPolicy>("/api/loyalty/policy"),
  me: (page = 0, type = "ALL") => request<LoyaltyOverview>(`/api/loyalty/me?page=${page}&size=10&type=${encodeURIComponent(type)}`),
  open: () => request<LoyaltyLiveCode>("/api/loyalty/live-session/open", { method: "POST" }),
  heartbeat: (sessionId: string) => request<{ serverTime: string }>("/api/loyalty/live-session/heartbeat", {
    method: "POST", body: JSON.stringify({ sessionId }),
  }),
  rotate: (sessionId: string) => request<LoyaltyLiveCode>("/api/loyalty/live-session/rotate", {
    method: "POST", body: JSON.stringify({ sessionId }),
  }),
  close: (sessionId: string) => request<void>("/api/loyalty/live-session/close", {
    method: "POST", body: JSON.stringify({ sessionId }), keepalive: true,
  }),
};
