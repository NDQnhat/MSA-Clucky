import request from "./api";

export type VoucherCampaign = {
  id: number;
  title: string;
  description: string | null;
  campaignKind: "LEGACY" | "PERSONAL";
  applicability: "ALL" | "SELECTED";
  storeIds: number[];
  scope: "ALL" | "STORE";
  storeId: number | null;
  storeName: string | null;
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

export const personalVoucherApi = {
  campaigns: () => request<VoucherCampaign[]>("/api/promotions/vouchers/available"),
  mine: () => request<PersonalVoucher[]>("/api/promotions/vouchers/mine"),
  claim: (campaignId: number) => request<PersonalVoucher>(`/api/promotions/vouchers/${campaignId}/claim`, { method: "POST" }),
  detail: (instanceId: string) => request<PersonalVoucher>(`/api/promotions/vouchers/my/${encodeURIComponent(instanceId)}`),
};
