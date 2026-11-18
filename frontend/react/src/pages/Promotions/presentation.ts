import type { AdminVoucher } from '../../types/promotion';

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

export function voucherStoreName(voucher: Pick<AdminVoucher, 'storeId' | 'storeIds' | 'storeName'>, stores: { id: number; name: string }[]) {
  if (voucher.storeIds?.length) {
    return voucher.storeIds.map(id => stores.find(store => store.id === Number(id))?.name || `#${id}`).join(', ');
  }
  return stores.find(store => voucher.storeId != null && Number(voucher.storeId) === store.id)?.name
    || voucher.storeName?.trim() || undefined;
}
