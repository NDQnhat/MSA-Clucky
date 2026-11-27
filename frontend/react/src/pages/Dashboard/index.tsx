import { useEffect, useMemo, useState, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Flex,
  Progress,
  Result,
  Row,
  Segmented,
  Select,
  Skeleton,
  Space,
  Statistic,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import {
  AlertOutlined,
  ArrowDownOutlined,
  ArrowUpOutlined,
  BarChartOutlined,
  ClockCircleOutlined,
  CoffeeOutlined,
  DollarOutlined,
  FileTextOutlined,
  FireOutlined,
  InfoCircleOutlined,
  MessageOutlined,
  MoreOutlined,
  ReloadOutlined,
  RiseOutlined,
  RollbackOutlined,
} from '@ant-design/icons';
import { getDashboardOrders, type DashboardOrderSnapshot } from '../../lib/admin-reporting';
import { useAuth } from '../../context/AuthContext';
import { useLocaleTools } from '../../i18n/hooks';
import { getBrowserLocale, getErrorMessage } from '../../i18n/core';
import { pickLocalized } from '../../lib/bilingual';
import {
  inventoryApi,
  reportsApi,
  reviewsApi,
  storesApi,
  type DailyStoreStatsItem,
  type DashboardReportResponse,
  type InventoryAlertSummaryResponse,
  type ItemRevenueEntry,
  type ItemRevenueReportResponse,
  type RevenueReportResponse,
  type ReviewModerationSummaryDto,
  type Store,
} from '../../lib/api';
import {
  ItemStoreBreakdownDrawer,
  type SelectedReportItem,
} from '../../components/reports/ItemStoreBreakdownDrawer';
import { getStoreStatusMeta, normalizeStoreStatus } from '../../types/store';

export type OrderSummary = {
  key: string;
  code: string;
  customer: string;
  items: number;
  status:
    | 'PREPARING'
    | 'COMPLETED'
    | 'SHIPPING'
    | 'PENDING'
    | 'READY'
    | 'PENDING_PAYMENT'
    | 'CONFIRMED'
    | 'CANCELLED';
  total: number;
  time: string;
  storeName?: string;
};

const statusTagStyleMap: Record<string, { color: string; bg: string; border: string }> = {
  PREPARING: { color: '#D97706', bg: '#FEF3C7', border: '#FDE68A' },
  PENDING: { color: '#B45309', bg: '#FFFBEB', border: '#FCD34D' },
  PENDING_PAYMENT: { color: '#B45309', bg: '#FFFBEB', border: '#FCD34D' },
  CONFIRMED: { color: '#B45309', bg: '#FFFBEB', border: '#FCD34D' },
  READY: { color: '#735A00', bg: '#FEF9C3', border: '#FDE047' },
  COMPLETED: { color: '#1E7A46', bg: '#D1FAE5', border: '#A7F3D0' },
  SHIPPING: { color: '#22577A', bg: '#E0F2FE', border: '#BAE6FD' },
  CANCELLED: { color: '#B42318', bg: '#FEE2E2', border: '#FECACA' },
};

export function StatusTag({ status }: { status: OrderSummary['status'] | string }) {
  const { t } = useTranslation();
  const labels: Record<string, string> = {
    PREPARING: t('business:orderprogress.preparing'),
    COMPLETED: t('business:orders.completed'),
    SHIPPING: t('business:orderprogress.delivering'),
    PENDING: t('business:dashboard.awaitingConfirmation'),
    CONFIRMED: t('business:orderprogress.confirm'),
    READY: t('business:orderprogress.ready'),
    PENDING_PAYMENT: t('business:checkout.awaitingPayment'),
    CANCELLED: t('business:orders.cancelled'),
  };
  const custom = statusTagStyleMap[status];
  if (custom) {
    return (
      <Tag
        variant="filled"
        style={{
          color: custom.color,
          backgroundColor: custom.bg,
          border: `1px solid ${custom.border}`,
          borderRadius: 8,
          fontWeight: 600,
          fontFamily: 'Quicksand, sans-serif',
          padding: '2px 8px',
        }}
      >
        {labels[status] ?? status}
      </Tag>
    );
  }
  return <Tag>{labels[status] ?? status}</Tag>;
}

export function OrdersTable({
  compact = false,
  orders = [],
  loading = false,
  onViewOrder,
}: {
  compact?: boolean;
  orders?: OrderSummary[];
  loading?: boolean;
  onViewOrder?: () => void;
}) {
  const { money, date } = useLocaleTools();
  const { t } = useTranslation();

  const columns: TableColumnsType<OrderSummary> = [
    {
      title: t('business:dashboard.orderCode'),
      dataIndex: 'code',
      key: 'code',
      render: (value: string) => (
        <Typography.Text strong style={{ color: '#16100D', fontFamily: 'var(--font-body)' }}>
          {value}
        </Typography.Text>
      ),
    },
    {
      title: t('business:dashboard.customer'),
      dataIndex: 'customer',
      key: 'customer',
      render: (value: string, row: OrderSummary) => (
        <div>
          <Typography.Text strong style={{ color: '#16100D' }}>
            {value}
          </Typography.Text>
          <Typography.Text type="secondary" className="table-subtext">
            {row.items} {t('business:dashboard.items')} {date(row.time)}
            {row.storeName ? ` · ${row.storeName}` : ''}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: t('business:catalog.status'),
      dataIndex: 'status',
      key: 'status',
      render: (value: string) => <StatusTag status={value} />,
    },
    {
      title: t('business:id.total'),
      dataIndex: 'total',
      key: 'total',
      align: 'right',
      render: (value: number) => (
        <Typography.Text strong className="money" style={{ color: '#8F1216', fontSize: 15 }}>
          {money(value)}
        </Typography.Text>
      ),
    },
    {
      title: '',
      key: 'actions',
      width: 48,
      render: () => (
        <Button
          type="text"
          icon={<MoreOutlined style={{ color: '#6B6259' }} />}
          aria-label={t('business:catalog.actions')}
          onClick={onViewOrder}
        />
      ),
    },
  ];

  return (
    <Table
      className="orders-table"
      columns={columns}
      dataSource={compact ? orders.slice(0, 5) : orders}
      loading={loading}
      locale={{ emptyText: t('business:dashboard.noRecentOrders') }}
      pagination={!compact ? { pageSize: 8, showSizeChanger: false } : false}
      scroll={{ x: 620 }}
    />
  );
}

export interface MetricCardProps {
  title: string;
  value: string;
  suffix?: string;
  trend: string;
  icon: React.ReactNode;
  warning?: boolean;
  trendUp?: boolean;
  tooltip?: string;
}

export function MetricCard({
  title,
  value,
  suffix,
  trend,
  icon,
  warning = false,
  trendUp = true,
  tooltip,
}: MetricCardProps) {
  return (
    <Card className="metric-card" hoverable>
      <Flex justify="space-between" align="start">
        <Statistic
          title={
            <Space size={4}>
              <span>{title}</span>
              {tooltip && (
                <Tooltip title={tooltip}>
                  <InfoCircleOutlined style={{ fontSize: 12, color: '#6B6259' }} />
                </Tooltip>
              )}
            </Space>
          }
          value={value}
          suffix={suffix ? <small style={{ fontSize: '0.65em', marginLeft: 4 }}>{suffix}</small> : undefined}
        />
        <span className={`metric-icon ${warning ? 'warning' : ''}`}>{icon}</span>
      </Flex>
      <Typography.Text
        className={`metric-trend ${warning ? 'warning-text' : ''}`}
        style={!warning && !trendUp ? { color: '#B42318' } : undefined}
      >
        {warning ? '● ' : trendUp ? <ArrowUpOutlined /> : <ArrowDownOutlined />} {trend}
      </Typography.Text>
    </Card>
  );
}

export function StoreHealth({
  name,
  status,
  orders,
  warning = false,
  sharePct,
  onClick,
}: {
  name: string;
  status: string;
  orders: string;
  warning?: boolean;
  sharePct?: number;
  onClick?: () => void;
}) {
  return (
    <div
      className="store-health"
      onClick={onClick}
      style={onClick ? { cursor: 'pointer' } : undefined}
    >
      <span className={`health-dot ${warning ? 'warning' : ''}`} />
      <div style={{ flex: 1 }}>
        <Flex align="center" gap={6} wrap="wrap">
          <Typography.Text strong style={{ color: '#16100D' }}>
            {name}
          </Typography.Text>
          {typeof sharePct === 'number' && sharePct > 0 && (
            <Tag color="volcano" variant="filled" style={{ margin: 0, fontSize: 11, borderRadius: 6 }}>
              {sharePct}%
            </Tag>
          )}
        </Flex>
        <Typography.Text type="secondary" style={{ color: '#6B6259' }}>
          {orders}
        </Typography.Text>
      </div>
      <Tag
        variant="filled"
        style={{
          borderRadius: 8,
          fontWeight: 600,
          fontFamily: 'Quicksand, sans-serif',
          color: warning ? '#D97706' : '#1E7A46',
          backgroundColor: warning ? '#FEF3C7' : '#D1FAE5',
          border: `1px solid ${warning ? '#FDE68A' : '#A7F3D0'}`,
        }}
      >
        {status}
      </Tag>
    </div>
  );
}

export function PageIntro({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow: string;
  title: string;
  description: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="page-intro">
      <div>
        <Typography.Text className="eyebrow">{eyebrow}</Typography.Text>
        <Typography.Title level={2} style={{ marginTop: 4, marginBottom: 4 }}>
          {title}
        </Typography.Title>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0, color: '#6B6259' }}>
          {description}
        </Typography.Paragraph>
      </div>
      {action}
    </div>
  );
}

