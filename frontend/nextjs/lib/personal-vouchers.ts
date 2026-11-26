import request from "./api";
import type { LocalizedText } from "./bilingual";

export type VoucherCampaign = {
  id: number;
  code?: string | null;
  title: string;
  titleI18n?: LocalizedText | null;
  description: string | null;
  descriptionI18n?: LocalizedText | null;
  campaignKind: "LEGACY" | "PERSONAL";
  applicability: "ALL" | "SELECTED";
  storeIds: number[];
  scope: "ALL" | "STORE";
  storeId: number | null;
  storeName: string | null;
  storeNameI18n?: LocalizedText | null;
  type: "PERCENT" | "AMOUNT";
  value: number;
  maxDiscount: number | null;
  minOrderAmount: number;
  startAt: string;
  endAt: string;
  status: string;
  effectiveStatus?: string;
  usage: number;
  maxUsage: number | null;
  reservedCount?: number;
};

export type PersonalVoucher = {
  instanceId: string;
  campaignId: number;
  state: "ISSUED" | "RESERVED" | "REDEEMED" | "EXPIRED" | "REVOKED";
  issuedAt: string;
  expiresAt: string | null;
  code?: string | null;
  qrPayload?: string | null;
  campaign: VoucherCampaign;
};

async function voucherList<T>(path: string): Promise<T[]> {
  const response: unknown = await request<unknown>(path);
  // A misconfigured gateway/HTML fallback can return HTTP 200 with a non-list
  // payload. Treat it as an unavailable service, never pass it to a .map/.filter
  // in checkout or the customer's voucher wallet.
  if (!Array.isArray(response)) throw { code: "DEPENDENCY_UNAVAILABLE", status: 503 };
  return response as T[];
}

export const personalVoucherApi = {
  campaigns: () => voucherList<VoucherCampaign>("/api/promotions/vouchers/available"),
  mine: () => voucherList<PersonalVoucher>("/api/promotions/vouchers/mine"),
  claim: (campaignId: number) => request<PersonalVoucher>(`/api/promotions/vouchers/${campaignId}/claim`, { method: "POST" }),
  detail: (instanceId: string) => request<PersonalVoucher>(`/api/promotions/vouchers/my/${encodeURIComponent(instanceId)}`),
};
