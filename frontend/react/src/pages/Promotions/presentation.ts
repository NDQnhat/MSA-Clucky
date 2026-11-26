import type { AdminVoucher } from '../../types/promotion';
import type { LocalizedText } from '../../lib/bilingual';
import { pickLocalized } from '../../lib/bilingual';

const statusColors: Record<string, string> = {
  ACTIVE: 'success', UPCOMING: 'processing', EXPIRED: 'default', EXHAUSTED: 'error',
  DISABLED: 'default', PENDING_APPROVAL: 'warning', REJECTED: 'error',
};

export function voucherState(voucher: Pick<AdminVoucher, 'status' | 'effectiveStatus' | 'scope' | 'applicability'>) {
  const current = voucher.effectiveStatus || voucher.status;
  return {
    isProposal: voucher.status === 'PENDING_APPROVAL' || voucher.status === 'REJECTED',
    status: Object.keys(statusColors).includes(current) ? current : 'UNKNOWN',
    color: statusColors[current] ?? 'default',
    canApprove: voucher.status === 'PENDING_APPROVAL'
      && (voucher.scope === 'STORE' || voucher.applicability === 'SELECTED'),
    canToggle: voucher.status === 'ACTIVE' || voucher.status === 'DISABLED',
  };
}

export function voucherStoreName(
  voucher: Pick<AdminVoucher, 'storeId' | 'storeIds' | 'storeName' | 'storeNameI18n'>,
  stores: { id: number; name: string; nameI18n?: LocalizedText | null }[],
  lang: string = 'vi'
) {
  if (voucher.storeIds?.length) {
    return voucher.storeIds
      .map((id) => {
        const matched = stores.find((store) => store.id === Number(id));
        return matched ? pickLocalized(matched.nameI18n, matched.name, lang) : `#${id}`;
      })
      .join(', ');
  }
  const matchedSingle = stores.find((store) => voucher.storeId != null && Number(voucher.storeId) === store.id);
  if (matchedSingle) {
    return pickLocalized(matchedSingle.nameI18n, matchedSingle.name, lang);
  }
  const resolved = pickLocalized(voucher.storeNameI18n, voucher.storeName, lang).trim();
  return resolved || undefined;
}
