import type { LocalizedText } from '../lib/bilingual';

export type VoucherType = 'PERCENT' | 'AMOUNT';
export type VoucherScope = 'ALL' | 'STORE';
export type VoucherStatus =
  | 'ACTIVE'
  | 'EXPIRED'
  | 'UPCOMING'
  | 'EXHAUSTED'
  | 'DISABLED'
  | 'PENDING_APPROVAL'
  | 'REJECTED';

export interface AdminVoucher {
  id: string | number;
  code?: string | null;
  campaignKind?: 'LEGACY' | 'PERSONAL';
  applicability?: 'ALL' | 'SELECTED';
  storeIds?: number[];
  usage?: number;
  maxUsage?: number | null;
  title?: string;
  titleI18n?: LocalizedText;
  description?: string;
  descriptionI18n?: LocalizedText | null;
  type: VoucherType;
  value: number;
  minOrderAmount: number;
  maxDiscount?: number | null;
  totalLimit?: number | null;
  usedCount: number;
  reservedCount?: number;
  printable?: boolean;
  maxPrintRatio?: number;
  maxPrintPerStore?: number | null;
  printedCount?: number;
  printedUnredeemed?: number;
  maxPrintableLimit?: number;
  remainingPrintableQuota?: number;
  version?: number;
  startAt: string;
  endAt: string;
  scope: VoucherScope;
  storeId?: string | number | null;
  storeName?: string | null;
  storeNameI18n?: LocalizedText | null;
  status: VoucherStatus | string;
  effectiveStatus?: VoucherStatus | string;
  createdBy?: string | number;
  approvedBy?: string | number | null;
  rejectionReason?: string | null;
  rejectedBy?: string | number | null;
  rejectedAt?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface VoucherPayload {
  version?: number;
  code?: string | null;
  applicability?: 'ALL' | 'SELECTED';
  storeIds?: number[];
  title?: string | LocalizedText;
  titleI18n?: LocalizedText | null;
  description?: string | LocalizedText | null;
  descriptionI18n?: LocalizedText | null;
  type: VoucherType;
  value: number;
  minOrderAmount: number;
  maxDiscount?: number | null;
  totalLimit?: number | null;
  startAt: string;
  endAt: string;
  scope: VoucherScope;
  storeId?: number | null;
  status?: VoucherStatus;
  printable?: boolean;
  maxPrintRatio?: number;
  maxPrintPerStore?: number | null;
}

export type PrintBatchStatus = 'ACTIVE' | 'PARTIALLY_REVOKED' | 'REVOKED';

export interface VoucherPrintQuota {
  campaignId: number;
  storeId?: number | null;
  printable: boolean;
  totalLimit?: number | null;
  usedCount: number;
  reservedCount: number;
  printedCount: number;
  printedUnredeemed: number;
  maxPrintableLimit: number;
  globalPrintableRemaining: number;
  poolRemaining: number;
  storePrintLimit?: number | null;
  storePrintedCount: number;
  maxPrintableNow: number;
  multiStoreCampaign: boolean;
}

export interface VoucherPrintBatch {
  id: number;
  batchCode: string;
  campaignId: number;
  campaignTitle?: string | null;
  campaignType?: VoucherType;
  discountValue?: number;
  minOrderAmount?: number;
  maxDiscount?: number | null;
  campaignStartAt?: string;
  campaignEndAt?: string;
  storeId?: number | null;
  storeName?: string | null;
  quantity: number;
  allowedChannels: string;
  status: PrintBatchStatus;
  note?: string | null;
  issuedCount: number;
  reservedCount: number;
  redeemedCount: number;
  expiredCount: number;
  revokedCount: number;
  createdBy: number;
  createdByRole: string;
  createdAt: string;
  revokedBy?: number | null;
  revokedAt?: string | null;
  revokeReason?: string | null;
}

export interface CreatePrintBatchPayload {
  storeId?: number | null;
  quantity: number;
  note?: string | null;
}

export interface VoucherProposal {
  id: string | number;
  code: string;
  title: string;
  type: VoucherType;
  value: number;
  minOrderAmount: number;
  maxDiscount?: number | null;
  totalLimit?: number | null;
  startAt: string;
  endAt: string;
  storeId: number;
  storeName: string;
  proposedBy: string;
  proposalReason: string;
  status: 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED';
  proposedAt: string;
}

export type BannerPosition = 'HERO_HOME' | 'POPUP' | 'PROMO_STRIP';
export type BannerEffectiveStatus = 'ACTIVE' | 'UPCOMING' | 'EXPIRED' | 'DISABLED';

export interface PromoBanner {
  id: number;
  title: string;
  titleI18n?: LocalizedText;
  imageUrl: string;
  imagePublicId?: string | null;
  linkUrl?: string | null;
  targetUrl?: string | null;
  position: BannerPosition;
  displayOrder: number;
  order?: number;
  startAt?: string | null;
  endAt?: string | null;
  isActive: boolean;
  version: number;
  effectiveStatus?: BannerEffectiveStatus;
  createdAt?: string;
  updatedAt?: string;
}

export interface BannerPayload {
  title: string | LocalizedText;
  linkUrl?: string | null;
  position: BannerPosition;
  displayOrder: number;
  startAt?: string | null;
  endAt?: string | null;
  isActive: boolean;
  expectedVersion?: number;
}

export type LoyaltyTier = 'MEMBER' | 'SILVER' | 'GOLD';

export interface AdminMemberPoints {
  id: string | number;
  customerId: string | number;
  customerName?: string;
  customerPhone?: string;
  totalPoints: number;
  tier: LoyaltyTier;
  updatedAt?: string;
}