function getVietnamTodayIso(): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Ho_Chi_Minh',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());
}

function shiftIsoDate(isoDate: string, deltaDays: number): string {
  const [y, m, d] = isoDate.split('-').map(Number);
  const utc = Date.UTC(y, m - 1, d + deltaDays);
  return new Date(utc).toISOString().slice(0, 10);
}

function formatShortDayMonth(isoDate: string): string {
  const parts = isoDate.split('-');
  if (parts.length !== 3) return isoDate;
  return `${parts[2]}/${parts[1]}`;
}

function formatPeriodDelta(current: number, previous: number): { label: string; isUp: boolean } | null {
  if (previous <= 0) return null;
  const pct = ((current - previous) / previous) * 100;
  const rounded = pct.toFixed(1);
  return {
    label: `${pct >= 0 ? '+' : ''}${rounded}%`,
    isUp: pct >= 0,
  };
}

export interface DashboardProps {
  onNavigate?: (key: string) => void;
  storeFilter?: string;
  onStoreChange?: (storeId: string) => void;
}

type DashboardPeriodKey = '7d' | '30d' | 'month';

export default function Dashboard({
  onNavigate,
  storeFilter,
  onStoreChange,
}: DashboardProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { user, isAdmin } = useAuth();
  const { money, number, date, locale } = useLocaleTools();

  const isStaff = user.role === 'STAFF';
  const isManager = user.role === 'STORE_MANAGER';
  const managerStoreId = isManager && user.storeId && user.storeId > 0 ? user.storeId : null;
  const managerMissingStore = isManager && !managerStoreId;

  const [periodKey, setPeriodKey] = useState<DashboardPeriodKey>('7d');
  const [chartMetric, setChartMetric] = useState<'revenue' | 'orders'>('revenue');
  const [itemTypeFilter, setItemTypeFilter] = useState<'ALL' | 'PRODUCT' | 'COMBO'>('ALL');
  const [itemSortBy, setItemSortBy] = useState<'revenue' | 'quantity'>('revenue');
  const [selectedStore, setSelectedStore] = useState<string>(() => {
    if (isManager && managerStoreId) return String(managerStoreId);
    return storeFilter && storeFilter !== 'all' ? storeFilter : 'all';
  });

  const [dashboard, setDashboard] = useState<DashboardReportResponse | null>(null);
  const [revenueReport, setRevenueReport] = useState<RevenueReportResponse | null>(null);
  const [itemReport, setItemReport] = useState<ItemRevenueReportResponse | null>(null);
  const [orderSnapshot, setOrderSnapshot] = useState<DashboardOrderSnapshot | null>(null);
  const [inventoryAlerts, setInventoryAlerts] = useState<InventoryAlertSummaryResponse | null>(null);
  const [reviewSummary, setReviewSummary] = useState<ReviewModerationSummaryDto | null>(null);
  const [orderError, setOrderError] = useState('');
  const [stores, setStores] = useState<Store[]>([]);
  const [storeError, setStoreError] = useState('');
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [reloadNonce, setReloadNonce] = useState(0);
  const [drawerItem, setDrawerItem] = useState<SelectedReportItem | null>(null);
  const loadedScope = useRef('');

  useEffect(() => {
    const timer = window.setInterval(() => {
      if (!document.hidden) setReloadNonce((value) => value + 1);
    }, 30000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (isManager && managerStoreId) {
      setSelectedStore(String(managerStoreId));
    }
  }, [isManager, managerStoreId]);

  const effectiveStoreId = useMemo<number | undefined>(() => {
    if (isManager) return managerStoreId ?? undefined;
    if (selectedStore === 'all') return undefined;
    const parsed = Number(selectedStore);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
  }, [isManager, managerStoreId, selectedStore]);

  const today = getVietnamTodayIso();
  const { fromIso, toIso } = useMemo(() => {
    if (periodKey === '30d') {
      return { fromIso: shiftIsoDate(today, -29), toIso: today };
    }
    if (periodKey === 'month') {
      return { fromIso: `${today.slice(0, 8)}01`, toIso: today };
    }
    return { fromIso: shiftIsoDate(today, -6), toIso: today };
  }, [periodKey, today]);

  const go = useCallback(
    (target: string) => {
      if (onNavigate) {
        onNavigate(target);
        return;
      }
      navigate(`/admin/${target}`);
    },
    [navigate, onNavigate]
  );

  useEffect(() => {
    if (isStaff || managerMissingStore) {
      setDashboard(null);
      setRevenueReport(null);
      setItemReport(null);
      setOrderSnapshot(null);
      setInventoryAlerts(null);
      setReviewSummary(null);
      setLoading(false);
      return;
    }

    const controller = new AbortController();
    setLoading(true);
    setLoadError('');
    const scope = `${user.id}:${user.role}:${effectiveStoreId}:${fromIso}:${toIso}`;
    if (loadedScope.current !== scope) {
      setDashboard(null);
      setRevenueReport(null);
      setItemReport(null);
      setStores([]);
    }
    loadedScope.current = scope;
    setStoreError('');

    const reportParams = {
      storeId: effectiveStoreId,
      from: fromIso,
      to: toIso,
    };

    Promise.all([
      reportsApi.getDashboard(reportParams, controller.signal),
      reportsApi.getRevenue(reportParams, controller.signal),
      reportsApi
        .getItemRevenue(
          {
            ...reportParams,
            itemType: itemTypeFilter,
            sortBy: itemSortBy,
            limit: 8,
          },
          controller.signal
        )
        .catch(() => null),
      storesApi.list(isAdmin ? false : undefined, controller.signal).catch((failure) => {
        if (!controller.signal.aborted) setStoreError(getErrorMessage(failure, getBrowserLocale()));
        return [] as Store[];
      }),
    ])
      .then(([dashData, revData, itemData, storeList]) => {
        if (controller.signal.aborted) return;
        setDashboard(dashData);
        setRevenueReport(revData);
        if (itemData) setItemReport(itemData);
        if (Array.isArray(storeList)) {
          setStores(storeList);
        }
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        setLoadError(getErrorMessage(error, getBrowserLocale()));
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setLoading(false);
        }
      });

    return () => controller.abort();
  }, [
    effectiveStoreId,
    fromIso,
    toIso,
    itemTypeFilter,
    itemSortBy,
    isAdmin,
    isStaff,
    managerMissingStore,
    reloadNonce,
    user.id,
    user.role,
  ]);

  useEffect(() => {
    setOrderSnapshot(null);
    setOrderError('');
    setInventoryAlerts(null);
    setReviewSummary(null);
    if (isStaff || managerMissingStore) return;

    const controller = new AbortController();
    Promise.all([
      getDashboardOrders(effectiveStoreId, controller.signal).catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setOrderError(getErrorMessage(error, getBrowserLocale()));
        }
        return null;
      }),
      inventoryApi.getAlerts(effectiveStoreId, controller.signal).catch(() => null),
      reviewsApi.summaryModeration(effectiveStoreId, controller.signal).catch(() => null),
    ]).then(([ordersRes, alertsRes, reviewRes]) => {
      if (controller.signal.aborted) return;
      if (ordersRes) setOrderSnapshot(ordersRes);
      if (alertsRes) setInventoryAlerts(alertsRes);
      if (reviewRes) setReviewSummary(reviewRes);
    });

    return () => controller.abort();
  }, [effectiveStoreId, isStaff, managerMissingStore, reloadNonce, user.id, user.role]);

  const storeMap = useMemo(() => {
    const map = new Map<number, Store>();
    for (const store of stores) {
      map.set(store.id, store);
    }
    return map;
  }, [stores]);

  const resolveStoreName = useCallback(
    (storeId: number) => {
      const st = storeMap.get(storeId);
      if (st) return pickLocalized(st.nameI18n, st.name, locale);
      if (isManager && storeId === managerStoreId && user.storeName) return user.storeName;
      return t('business:dashboard.scopeStoreLabel', { value1: storeId });
    },
    [isManager, locale, managerStoreId, storeMap, t, user.storeName]
  );

  const recentOrdersData: OrderSummary[] = useMemo(() => {
    return (orderSnapshot?.recent ?? []).map((order) => ({
      key: String(order.id),
      code: '#' + order.orderCode,
      customer: order.receiverName,
      items: (order.items ?? []).reduce((sum, item) => sum + item.quantity, 0),
      status: order.status as OrderSummary['status'],
      total: order.totalAmount,
      time: order.createdAt,
      storeName: resolveStoreName(order.storeId),
    }));
  }, [orderSnapshot, resolveStoreName]);

  const activeKitchenCount = orderSnapshot?.activeKitchenCount;

  const chartSeries = useMemo(() => {
    if (!revenueReport) return [];
    const items: DailyStoreStatsItem[] = revenueReport?.items ?? [];
    const byDate = new Map<string, { revenue: number; orders: number }>();
    for (const item of items) {
      const existing = byDate.get(item.statDate) ?? { revenue: 0, orders: 0 };
      byDate.set(item.statDate, {
        revenue: existing.revenue + Number(item.revenue || 0),
        orders: existing.orders + Number(item.orderCount || 0),
      });
    }
    const visibleDates: string[] = [];
    for (
      const day = new Date(`${fromIso}T00:00:00Z`);
      day.toISOString().slice(0, 10) <= toIso;
      day.setUTCDate(day.getUTCDate() + 1)
    ) {
      const iso = day.toISOString().slice(0, 10);
      visibleDates.push(iso);
      if (!byDate.has(iso)) byDate.set(iso, { revenue: 0, orders: 0 });
    }
    const maxVal = Math.max(
      1,
      ...visibleDates.map((d) => {
        const pt = byDate.get(d);
        return chartMetric === 'revenue' ? (pt?.revenue ?? 0) : (pt?.orders ?? 0);
      })
    );
    return visibleDates.map((iso) => {
      const point = byDate.get(iso)!;
      const metricValue = chartMetric === 'revenue' ? point.revenue : point.orders;
      const heightPct = metricValue > 0 ? Math.max(10, Math.round((metricValue / maxVal) * 100)) : 4;
      return {
        iso,
        label: formatShortDayMonth(iso),
        revenue: point.revenue,
        orders: point.orders,
        heightPct,
      };
    });
  }, [chartMetric, fromIso, revenueReport, toIso]);

  const storeStatsByStore = useMemo(() => {
    const grouped = new Map<number, { orders: number; revenue: number }>();
    for (const item of revenueReport?.items ?? []) {
      const prev = grouped.get(item.storeId) ?? { orders: 0, revenue: 0 };
      grouped.set(item.storeId, {
        orders: prev.orders + Number(item.orderCount || 0),
        revenue: prev.revenue + Number(item.revenue || 0),
      });
    }
    return grouped;
  }, [revenueReport]);

  const periodTotalRevenue = Number(dashboard?.periodRevenue ?? revenueReport?.totalRevenue ?? 0);
  const periodOrderCount = Number(dashboard?.periodOrderCount ?? revenueReport?.totalOrderCount ?? 0);

  const visibleStores = useMemo(() => {
    const list =
      effectiveStoreId != null ? stores.filter((s) => s.id === effectiveStoreId) : [...stores];
    return list.sort((a, b) => {
      const revA = storeStatsByStore.get(a.id)?.revenue ?? 0;
      const revB = storeStatsByStore.get(b.id)?.revenue ?? 0;
      return revB - revA || a.id - b.id;
    });
  }, [effectiveStoreId, storeStatsByStore, stores]);

  const storeSelectOptions = useMemo<Array<{ value: string; label: string }>>(() => {
    if (isManager && managerStoreId) {
      return [{ value: String(managerStoreId), label: resolveStoreName(managerStoreId) }];
    }
    const opts = [{ value: 'all', label: t('business:dashboard.scopeChain') }];
    for (const store of stores) {
      opts.push({
        value: String(store.id),
        label: pickLocalized(store.nameI18n, store.name, locale),
      });
    }
    return opts;
  }, [isManager, locale, managerStoreId, resolveStoreName, stores, t]);

  const handleStoreChange = (val: string) => {
    if (isManager) return;
    setSelectedStore(val);
    onStoreChange?.(val);
  };

  if (isStaff || managerMissingStore) {
    return (
      <Result
        status="403"
        title={t('errors:FORBIDDEN')}
        subTitle={
          isStaff
            ? t('business:dashboard.staffForbiddenTitle')
            : t('business:dashboard.noStoreAssignedError')
        }
      />
    );
  }

  const periodCodAmount = Number(dashboard?.periodCodAmount ?? revenueReport?.totalCodAmount ?? 0);
  const periodReconciled = Number(
    dashboard?.periodReconciledTransferAmount ?? revenueReport?.totalReconciledTransferAmount ?? 0
  );
  const periodPending = Number(
    dashboard?.periodPendingReconciliationAmount ?? revenueReport?.totalPendingReconciliationAmount ?? 0
  );
  const periodExcess = Number(dashboard?.periodExcessAmount ?? revenueReport?.totalExcessAmount ?? 0);
  const periodExpired = Number(
    dashboard?.periodExpiredReceiptAmount ?? revenueReport?.totalExpiredReceiptAmount ?? 0
  );

  const prevRevenue = Number(dashboard?.previousPeriodRevenue ?? 0);
  const revenueDelta = formatPeriodDelta(periodTotalRevenue, prevRevenue);
  const averageOrderValue = periodOrderCount > 0 ? Math.round(periodTotalRevenue / periodOrderCount) : 0;
  const periodPromoAndPoints =
    Number(dashboard?.periodDiscountAmount ?? 0) + Number(dashboard?.periodPointsAmount ?? 0);
  const periodCancelledCount = Number(dashboard?.periodCancelledOrderCount ?? 0);
  const todayCancelledCount = Number(orderSnapshot?.todayCancelledCount ?? 0);

  const webOrders = Number(dashboard?.periodWebOrderCount ?? 0);
  const counterOrders = Number(dashboard?.periodCounterOrderCount ?? 0);
  const totalSourceOrders = webOrders + counterOrders;
  const deliveryOrders = Number(dashboard?.periodDeliveryOrderCount ?? 0);
  const pickupOrders = Number(dashboard?.periodPickupOrderCount ?? 0);
  const totalFulfillOrders = deliveryOrders + pickupOrders;

  const topItems: ItemRevenueEntry[] = itemReport?.items ?? [];

  const lastUpdatedLabel = dashboard?.lastUpdatedAt
    ? t('business:dashboard.lastUpdatedAt', {
        value1: date(dashboard.lastUpdatedAt, {
          hour: '2-digit',
          minute: '2-digit',
        }),
      })
    : t('business:dashboard.notUpdatedYet');

  return (
    <div className="dashboard-page">
      <PageIntro
        eyebrow={t('business:dashboard.eyebrowOverview')}
        title={t('business:dashboard.goodMorningClucky')}
        description={t('business:dashboard.monitorOperationsBusinessPerformanceAndOrdersThatNeedAttentionToday')}
        action={
          <Flex gap={8} align="center" wrap="wrap">
            <Segmented
              options={[
                { value: '7d', label: t('business:dashboard.days') },
                { value: '30d', label: t('business:dashboard.daysLabel') },
                { value: 'month', label: t('business:dashboard.monthToDate') },
              ]}
              value={periodKey}
              onChange={(value) => setPeriodKey(value as DashboardPeriodKey)}
            />
            <Select
              value={selectedStore}
              onChange={handleStoreChange}
              disabled={isManager}
              aria-label={t('business:reports.store')}
              style={{ minWidth: 200 }}
              options={storeSelectOptions}
            />
            <Button
              icon={<ReloadOutlined />}
              onClick={() => setReloadNonce((n) => n + 1)}
              loading={loading}
            >
              {t('business:dashboard.refresh')}
            </Button>
            <Button
              type="primary"
              icon={<BarChartOutlined />}
              onClick={() => go('reports')}
            >
              {t('business:dashboard.openReports')}
            </Button>
          </Flex>
        }
      />

      {loadError && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16, borderRadius: 14 }}
          message={loadError}
          action={
            <Button size="small" onClick={() => setReloadNonce((n) => n + 1)}>
              {t('common:retry')}
            </Button>
          }
        />
      )}

      {orderError && <Alert type="error" showIcon message={orderError} style={{ marginBottom: 16 }} />}
      {storeError && <Alert type="warning" showIcon message={t('admin:ui.unableToLoadTheBranchList')} description={storeError} style={{ marginBottom: 16 }} />}

      {dashboard && !dashboard.projectionActive && (
        <Alert
          type="info"
          showIcon
          style={{
            marginBottom: 16,
            borderRadius: 14,
            background: '#FFFAF3',
            borderColor: '#E8E2D9',
          }}
          message={t('business:dashboard.projectionInactiveTitle')}
          description={t('business:dashboard.projectionInactiveDesc', {
            value1: dashboard.revenueEventPolicy ?? 'false',
          })}
        />
      )}

      {loading && !dashboard ? (
        <Card style={{ marginBottom: 16 }}>
          <Skeleton active paragraph={{ rows: 4 }} />
        </Card>
      ) : (
        <Row gutter={[16, 16]} className="stats-row">
          <Col xs={24} sm={12} xl={6}>
            <MetricCard
              title={t('business:dashboard.todaySOrders')}
              value={orderSnapshot ? number(orderSnapshot.todayOrderCount) : '—'}
              suffix={t('business:dashboard.orders')}
              trend={`${t('business:dashboard.periodOrdersSummary', {
                value1: number(periodOrderCount),
                value2: money(Number(dashboard?.todayCodAmount ?? 0)),
              })} · ${t('business:dashboard.cancelledOrdersSub', {
                value1: number(todayCancelledCount),
                value2: number(periodCancelledCount),
              })}`}
              icon={<FileTextOutlined />}
              trendUp
              tooltip={t('business:dashboard.totalOrdersPlacedTodayAtTheSelectedStore')}
            />
          </Col>
          <Col xs={24} sm={12} xl={6}>
            <MetricCard
              title={t('business:dashboard.todayRecognizedRevenue')}
              value={dashboard ? money(Number(dashboard.todayRevenue)) : '—'}
              trend={
                revenueDelta
                  ? `${t('business:dashboard.periodRecognizedSummary', {
                      value1: money(periodTotalRevenue),
                    })} · ${t('business:dashboard.vsPreviousPeriod', { value1: revenueDelta.label })}`
                  : `${t('business:dashboard.periodRecognizedSummary', {
                      value1: money(periodTotalRevenue),
                    })}`
              }
              icon={<DollarOutlined />}
              trendUp={revenueDelta ? revenueDelta.isUp : true}
              tooltip={t('business:dashboard.estimatedRevenueFromSuccessfulOrders')}
            />
          </Col>
          <Col xs={24} sm={12} xl={6}>
            <MetricCard
              title={t('business:dashboard.aovKpiTitle')}
              value={dashboard ? money(averageOrderValue) : '—'}
              trend={t('business:dashboard.aovSubSummary', {
                value1: money(periodPromoAndPoints),
                value2: number(periodCancelledCount),
              })}
              icon={<RiseOutlined />}
              trendUp
              tooltip={t('business:dashboard.aovTooltip')}
            />
          </Col>
          <Col xs={24} sm={12} xl={6}>
            <MetricCard
              title={t('business:orderprogress.preparing')}
              value={activeKitchenCount == null ? '—' : number(activeKitchenCount)}
              suffix={t('business:dashboard.orders')}
              trend={`${t('business:dashboard.activeKitchenOrdersSub')} · ${t('business:dashboard.pendingReconciliationKpi')}: ${money(periodPending)}`}
              icon={periodPending > 0 ? <ClockCircleOutlined /> : <CoffeeOutlined />}
              warning={(activeKitchenCount ?? 0) > 0 || periodPending > 0}
              tooltip={t('business:dashboard.paidOrConfirmedOrdersBeingPrepared')}
            />
          </Col>
        </Row>
      )}

      {/* Actionable Operational Alerts Strip */}
      <Row gutter={[16, 12]} style={{ marginBottom: 16 }}>
        <Col xs={24} md={8}>
          <Card
            size="small"
            hoverable
            onClick={() => go('inventory')}
            style={{
              borderRadius: 14,
              background:
                (inventoryAlerts?.outOfStockCount ?? 0) > 0 || (inventoryAlerts?.lowStockCount ?? 0) > 0
                  ? '#FFFBEB'
                  : '#FFFAF3',
              borderColor:
                (inventoryAlerts?.outOfStockCount ?? 0) > 0 ? '#FCD34D' : '#E8E2D9',
            }}
          >
            <Flex justify="space-between" align="center" gap={8}>
              <Space>
                <AlertOutlined
                  style={{
                    fontSize: 18,
                    color: (inventoryAlerts?.outOfStockCount ?? 0) > 0 ? '#D97706' : '#1E7A46',
                  }}
                />
                <div>
                  <Typography.Text strong style={{ display: 'block', color: '#16100D' }}>
                    {t('business:dashboard.inventoryAlertLabel')}
                  </Typography.Text>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {inventoryAlerts
                      ? inventoryAlerts.outOfStockCount === 0 && inventoryAlerts.lowStockCount === 0
                        ? t('business:dashboard.inventoryAllHealthy')
                        : t('business:dashboard.inventoryAlertDetail', {
                            value1: number(inventoryAlerts.outOfStockCount),
                            value2: number(inventoryAlerts.lowStockCount),
                          })
                      : '—'}
                  </Typography.Text>
                </div>
              </Space>
              <Tag color={(inventoryAlerts?.outOfStockCount ?? 0) > 0 ? 'orange' : 'green'} style={{ margin: 0 }}>
                {inventoryAlerts ? number(inventoryAlerts.outOfStockCount + inventoryAlerts.lowStockCount) : '—'}
              </Tag>
            </Flex>
          </Card>
        </Col>

        <Col xs={24} md={8}>
          <Card
            size="small"
            hoverable
            onClick={() => go('orders')}
            style={{
              borderRadius: 14,
              background: (orderSnapshot?.openRefundRequestCount ?? 0) > 0 ? '#FEF2F2' : '#FFFAF3',
              borderColor: (orderSnapshot?.openRefundRequestCount ?? 0) > 0 ? '#FECACA' : '#E8E2D9',
            }}
          >
            <Flex justify="space-between" align="center" gap={8}>
              <Space>
                <RollbackOutlined
                  style={{
                    fontSize: 18,
                    color: (orderSnapshot?.openRefundRequestCount ?? 0) > 0 ? '#B42318' : '#22577A',
                  }}
                />
                <div>
                  <Typography.Text strong style={{ display: 'block', color: '#16100D' }}>
                    {t('business:dashboard.refundAlertLabel')}
                  </Typography.Text>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {orderSnapshot
                      ? t('business:dashboard.refundAlertDetail', {
                          value1: number(orderSnapshot.openRefundRequestCount),
                        })
                      : '—'}
                  </Typography.Text>
                </div>
              </Space>
              <Tag color={(orderSnapshot?.openRefundRequestCount ?? 0) > 0 ? 'red' : 'blue'} style={{ margin: 0 }}>
                {orderSnapshot ? number(orderSnapshot.openRefundRequestCount) : '—'}
              </Tag>
            </Flex>
          </Card>
        </Col>

        <Col xs={24} md={8}>
          <Card
            size="small"
            hoverable
            onClick={() => go('reviews')}
            style={{
              borderRadius: 14,
              background: (reviewSummary?.unrepliedVisibleCount ?? 0) > 0 ? '#FFFBEB' : '#FFFAF3',
              borderColor: '#E8E2D9',
            }}
          >
            <Flex justify="space-between" align="center" gap={8}>
              <Space>
                <MessageOutlined style={{ fontSize: 18, color: '#C9A227' }} />
                <div>
                  <Typography.Text strong style={{ display: 'block', color: '#16100D' }}>
                    {t('business:dashboard.reviewAlertLabel')}
                  </Typography.Text>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {reviewSummary
                      ? t('business:dashboard.reviewAlertDetail', {
                          value1: reviewSummary.averageRating.toFixed(1),
                          value2: number(reviewSummary.unrepliedVisibleCount),
                        })
                      : '—'}
                  </Typography.Text>
                </div>
              </Space>
              <Tag color={(reviewSummary?.unrepliedVisibleCount ?? 0) > 0 ? 'gold' : 'green'} style={{ margin: 0 }}>
                {reviewSummary ? number(reviewSummary.unrepliedVisibleCount) : '—'}
              </Tag>
            </Flex>
          </Card>
        </Col>
      </Row>

      {/* Row: Item & Combo Revenue + Revenue Trend & Channel Mix */}
      <Row gutter={[16, 16]} className="dashboard-grid">
        <Col xs={24} xl={14}>
          <Card
            title={
              <Space>
                <FireOutlined style={{ color: '#8F1216' }} />
                <span>{t('business:dashboard.topDishesTitle')}</span>
              </Space>
            }
            extra={
              <Flex gap={8} wrap="wrap">
                <Segmented
                  size="small"
                  value={itemTypeFilter}
                  onChange={(val) => setItemTypeFilter(val as 'ALL' | 'PRODUCT' | 'COMBO')}
                  options={[
                    { value: 'ALL', label: t('business:dashboard.allItems') },
                    { value: 'PRODUCT', label: t('business:dashboard.singleProducts') },
                    { value: 'COMBO', label: t('business:dashboard.combosOnly') },
                  ]}
                />
                <Segmented
                  size="small"
                  value={itemSortBy}
                  onChange={(val) => setItemSortBy(val as 'revenue' | 'quantity')}
                  options={[
                    { value: 'revenue', label: t('business:dashboard.sortByRevenue') },
                    { value: 'quantity', label: t('business:dashboard.sortByQuantity') },
                  ]}
                />
              </Flex>
            }
          >
            <Typography.Text type="secondary" style={{ display: 'block', fontSize: 12, marginBottom: 12 }}>
              {t('business:dashboard.itemStoreBreakdownSubtitle')}
            </Typography.Text>
            {topItems.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={t('business:dashboard.noItemRevenueData')}
                style={{ padding: '28px 0' }}
              />
            ) : (
              <Space orientation="vertical" size={10} style={{ width: '100%' }}>
                {topItems.map((entry, idx) => {
                  const displayName = pickLocalized(entry.itemNameI18n, entry.itemName, locale);
                  return (
                    <div
                      key={`${entry.itemType}-${entry.itemRefId}`}
                      onClick={() =>
                        setDrawerItem({
                          itemType: entry.itemType,
                          itemRefId: entry.itemRefId,
                          itemName: entry.itemName,
                          itemNameI18n: entry.itemNameI18n,
                        })
                      }
                      style={{
                        cursor: 'pointer',
                        padding: '10px 12px',
                        borderRadius: 12,
                        background: '#FFFAF3',
                        border: '1px solid #E8E2D9',
                      }}
                    >
                      <Flex justify="space-between" align="center" gap={8} wrap="wrap">
                        <Space size={8}>
                          <Tag color={idx < 3 ? 'red' : 'default'} style={{ margin: 0, fontWeight: 700 }}>
                            #{idx + 1}
                          </Tag>
                          <Tag color={entry.itemType === 'COMBO' ? 'gold' : 'volcano'} variant="filled" style={{ margin: 0 }}>
                            {entry.itemType === 'COMBO'
                              ? t('business:dashboard.combosOnly')
                              : t('business:dashboard.singleProducts')}
                          </Tag>
                          <Typography.Text strong style={{ color: '#16100D' }}>
                            {displayName}
                          </Typography.Text>
                        </Space>
                        <div style={{ textAlign: 'right' }}>
                          <Typography.Text strong style={{ color: '#8F1216', fontFamily: 'var(--font-heading)', fontSize: 15 }}>
                            {money(Number(entry.grossRevenue || 0))}
                          </Typography.Text>
                          <Typography.Text type="secondary" style={{ display: 'block', fontSize: 11 }}>
                            {t('business:dashboard.netAllocatedShort', {
                              value1: money(Number(entry.allocatedNetRevenue || 0)),
                            })}
                          </Typography.Text>
                        </div>
                      </Flex>
                      <Flex justify="space-between" align="center" gap={12} style={{ marginTop: 6 }}>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          {t('business:dashboard.itemSalesMeta', {
                            value1: number(entry.quantitySold),
                            value2: number(entry.orderCount),
                            value3: entry.revenueSharePct,
                          })}
                        </Typography.Text>
                        <div style={{ width: 120 }}>
                          <Progress
                            percent={Math.min(100, Math.round(entry.revenueSharePct))}
                            size="small"
                            showInfo={false}
                            strokeColor="#8F1216"
                          />
                        </div>
                      </Flex>
                    </div>
                  );
                })}
              </Space>
            )}
          </Card>
        </Col>

        <Col xs={24} xl={10}>
          <Space orientation="vertical" size={16} style={{ width: '100%' }}>
            <Card
              title={t('business:dashboard.revenueOverTime')}
              extra={
                <Segmented
                  size="small"
                  options={[
                    { value: 'revenue', label: t('business:dashboard.chartMetricRevenue') },
                    { value: 'orders', label: t('business:dashboard.chartMetricOrders') },
                  ]}
                  value={chartMetric}
                  onChange={(value) => setChartMetric(value as 'revenue' | 'orders')}
                />
              }
            >
              {chartSeries.length === 0 ? (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={t('business:dashboard.noChartData')}
                  style={{ padding: '42px 0' }}
                />
              ) : (
                <div className="revenue-chart">
                  <div className="chart-bars">
                    {chartSeries.map((item) => (
                      <Tooltip
                        key={item.iso}
                        title={`${date(item.iso)}: ${money(item.revenue)} (${t('business:reports.ordersUnit', { value1: number(item.orders) })})`}
                      >
                        <div
                          className="bar-group"
                          tabIndex={0}
                          aria-label={`${date(item.iso)}: ${money(item.revenue)}`}
                        >
                          <div className="chart-bar" style={{ height: `${item.heightPct}%` }} />
                          <span>{item.label}</span>
                        </div>
                      </Tooltip>
                    ))}
                  </div>
                  <Flex justify="space-between" className="chart-caption" align="center" wrap="wrap" gap={8}>
                    <Typography.Text type="secondary" style={{ color: '#6B6259', fontSize: 12 }}>
                      {lastUpdatedLabel}
                    </Typography.Text>
                    <Typography.Text
                      strong
                      style={{ color: '#8F1216', fontFamily: 'var(--font-heading)', fontSize: 18 }}
                    >
                      {chartMetric === 'revenue'
                        ? money(periodTotalRevenue)
                        : t('business:reports.ordersUnit', { value1: number(periodOrderCount) })}
                    </Typography.Text>
                  </Flex>
                </div>
              )}
            </Card>

            <Card title={t('business:dashboard.channelMixTitle')}>
              <Space orientation="vertical" size={12} style={{ width: '100%' }}>
                <div>
                  <Flex justify="space-between">
                    <Typography.Text strong>{t('business:dashboard.webOrdersLabel')}</Typography.Text>
                    <Typography.Text>
                      {number(webOrders)} / {number(totalSourceOrders)}
                    </Typography.Text>
                  </Flex>
                  <Progress
                    percent={totalSourceOrders > 0 ? Math.round((webOrders / totalSourceOrders) * 100) : 0}
                    strokeColor="#8F1216"
                    size="small"
                  />
                </div>
                <div>
                  <Flex justify="space-between">
                    <Typography.Text strong>{t('business:dashboard.counterOrdersLabel')}</Typography.Text>
                    <Typography.Text>
                      {number(counterOrders)} / {number(totalSourceOrders)}
                    </Typography.Text>
                  </Flex>
                  <Progress
                    percent={totalSourceOrders > 0 ? Math.round((counterOrders / totalSourceOrders) * 100) : 0}
                    strokeColor="#C9A227"
                    size="small"
                  />
                </div>
                <Flex justify="space-between" style={{ paddingTop: 8, borderTop: '1px solid #E8E2D9' }}>
                  <Typography.Text type="secondary">
                    {t('business:dashboard.deliveryOrdersLabel')}: <strong style={{ color: '#16100D' }}>{number(deliveryOrders)}</strong>
                  </Typography.Text>
                  <Typography.Text type="secondary">
                    {t('business:dashboard.pickupOrdersLabel')}: <strong style={{ color: '#16100D' }}>{number(pickupOrders)}</strong>
                    {totalFulfillOrders > 0 ? ` (${Math.round((pickupOrders / totalFulfillOrders) * 100)}%)` : ''}
                  </Typography.Text>
                </Flex>
              </Space>
            </Card>
          </Space>
        </Col>
      </Row>

      {/* Recent Orders */}
      <Card
        style={{ marginBottom: 16 }}
        title={t('business:dashboard.recentOrders')}
        extra={
          <Button
            type="link"
            onClick={() => go('orders')}
            style={{ color: '#8F1216', fontWeight: 600 }}
          >
            {t('business:dashboard.viewAll')}
          </Button>
        }
      >
        <OrdersTable
          compact
          orders={recentOrdersData}
          loading={!orderSnapshot && !orderError}
          onViewOrder={() => go('orders')}
        />
      </Card>

      {/* Reconciliation Ledger + Full Ranked Stores */}
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card
            title={t('business:dashboard.reconciliationLedgerTitle')}
            extra={
              <Button
                type="link"
                onClick={() => go('reports')}
                style={{ color: '#8F1216', fontWeight: 600 }}
              >
                {t('business:dashboard.reconciliationLedgerLink')}
              </Button>
            }
          >
            <Space orientation="vertical" size={12} style={{ width: '100%' }}>
              <Flex justify="space-between" align="center" style={{ paddingBottom: 8, borderBottom: '1px solid #E8E2D9' }}>
                <Typography.Text strong>{t('business:dashboard.cashCodLabel')}</Typography.Text>
                <Typography.Text strong className="money">
                  {money(periodCodAmount)}
                </Typography.Text>
              </Flex>

              <Flex justify="space-between" align="center" wrap="wrap" gap={8} style={{ paddingBottom: 8, borderBottom: '1px solid #E8E2D9' }}>
                <Space size={8}>
                  <Typography.Text strong>{t('business:dashboard.reconciledTransferLabel')}</Typography.Text>
                  <Tag color="green" variant="filled" style={{ borderRadius: 6, fontWeight: 600 }}>
                    {t('business:dashboard.reconciledBadge')}
                  </Tag>
                </Space>
                <Typography.Text strong style={{ color: '#1E7A46', fontFamily: 'var(--font-heading)' }}>
                  {money(periodReconciled)}
                </Typography.Text>
              </Flex>

              <Flex justify="space-between" align="center" wrap="wrap" gap={8} style={{ paddingBottom: 8, borderBottom: '1px solid #E8E2D9' }}>
                <Space size={8}>
                  <Typography.Text strong>{t('business:dashboard.pendingReconciliationLabel')}</Typography.Text>
                  <Tag color="orange" variant="filled" style={{ borderRadius: 6, fontWeight: 600 }}>
                    {t('business:dashboard.pendingBadge')}
                  </Tag>
                </Space>
                <Typography.Text strong style={{ color: '#D97706', fontFamily: 'var(--font-heading)' }}>
                  {money(periodPending)}
                </Typography.Text>
              </Flex>

              <Flex justify="space-between" align="center" wrap="wrap" gap={8} style={{ paddingBottom: 8, borderBottom: '1px solid #E8E2D9' }}>
                <Space size={8}>
                  <Typography.Text>{t('business:dashboard.excessAmountLabel')}</Typography.Text>
                  <Tag variant="filled" style={{ borderRadius: 6 }}>
                    {t('business:dashboard.excludedFromRevenueBadge')}
                  </Tag>
                </Space>
                <Typography.Text style={{ color: '#6B6259', fontWeight: 700 }}>
                  {money(periodExcess)}
                </Typography.Text>
              </Flex>

              <Flex justify="space-between" align="center" wrap="wrap" gap={8}>
                <Space size={8}>
                  <Typography.Text>{t('business:dashboard.expiredReceiptLabel')}</Typography.Text>
                  <Tag variant="filled" style={{ borderRadius: 6 }}>
                    {t('business:dashboard.excludedFromRevenueBadge')}
                  </Tag>
                </Space>
                <Typography.Text style={{ color: '#6B6259', fontWeight: 700 }}>
                  {money(periodExpired)}
                </Typography.Text>
              </Flex>
            </Space>
          </Card>
        </Col>

        <Col xs={24} md={12}>
          <Card
            title={
              <div>
                <span>{t('business:dashboard.storeStatus')}</span>
                <Typography.Text type="secondary" style={{ display: 'block', fontSize: 12, fontWeight: 400 }}>
                  {t('business:dashboard.allStoresCountLabel', { value1: number(visibleStores.length) })}
                  {isAdmin ? ` · ${t('business:dashboard.clickStoreToFilter')}` : ''}
                </Typography.Text>
              </div>
            }
            extra={
              <Button
                type="link"
                onClick={() => go('stores')}
                style={{ color: '#8F1216', fontWeight: 600 }}
              >
                {t('business:dashboard.stores')}
              </Button>
            }
          >
            {visibleStores.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={t('business:dashboard.noStoreData')}
              />
            ) : (
              visibleStores.map((store) => {
                const normalized = normalizeStoreStatus(store.status);
                const meta = getStoreStatusMeta(store.status);
                const stats = storeStatsByStore.get(store.id);
                const storeRevenue = stats?.revenue ?? 0;
                const sharePct =
                  periodTotalRevenue > 0
                    ? Number(((storeRevenue / periodTotalRevenue) * 100).toFixed(1))
                    : 0;
                const statusLabel =
                  normalized === 'OPEN'
                    ? t('business:stores.open')
                    : normalized === 'CLOSED_TEMP'
                      ? t('business:stores.temporarilyClosed')
                      : t('business:stores.inactive');
                return (
                  <StoreHealth
                    key={store.id}
                    name={pickLocalized(store.nameI18n, store.name, locale)}
                    status={statusLabel}
                    warning={!meta.isOpen}
                    sharePct={effectiveStoreId == null ? sharePct : undefined}
                    orders={`${t('business:dashboard.periodOrdersCount', { value1: number(stats?.orders ?? 0) })} · ${money(storeRevenue)}`}
                    onClick={isAdmin ? () => handleStoreChange(String(store.id)) : undefined}
                  />
                );
              })
            )}
          </Card>
        </Col>
      </Row>

      <ItemStoreBreakdownDrawer
        open={drawerItem !== null}
        onClose={() => setDrawerItem(null)}
        selectedItem={drawerItem}
        fromIso={fromIso}
        toIso={toIso}
        storeId={effectiveStoreId}
        resolveStoreName={resolveStoreName}
      />
    </div>
  );
}
