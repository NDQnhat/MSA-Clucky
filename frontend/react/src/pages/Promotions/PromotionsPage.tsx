import { message } from "../../components/feedback";
import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { Alert, Button, Space, Tag, Select, Popconfirm, Tabs, Badge, Typography, Tooltip, Progress } from "antd";
import { TableColumnsType } from "antd";
import dayjs from 'dayjs';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  EyeOutlined,
  ShopOutlined,
  GlobalOutlined,
  GiftOutlined,
  CalendarOutlined,
  StopOutlined,
  CheckCircleOutlined,
  CopyOutlined,
  ReloadOutlined,
  PercentageOutlined,
  ThunderboltOutlined,
  ClockCircleOutlined,
  PictureOutlined,
  PrinterOutlined,
} from '@ant-design/icons';
import { AdminTable } from '../../components/ui/AdminTable';
import { VoucherBadge } from '../../components/ui/VoucherBadge';
import { VoucherModal } from './components/VoucherModal';
import { VoucherDetailDrawer } from './components/VoucherDetailDrawer';
import { LoyaltySettings } from './components/LoyaltySettings';
import { BannerManagementSection } from './components/BannerManagementSection';
import { PrintVoucherModal } from './components/PrintVoucherModal';
import { PrintBatchesSection } from './components/PrintBatchesSection';
import { useAuth } from '../../context/AuthContext';
import {
  promotionsApi,
  storesApi,
  getErrorMessage,
  type Store,
} from '../../lib/api';
import { matchesBilingualSearch, pickLocalized, toLocalizedPayload } from '../../lib/bilingual';
import type { AdminVoucher, VoucherPayload, VoucherStatus } from '../../types/promotion';
import './promotions.css';
import { voucherState, voucherStoreName } from './presentation';

const { Text, Title } = Typography;

