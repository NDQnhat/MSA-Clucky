import { ordersApi, type BackendOrder } from './api';

export interface DashboardOrderSnapshot {
  recent: BackendOrder[];
  activeKitchenCount: number;
  todayOrderCount: number;
  todayCancelledCount: number;
  openRefundRequestCount: number;
}

export async function getDashboardOrders(
  storeId?: number,
  signal?: AbortSignal
): Promise<DashboardOrderSnapshot> {
  const summary = await ordersApi.getDashboardSummary(storeId, signal);
  return {
    recent: summary.recentOrders ?? [],
    activeKitchenCount: Number(summary.activeKitchenCount ?? 0),
    todayOrderCount: Number(summary.todayOrderCount ?? 0),
    todayCancelledCount: Number(summary.todayCancelledCount ?? 0),
    openRefundRequestCount: Number(summary.openRefundRequestCount ?? 0),
  };
}