export const PromotionsPage: React.FC = () => {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage || i18n.language || 'vi';
  const currencyLabel = t('common:currency');
  const numberLocale = lang.startsWith('en') ? 'en-US' : 'vi-VN';
  const { user, isAdmin } = useAuth();

  const [activeTab, setActiveTab] = useState<string>('vouchers');
  const [vouchers, setVouchers] = useState<AdminVoucher[]>([]);
  const [stores, setStores] = useState<Store[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [loadError, setLoadError] = useState('');
  const [storeError, setStoreError] = useState('');
  const [actionError, setActionError] = useState('');
  const [actionLoading, setActionLoading] = useState<boolean>(false);

  // Filters
  const [searchTerm, setSearchTerm] = useState<string>('');
  const [scopeFilter, setScopeFilter] = useState<string>('ALL');
  const [statusFilter, setStatusFilter] = useState<string>('ALL');
  const [storeFilter, setStoreFilter] = useState<number | 'ALL'>('ALL');
  const [reviewSearch, setReviewSearch] = useState('');
  const [reviewStatus, setReviewStatus] = useState(isAdmin ? 'PENDING_APPROVAL' : 'ALL');
  const [reviewStore, setReviewStore] = useState<number | 'ALL'>('ALL');

  // Modals & Drawers
  const [modalOpen, setModalOpen] = useState<boolean>(false);
  const [editingVoucher, setEditingVoucher] = useState<AdminVoucher | null>(null);
  const [drawerOpen, setDrawerOpen] = useState<boolean>(false);
  const [selectedVoucher, setSelectedVoucher] = useState<AdminVoucher | null>(null);
  const [printModalOpen, setPrintModalOpen] = useState<boolean>(false);
  const [printingVoucher, setPrintingVoucher] = useState<AdminVoucher | null>(null);
  const [printBatchesNonce, setPrintBatchesNonce] = useState<number>(0);
  const voucherRequest = useRef(0);
  const mutationLock = useRef(false);

  // Fetch stores list
  const fetchStores = useCallback(async () => {
    setStoreError('');
    try {
      const data = isAdmin ? await storesApi.list(false)
        : user.storeId ? [await storesApi.get(Number(user.storeId))] : [];
      setStores(data || []);
    } catch (error) {
      setStoreError(getErrorMessage(error));
    }
  }, [isAdmin, user.storeId, lang]);

  const fetchVouchers = useCallback(async () => {
    const request = ++voucherRequest.current;
    try {
      setLoading(true);
      setLoadError('');
      const data = await promotionsApi.listVouchers();

      if (request === voucherRequest.current) {
        setVouchers(data);
        setSelectedVoucher(current => current ? data.find(voucher => voucher.id === current.id) ?? null : null);
      }
    } catch (error) {
      if (request !== voucherRequest.current) return;
      setLoadError(getErrorMessage(error));
      setVouchers([]);
    } finally {
      if (request === voucherRequest.current) setLoading(false);
    }
  }, [lang]);

  useEffect(() => {
    fetchStores();
  }, [fetchStores]);

  useEffect(() => {
    fetchVouchers();
    window.addEventListener('focus', fetchVouchers);
    return () => { voucherRequest.current += 1; window.removeEventListener('focus', fetchVouchers); };
  }, [fetchVouchers]);

  const liveVouchers = useMemo(() => vouchers.filter(voucher => !voucherState(voucher).isProposal), [vouchers]);
  const pendingCount = vouchers.filter(voucher => voucher.status === 'PENDING_APPROVAL').length;
  const reviewRows = vouchers.filter(voucher => {
    if (!voucherState(voucher).isProposal) return false;
    if (reviewStatus !== 'ALL' && voucher.status !== reviewStatus) return false;
    if (reviewStore !== 'ALL' && Number(voucher.storeId) !== reviewStore
        && !voucher.storeIds?.some(storeId => Number(storeId) === reviewStore)) return false;
    return matchesBilingualSearch(
      reviewSearch,
      voucher.code,
      voucher.titleI18n,
      voucher.title,
      voucherStoreName(voucher, stores, lang),
      voucher.storeNameI18n,
      voucher.rejectionReason
    );
  });

  // Quick KPI Statistics
  const kpiStats = useMemo(() => {
    const total = liveVouchers.length;
    const active = liveVouchers.filter((v) => (v.effectiveStatus || v.status) === 'ACTIVE').length;
    const upcoming = liveVouchers.filter((v) => (v.effectiveStatus || v.status) === 'UPCOMING').length;
    const inactive = liveVouchers.filter((v) => {
      const s = v.effectiveStatus || v.status;
      return s === 'EXPIRED' || s === 'EXHAUSTED' || s === 'DISABLED';
    }).length;
    return { total, active, upcoming, inactive };
  }, [liveVouchers]);

  // Role isolation & Client-side filtering
  const filteredVouchers = useMemo(() => {
    return liveVouchers.filter((v) => {
      // 1. Role-based isolation: Store Manager only sees ALL vouchers or vouchers of their store
      const applicability = v.applicability || (v.scope === 'STORE' ? 'SELECTED' : 'ALL');
      const storeIds = v.storeIds?.length ? v.storeIds : v.storeId ? [Number(v.storeId)] : [];
      if (!isAdmin && user.storeId) {
        if (applicability === 'SELECTED' && !storeIds.some(id => Number(id) === Number(user.storeId))) {
          return false;
        }
      }

      // 2. Scope filter
      if (scopeFilter === 'SCOPE_ALL' && applicability !== 'ALL') return false;
      if (scopeFilter === 'SCOPE_STORE' && applicability !== 'SELECTED') return false;

      // 3. Store filter (for admin)
      if (storeFilter !== 'ALL') {
        if (applicability !== 'SELECTED' || !storeIds.some(id => Number(id) === Number(storeFilter))) {
          return false;
        }
      }

      // 4. Status filter
      if (statusFilter !== 'ALL') {
        const effStatus = v.effectiveStatus || v.status;
        if (statusFilter === 'INACTIVE'
          ? !['EXPIRED', 'EXHAUSTED', 'DISABLED'].includes(effStatus)
          : effStatus !== statusFilter) {
          return false;
        }
      }

      // 5. Keyword search
      if (!matchesBilingualSearch(searchTerm, v.code, v.titleI18n, v.title, v.descriptionI18n, v.description)) {
        return false;
      }

      return true;
    });
  }, [liveVouchers, isAdmin, user.storeId, scopeFilter, storeFilter, statusFilter, searchTerm]);

  const hasActiveFilters =
    searchTerm.trim() !== '' ||
    scopeFilter !== 'ALL' ||
    statusFilter !== 'ALL' ||
    storeFilter !== 'ALL';

  const handleResetFilters = () => {
    setSearchTerm('');
    setScopeFilter('ALL');
    setStatusFilter('ALL');
    setStoreFilter('ALL');
  };

  // Open create modal
  const handleOpenCreate = () => {
    if (!isAdmin && !user.storeId) return;
    setEditingVoucher(null);
    setModalOpen(true);
  };

  // Open edit modal
  const handleOpenEdit = (voucher: AdminVoucher) => {
    if (!isAdmin || !voucherState(voucher).canToggle || mutationLock.current) return;
    setEditingVoucher(voucher);
    setModalOpen(true);
  };

  // Open detail drawer
  const handleOpenDetail = (voucher: AdminVoucher) => {
    setActionError('');
    setSelectedVoucher(voucher);
    setDrawerOpen(true);
  };

  const handleOpenPrint = (voucher: AdminVoucher) => {
    setPrintingVoucher(voucher);
    setPrintModalOpen(true);
  };

  // Quick copy code
  const handleCopyCode = async (code: string) => {
    try {
      await navigator.clipboard.writeText(code);
      message.success(t('admin:voucherUi.copiedCode', { code }));
    } catch {
      message.error(t('common:feedback.copyFailed'));
    }
  };

  // Submit create or update
  const handleSubmitVoucher = async (payload: VoucherPayload) => {
    if (mutationLock.current || (editingVoucher && !isAdmin)) return;
    mutationLock.current = true;
    setActionLoading(true);
    try {
      const saved = editingVoucher
        ? await promotionsApi.updateVoucher(editingVoucher.id, { ...payload, version: editingVoucher.version })
        : isAdmin ? await promotionsApi.createVoucher(payload) : await promotionsApi.createStoreProposal(payload);
      setVouchers(previous => editingVoucher
        ? previous.map(voucher => voucher.id === saved.id ? saved : voucher)
        : [saved, ...previous]);
      if (selectedVoucher?.id === saved.id) setSelectedVoucher(saved);
      await fetchVouchers();
      setModalOpen(false);
      if (!isAdmin) { setActiveTab('approvals'); setReviewStatus('PENDING_APPROVAL'); setReviewSearch(''); }
      message.success(t(isAdmin ? 'loyalty:saved' : 'admin:voucherUi.proposed'));
    } catch (error) {
      message.error(getErrorMessage(error));
      await fetchVouchers();
    } finally {
      mutationLock.current = false;
      setActionLoading(false);
    }
  };

  // Toggle active / disabled status
  const handleToggleStatus = async (voucher: AdminVoucher) => {
    if (!isAdmin || !voucherState(voucher).canToggle || mutationLock.current) return;
    mutationLock.current = true;
    setActionError('');
    setActionLoading(true);
    const newStatus: VoucherStatus = voucher.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
    const titlePayload = toLocalizedPayload(voucher.titleI18n, voucher.title);
    const descriptionPayload = toLocalizedPayload(voucher.descriptionI18n, voucher.description);
    try {
        const saved = await promotionsApi.updateVoucher(voucher.id, {
          version: voucher.version,
          code: voucher.campaignKind === 'LEGACY' ? voucher.code : null,
          applicability: voucher.applicability || (voucher.scope === 'STORE' ? 'SELECTED' : 'ALL'),
          storeIds: voucher.storeIds?.length ? voucher.storeIds : voucher.storeId ? [Number(voucher.storeId)] : [],
          title: titlePayload ?? voucher.title,
          titleI18n: titlePayload ?? null,
          description: descriptionPayload ?? null,
          descriptionI18n: descriptionPayload ?? null,
          type: voucher.type,
          value: voucher.value,
          minOrderAmount: voucher.minOrderAmount,
          maxDiscount: voucher.maxDiscount,
          totalLimit: voucher.totalLimit,
          startAt: voucher.startAt,
          endAt: voucher.endAt,
          scope: voucher.scope,
          storeId: voucher.storeId ? Number(voucher.storeId) : null,
          status: newStatus,
          printable: Boolean(voucher.printable),
          maxPrintRatio: voucher.maxPrintRatio ?? 0.5,
          maxPrintPerStore: voucher.maxPrintPerStore ?? null,
        });

      setVouchers((prev) =>
        prev.map((v) =>
          v.id === voucher.id
            ? saved
            : v
        )
      );

      if (selectedVoucher && selectedVoucher.id === voucher.id) {
        setSelectedVoucher((prev) =>
          prev ? saved : null
        );
      }

      message.success(
        newStatus === 'ACTIVE'
          ? t('admin:voucherUi.campaignActivated')
          : t('admin:voucherUi.campaignDisabled')
      );
      await fetchVouchers();
    } catch (err) {
      setActionError(getErrorMessage(err));
      message.error(getErrorMessage(err));
      await fetchVouchers();
    } finally {
      mutationLock.current = false;
      setActionLoading(false);
    }
  };

  // Delete voucher
  const handleApproveVoucher = async (voucher: AdminVoucher) => {
    if (!isAdmin || !voucherState(voucher).canApprove || mutationLock.current) return;
    mutationLock.current = true;
    setActionLoading(true);
    setActionError('');
    try {
      const saved = await promotionsApi.approveVoucher(voucher.id);
      setSelectedVoucher(saved);
      message.success(t('admin:voucherUi.approved'));
      await fetchVouchers();
    } catch (error) {
      setActionError(getErrorMessage(error));
      await fetchVouchers();
    } finally {
      mutationLock.current = false;
      setActionLoading(false);
    }
  };

  const handleRejectVoucher = async (voucher: AdminVoucher, reason: string) => {
    if (!isAdmin || !voucherState(voucher).canApprove || mutationLock.current) return;
    mutationLock.current = true;
    setActionLoading(true);
    setActionError('');
    try {
      const saved = await promotionsApi.rejectVoucher(voucher.id, reason.trim(), voucher.version);
      setSelectedVoucher(saved);
      message.success(t('admin:voucherUi.rejected'));
      await fetchVouchers();
    } catch (error) {
      setActionError(getErrorMessage(error));
      await fetchVouchers();
    } finally {
      mutationLock.current = false;
      setActionLoading(false);
    }
  };

  const handleDeleteVoucher = async (voucher: AdminVoucher) => {
    if (!isAdmin || voucherState(voucher).isProposal || mutationLock.current) return;
    mutationLock.current = true;
    setActionLoading(true);
    try {
      await promotionsApi.deleteVoucher(voucher.id);

      await fetchVouchers();
      setDrawerOpen(false);
      message.success(t('admin:voucherUi.deleteRequested'));
    } catch (err) {
      message.error(getErrorMessage(err));
    } finally {
      mutationLock.current = false;
      setActionLoading(false);
    }
  };

  // Harmonious, carefully balanced Table Columns
  const columns: TableColumnsType<AdminVoucher> = [
    {
      title: t('admin:voucherUi.campaignColumn'),
      dataIndex: 'code',
      key: 'code',
      width: 200,
      render: (_, record) => (
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            {record.campaignKind === 'PERSONAL' || !record.code
              ? <Tag color="red">{t('admin:voucherUi.personalCampaign')}</Tag>
              : <VoucherBadge code={record.code} type={record.type} value={record.value}
                  status={record.effectiveStatus || record.status} size="medium" />}
          </div>
            {record.campaignKind === 'LEGACY' && record.code && <Tooltip title={t('admin:voucherUi.copyLegacyCode')}>
              <Button
                type="text"
                size="small"
                aria-label={t('admin:voucherUi.copyCodeAria', { code: record.code })}
                icon={<CopyOutlined style={{ color: '#8F1216', fontSize: 13 }} />}
                onClick={() => handleCopyCode(record.code!)}
                style={{ width: 24, height: 24, padding: 0, flexShrink: 0 }}
              />
            </Tooltip>}
        </div>
      ),
    },
    {
      title: t('admin:voucherUi.programTermsColumn'),
      key: 'title',
      width: 280,
      render: (_, record) => {
        const resolvedTitle = pickLocalized(record.titleI18n, record.title, lang);
        const resolvedDescription = pickLocalized(record.descriptionI18n, record.description, lang);
        return (
          <div style={{ maxWidth: 280 }}>
            <div
              style={{
                fontFamily: 'Baloo 2, sans-serif',
                fontWeight: 700,
                fontSize: 15,
                color: '#16100D',
                lineHeight: 1.3,
              }}
            >
              {resolvedTitle || record.code || `#${record.id}`}
            </div>
            {resolvedDescription && (
              <div
                style={{
                  color: '#6B6259',
                  fontSize: 12,
                  marginTop: 4,
                  lineHeight: 1.45,
                  display: '-webkit-box',
                  WebkitLineClamp: 2,
                  WebkitBoxOrient: 'vertical',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                }}
                title={resolvedDescription}
              >
                {resolvedDescription}
              </div>
            )}
          </div>
        );
      },
    },
    {
      title: t('admin:voucherUi.discountColumn'),
      key: 'discount',
      width: 170,
      render: (_, record) => {
        const isPercent = record.type === 'PERCENT';
        return (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <Tag
              bordered={false}
              style={{
                alignSelf: 'flex-start',
                padding: '3px 10px',
                borderRadius: 8,
                fontWeight: 800,
                fontSize: 13,
                fontFamily: 'Quicksand, sans-serif',
                backgroundColor: isPercent ? '#FFF1F2' : '#FEF3C7',
                color: isPercent ? '#E11D48' : '#B45309',
                border: `1px solid ${isPercent ? '#FECDD3' : '#FDE68A'}`,
              }}
            >
              {t('admin:voucherUi.discountBadge', {
                value: isPercent ? `${record.value}%` : `${record.value.toLocaleString(numberLocale)} ${currencyLabel}`,
              })}
            </Tag>
            <div style={{ fontSize: 11, color: '#6B6259', marginTop: 2, lineHeight: 1.3 }}>
              <span>{t('admin:voucherUi.minOrderShort')} <b>{record.minOrderAmount ? `${record.minOrderAmount.toLocaleString(numberLocale)} ${currencyLabel}` : `0 ${currencyLabel}`}</b></span>
              {isPercent && record.maxDiscount ? (
                <div>{t('admin:voucherUi.maxDiscountShort')} <b>{record.maxDiscount.toLocaleString(numberLocale)} {currencyLabel}</b></div>
              ) : null}
            </div>
          </div>
        );
      },
    },
    {
      title: t('admin:voucherUi.scopeColumn'),
      key: 'scope',
      width: 175,
      render: (_, record) => {
        if (record.applicability === 'SELECTED' || record.scope === 'STORE') {
          const storeTitleList = (record.storeIds?.length
            ? record.storeIds.map(id => {
                const matched = stores.find(store => store.id === Number(id));
                return matched ? pickLocalized(matched.nameI18n, matched.name, lang) : `#${id}`;
              }).join(', ')
            : voucherStoreName(record, stores, lang)) || t('admin:voucherUi.storeUnavailable');
          return (
            <Tag
              icon={<ShopOutlined />}
              bordered={false}
              style={{
                borderRadius: 999,
                padding: '4px 10px',
                fontWeight: 700,
                fontSize: 12,
                backgroundColor: '#E0F2FE',
                color: '#0369A1',
                border: '1px solid #BAE6FD',
                maxWidth: 165,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
              title={storeTitleList}
            >
              {record.storeIds?.length ? t('admin:voucherUi.branchCount', { count: record.storeIds.length })
                : voucherStoreName(record, stores, lang) || t('admin:voucherUi.storeUnavailable')}
            </Tag>
          );
        }
        return (
          <Tag
            icon={<GlobalOutlined />}
            bordered={false}
            style={{
              borderRadius: 999,
              padding: '4px 10px',
              fontWeight: 700,
              fontSize: 12,
              backgroundColor: '#F3E8FF',
              color: '#6B21A8',
              border: '1px solid #E9D5FF',
            }}
          >
            {t('admin:promotions.scope.all')}
          </Tag>
        );
      },
    },
    {
      title: t('admin:voucherUi.validityColumn'),
      key: 'validity',
      width: 195,
      render: (_, record) => (
        <div style={{ fontSize: 12, color: '#16100D', lineHeight: 1.5 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <CalendarOutlined style={{ color: '#8F1216', fontSize: 12 }} />
            <span>{t('admin:voucherUi.fromShort')} <b>{dayjs(record.startAt).format('DD/MM/YYYY')}</b> {dayjs(record.startAt).format('HH:mm')}</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: '#6B6259', marginTop: 2 }}>
            <ClockCircleOutlined style={{ fontSize: 12 }} />
            <span>{t('admin:voucherUi.toShort')} <b>{dayjs(record.endAt).format('DD/MM/YYYY')}</b> {dayjs(record.endAt).format('HH:mm')}</span>
          </div>
        </div>
      ),
    },
    {
      title: t('admin:voucherUi.globalProgress'),
      key: 'usage',
      width: 145,
      render: (_, record) => {
        const limit = record.maxUsage ?? record.totalLimit;
        const used = record.usage ?? record.usedCount;
        const percent = limit && limit > 0
          ? Math.min(100, Math.round((used / limit) * 100))
          : null;
        return (
          <div style={{ width: '100%', maxWidth: 125 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, marginBottom: 3 }}>
              <span style={{ fontWeight: 700, color: '#8F1216', fontFamily: 'var(--font-heading)', fontSize: 14 }}>
                {used}
              </span>
              <span style={{ color: '#6B6259', fontWeight: 600 }}>
                / {limit && limit > 0 ? limit : '∞'}
              </span>
            </div>
            <Text type="secondary" style={{ fontSize: 11 }}>{t("admin:ui.reserved")} {record.reservedCount ?? 0}</Text>
            {record.printable && (
              <div style={{ marginTop: 3 }}>
                <Tag color="gold" style={{ fontSize: 10, lineHeight: '16px', marginInlineEnd: 0 }}>
                  {t('admin:voucherPrinting.printableBadge')}: {record.printedCount ?? 0}/{record.maxPrintableLimit ?? 0}
                </Tag>
              </div>
            )}
            {percent !== null ? (
              <Progress
                percent={percent}
                size={['100%', 5]}
                showInfo={false}
                strokeColor="#8F1216"
                trailColor="#E8E2D9"
              />
            ) : (
              <div style={{ fontSize: 11, color: '#6B6259', fontStyle: 'italic' }}>{t("admin:ui.unlimited")}</div>
            )}
          </div>
        );
      },
    },
    {
      title: t("accounts:status"),
      key: 'status',
      width: 135,
      render: (_, record) => {
        const state = voucherState(record);
        return <Tag color={state.color}>{t(`admin:voucherUi.states.${state.status}`)}</Tag>;
      },
    },
    {
      title: t("accounts:action"),
      key: 'actions',
      width: 150,
      fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Tooltip title={t("business:orderspage.viewDetails")}>
            <Button
              type="text"
              size="small"
              className="voucher-action-btn"
              icon={<EyeOutlined style={{ color: '#0284C7', fontSize: 14 }} />}
              onClick={() => handleOpenDetail(record)}
              aria-label={t("business:orderspage.viewDetails")}
            />
          </Tooltip>
          {record.printable && (record.effectiveStatus || record.status) === 'ACTIVE' && (
            <Tooltip title={t('admin:voucherPrinting.printAction')}>
              <Button
                type="text"
                size="small"
                className="voucher-action-btn"
                icon={<PrinterOutlined style={{ color: '#16100D', fontSize: 14 }} />}
                onClick={() => handleOpenPrint(record)}
                aria-label={t('admin:voucherPrinting.printAction')}
              />
            </Tooltip>
          )}
          {isAdmin && voucherState(record).canToggle && <Tooltip title={t('admin:voucherUi.editAction')}>
            <Button
              type="text"
              size="small"
              className="voucher-action-btn"
              icon={<EditOutlined style={{ color: '#8F1216', fontSize: 14 }} />}
              onClick={() => handleOpenEdit(record)}
              aria-label={t('admin:voucherUi.editAction')}
            />
          </Tooltip>}
          {isAdmin && !voucherState(record).isProposal && <Popconfirm
            title={t('admin:voucherUi.confirmDeleteShort')}
            description={t('admin:voucherUi.deleteCampaignHint')}
            okText={t("admin:ui.delete")}
            cancelText={t("business:account.cancel")}
            okButtonProps={{ danger: true }}
            onConfirm={() => handleDeleteVoucher(record)}
          >
            <Tooltip title={t("admin:ui.delete")}>
              <Button
                type="text"
                size="small"
                danger
                className="voucher-action-btn"
                icon={<DeleteOutlined style={{ fontSize: 14 }} />}
                aria-label={t('admin:voucherUi.deleteVoucherAria')}
                disabled={actionLoading}
              />
            </Tooltip>
          </Popconfirm>}
        </Space>
      ),
    },
  ];

  return (
    <div className="promotions-wrapper">
      {/* Header section */}
      <div className="promotions-header">
        <div>
          <span className="eyebrow">{t('admin:voucherUi.eyebrow')}</span>
          <Title level={2} className="title">
            {t('admin:voucherUi.pageTitle')}
          </Title>
          <div className="subtitle">
            {t('admin:voucherUi.pageSubtitle')}
          </div>
        </div>

        <Space wrap>
        <Button icon={<ReloadOutlined />} disabled={loading || actionLoading} onClick={fetchVouchers}>{t('admin:voucherUi.refresh')}</Button>
        {(activeTab === 'vouchers' || (!isAdmin && activeTab === 'approvals')) && (
          <Button
            type="primary"
            icon={<PlusOutlined />}
            style={{
              backgroundColor: '#8F1216',
              borderColor: '#8F1216',
              borderRadius: 999,
              fontWeight: 700,
              height: 42,
              padding: '0 24px',
              boxShadow: '0 4px 14px rgba(143, 18, 22, 0.2)',
            }}
            onClick={handleOpenCreate}
            disabled={actionLoading || (!isAdmin && !user.storeId)}
          >
            {isAdmin ? t('admin:voucherUi.createVoucherBtn') : t('admin:voucherUi.propose')}
          </Button>
        )}
        </Space>
      </div>

      {!isAdmin && !user.storeId && <Alert type="warning" showIcon
        title={t('admin:voucherUi.managerStoreRequired')}
        description={t('admin:voucherUi.managerStoreRequiredHint')} />}
      {storeError && <Alert type="warning" showIcon title={t('admin:voucherUi.storeUnavailable')} description={storeError}
        action={<Button onClick={fetchStores}>{t('common:retry')}</Button>} />}

      {/* Main Tabs */}
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        type="card"
        className="promotions-tabs"
        items={[
          ...(isAdmin ? [{ key: 'loyalty', label: t('loyalty:title'), children: <LoyaltySettings /> }] : []),
          {
            key: 'vouchers',
            label: (
              <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <GiftOutlined />
                <span>{t('admin:voucherUi.vouchersTabTitle')}</span>
                <Badge
                  count={liveVouchers.length}
                  style={{
                    backgroundColor: activeTab === 'vouchers' ? '#FFFAF3' : '#8F1216',
                    color: activeTab === 'vouchers' ? '#8F1216' : '#FFFAF3',
                    fontWeight: 700,
                    boxShadow: 'none',
                  }}
                />
              </span>
            ),
            children: loadError ? <Alert type="error" showIcon title={loadError} action={<Button onClick={fetchVouchers}>{t('common:retry')}</Button>} /> : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                {/* Quick KPI Overview Cards */}
                <div className="promotions-kpi-grid">
                  <div
                    className={`promotions-kpi-card ${statusFilter === 'ALL' ? 'active' : ''}`}
                    onClick={() => setStatusFilter('ALL')}
                  >
                    <div>
                      <div className="kpi-label">{t('admin:voucherUi.totalVouchers')}</div>
                      <div className="kpi-value">{kpiStats.total}</div>
                    </div>
                    <div className="kpi-icon" style={{ background: '#F7E5DF', color: '#8F1216' }}>
                      <GiftOutlined />
                    </div>
                  </div>

                  <div
                    className={`promotions-kpi-card ${statusFilter === 'ACTIVE' ? 'active' : ''}`}
                    onClick={() => setStatusFilter('ACTIVE')}
                  >
                    <div>
                      <div className="kpi-label">{t("admin:ui.active")}</div>
                      <div className="kpi-value" style={{ color: '#1E7A46' }}>{kpiStats.active}</div>
                    </div>
                    <div className="kpi-icon" style={{ background: '#ECFDF5', color: '#10B981' }}>
                      <ThunderboltOutlined />
                    </div>
                  </div>

                  <div
                    className={`promotions-kpi-card ${statusFilter === 'UPCOMING' ? 'active' : ''}`}
                    onClick={() => setStatusFilter('UPCOMING')}
                  >
                    <div>
                      <div className="kpi-label">{t('admin:voucherUi.upcomingKpi')}</div>
                      <div className="kpi-value" style={{ color: '#0369A1' }}>{kpiStats.upcoming}</div>
                    </div>
                    <div className="kpi-icon" style={{ background: '#E0F2FE', color: '#0284C7' }}>
                      <CalendarOutlined />
                    </div>
                  </div>

                  <div
                    className={`promotions-kpi-card ${statusFilter === 'INACTIVE' ? 'active' : ''}`}
                    onClick={() => setStatusFilter('INACTIVE')}
                  >
                    <div>
                      <div className="kpi-label">{t('admin:voucherUi.inactiveFilter')}</div>
                      <div className="kpi-value" style={{ color: '#6B6259' }}>{kpiStats.inactive}</div>
                    </div>
                    <div className="kpi-icon" style={{ background: '#F5EDE0', color: '#78716C' }}>
                      <StopOutlined />
                    </div>
                  </div>
                </div>

                {/* Table with balanced columns and horizontal scroll protection */}
                <div className="voucher-admin-table">
                  <AdminTable<AdminVoucher>
                    columns={columns}
                    dataSource={filteredVouchers}
                    rowKey="id"
                    loading={loading}
                    scroll={{ x: 1260 }}
                    searchPlaceholder={t('admin:voucherUi.searchVouchersPlaceholder')}
                    searchValue={searchTerm}
                    onSearchChange={setSearchTerm}
                    filterNodes={
                      <Space wrap size={10}>
                        {/* Scope Filter */}
                        <Select
                          value={scopeFilter}
                          onChange={setScopeFilter}
                          style={{ width: 175 }}
                          options={[
                            { value: 'ALL', label: t('admin:voucherUi.allScopes') },
                            { value: 'SCOPE_ALL', label: <Space size={6}><GlobalOutlined />{t('admin:promotions.scope.all')}</Space> },
                            { value: 'SCOPE_STORE', label: <Space size={6}><ShopOutlined />{t('admin:promotions.scope.store')}</Space> },
                          ]}
                        />

                        {/* Store Filter (for Admin) */}
                        {isAdmin && (
                          <Select
                            value={storeFilter}
                            onChange={setStoreFilter}
                            style={{ width: 215 }}
                            options={[
                              { value: 'ALL', label: t("business:stores.allStores") },
                              ...stores.map((s) => ({
                                value: s.id,
                                label: <Space size={6}><ShopOutlined />{pickLocalized(s.nameI18n, s.name, lang)}</Space>,
                              })),
                            ]}
                          />
                        )}

                        {/* Status Filter */}
                        <Select
                          value={statusFilter}
                          onChange={setStatusFilter}
                          style={{ width: 170 }}
                          options={[
                            { value: 'ALL', label: t("business:paymentspage.allStatuses") },
                            { value: 'ACTIVE', label: <Space size={6}><CheckCircleOutlined />{t('admin:voucherUi.runningStatus')}</Space> },
                            { value: 'UPCOMING', label: <Space size={6}><CalendarOutlined />{t('admin:voucherUi.upcomingStatus')}</Space> },
                            { value: 'EXPIRED', label: <Space size={6}><ClockCircleOutlined />{t('admin:voucherUi.expiredStatus')}</Space> },
                            { value: 'INACTIVE', label: <Space size={6}><StopOutlined />{t('admin:voucherUi.inactiveFilter')}</Space> },
                            { value: 'EXHAUSTED', label: <Space size={6}><StopOutlined />{t('admin:voucherUi.states.EXHAUSTED')}</Space> },
                            { value: 'DISABLED', label: <Space size={6}><StopOutlined />{t('admin:voucherUi.states.DISABLED')}</Space> },
                          ]}
                        />

                        {/* Reset Filter Button */}
                        {hasActiveFilters && (
                          <Button
                            icon={<ReloadOutlined />}
                            onClick={handleResetFilters}
                            style={{ borderRadius: 999, fontSize: 13 }}
                          >
                            {t('admin:voucherUi.resetFilters')}
                          </Button>
                        )}
                      </Space>
                    }
                  />
                </div>
              </div>
            ),
          },
          ...(isAdmin
            ? [
                {
                  key: 'banners',
                  label: (
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                      <PictureOutlined />
                      {t('admin:promotions.tabs.banners')}
                    </span>
                  ),
                  children: <BannerManagementSection />,
                },
              ]
            : []),
          {
            key: 'print-batches',
            label: (
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                <PrinterOutlined />
                {t('admin:voucherPrinting.printTab')}
              </span>
            ),
            children: (
              <PrintBatchesSection
                stores={stores}
                refreshToken={printBatchesNonce}
                onChanged={() => {
                  void fetchVouchers();
                }}
              />
            ),
          },
          {
            key: 'approvals',
            label: <span className={`voucher-review-tab${pendingCount > 0 && !loadError ? ' has-pending' : ''}`}>
              <CheckCircleOutlined />
              {t(isAdmin ? 'admin:voucherUi.reviewTab' : 'admin:voucherUi.proposalsTab')}
              {!loadError && <Badge count={pendingCount} overflowCount={9} showZero className="voucher-review-count"
                title={t('admin:voucherUi.pendingCount', { count: pendingCount })} />}
            </span>,
            children: loadError ? <Alert type="error" showIcon title={loadError} action={<Button onClick={fetchVouchers}>{t('common:retry')}</Button>} /> : (
              <div className="voucher-admin-table">
                <AdminTable<AdminVoucher> rowKey="id" loading={loading} dataSource={reviewRows} scroll={{ x: 1100 }}
                  columns={[
                    ...columns.filter(column => column.key !== 'actions'),
                    { key: 'reason', title: t('admin:voucherUi.rejectionReason'), width: 240,
                      render: (_, voucher) => <span className="voucher-review-reason">{voucher.rejectionReason || '—'}</span> },
                    { key: 'review', title: t('accounts:action'), width: 140, fixed: 'right', render: (_, voucher) => (
                      <Button type={isAdmin && voucher.status === 'PENDING_APPROVAL' ? 'primary' : 'default'}
                        icon={<EyeOutlined />} disabled={actionLoading} onClick={() => handleOpenDetail(voucher)}>
                        {t(isAdmin && voucher.status === 'PENDING_APPROVAL' ? 'admin:voucherUi.review' : 'business:orderspage.viewDetails')}
                      </Button>
                    ) },
                  ]}
                  searchValue={reviewSearch} onSearchChange={setReviewSearch} searchPlaceholder={t('admin:voucherUi.reviewSearch')}
                  filterNodes={<Space wrap>
                    <Select value={reviewStatus} onChange={setReviewStatus} style={{ width: 180 }} aria-label={t('accounts:status')}
                      options={['ALL', 'PENDING_APPROVAL', 'REJECTED'].map(value => ({ value, label: t(value === 'ALL' ? 'business:paymentspage.allStatuses' : `admin:voucherUi.states.${value}`) }))} />
                    {isAdmin && <Select value={reviewStore} onChange={setReviewStore} style={{ width: 230 }} aria-label={t('business:stores.store')}
                      options={[{ value: 'ALL', label: t('business:stores.allStores') }, ...stores.map(store => ({ value: store.id, label: pickLocalized(store.nameI18n, store.name, lang) }))]} />}
                    {(reviewSearch || reviewStore !== 'ALL' || reviewStatus !== (isAdmin ? 'PENDING_APPROVAL' : 'ALL')) && <Button onClick={() => { setReviewSearch(''); setReviewStore('ALL'); setReviewStatus(isAdmin ? 'PENDING_APPROVAL' : 'ALL'); }}>{t('business:orderspage.clearFilters')}</Button>}
                  </Space>}
                />
              </div>
            ),
          },
        ]}
      />

      {/* Create / Edit Modal */}
      <VoucherModal
        open={modalOpen}
        editingVoucher={editingVoucher}
        stores={stores}
        loading={actionLoading}
        onCancel={() => setModalOpen(false)}
        onSubmit={handleSubmitVoucher}
      />

      {/* Voucher Detail Drawer */}
      <VoucherDetailDrawer
        open={drawerOpen}
        voucher={selectedVoucher}
        storeName={selectedVoucher ? voucherStoreName(selectedVoucher, stores, lang) : undefined}
        stores={stores}
        isAdmin={isAdmin}
        loading={actionLoading}
        error={actionError}
        onApprove={handleApproveVoucher}
        onReject={handleRejectVoucher}
        onClose={() => { if (!mutationLock.current) setDrawerOpen(false); }}
        onEdit={(v) => {
          setDrawerOpen(false);
          handleOpenEdit(v);
        }}
        onToggleStatus={handleToggleStatus}
        onDelete={(v) => {
          handleDeleteVoucher(v);
        }}
        onPrint={handleOpenPrint}
      />

      <PrintVoucherModal
        open={printModalOpen}
        voucher={printingVoucher}
        stores={stores}
        onCancel={() => {
          setPrintModalOpen(false);
          setPrintingVoucher(null);
        }}
        onSuccess={async () => {
          setPrintBatchesNonce((n) => n + 1);
          await fetchVouchers();
        }}
      />
    </div>
  );
};

export default PromotionsPage;
