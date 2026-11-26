import { message } from "../../../components/feedback";
import { useTranslation } from "react-i18next";
import React, { useState, useMemo } from 'react';
import { Alert, Tag, Button, Space, Modal, Typography, Select, Tooltip } from "antd";
import { TableColumnsType } from "antd";
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ClockCircleOutlined,
  ShopOutlined,
  InfoCircleOutlined,
  BulbOutlined,
  CopyOutlined,
  ReloadOutlined,
  UserOutlined,
  CalendarOutlined,
} from '@ant-design/icons';
import { AdminTable } from '../../../components/ui/AdminTable';
import { MOCK_PROPOSALS } from '../../../mocks/promotionData';
import type { VoucherProposal } from '../../../types/promotion';

const { Text, Title, Paragraph } = Typography;

export const ProposalBacklogSection: React.FC = () => {
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage || i18n.language || 'vi';
  const currencyLabel = t('common:currency');
  const numberLocale = lang.startsWith('en') ? 'en-US' : 'vi-VN';
  const [proposals, setProposals] = useState<VoucherProposal[]>(MOCK_PROPOSALS);
  const [searchTerm, setSearchTerm] = useState<string>('');
  const [statusFilter, setStatusFilter] = useState<string>('ALL');
  const [storeFilter, setStoreFilter] = useState<number | 'ALL'>('ALL');

  const [modalInfo, setModalInfo] = useState<{
    open: boolean;
    title: string;
    content: string;
  }>({
    open: false,
    title: '',
    content: '',
  });

  const handleAction = (proposal: VoucherProposal, action: 'APPROVE' | 'REJECT') => {
    setModalInfo({
      open: true,
      title: action === 'APPROVE'
        ? t('admin:voucherProposalDemo.approvePreviewTitle')
        : t('admin:voucherProposalDemo.rejectPreviewTitle'),
      content: t('admin:voucherProposalDemo.demoActionContent', {
        action: action === 'APPROVE'
          ? t('admin:voucherProposalDemo.approveVerb')
          : t('admin:voucherProposalDemo.rejectVerb'),
        code: proposal.code,
        store: proposal.storeName,
      }),
    });

    // Update local state for interactive demo experience
    setProposals((prev) =>
      prev.map((item) =>
        item.id === proposal.id
          ? { ...item, status: action === 'APPROVE' ? 'APPROVED' : 'REJECTED' }
          : item
      )
    );
  };

  const handleCopyCode = async (code: string) => {
    try {
      await navigator.clipboard.writeText(code);
      message.success(t('admin:voucherProposalDemo.copiedProposalCode', { code }));
    } catch {
      message.error(t("common:feedback.copyFailed"));
    }
  };

  // Status counts for KPI cards
  const pendingCount = proposals.filter((p) => p.status === 'PENDING_APPROVAL').length;
  const approvedCount = proposals.filter((p) => p.status === 'APPROVED').length;
  const rejectedCount = proposals.filter((p) => p.status === 'REJECTED').length;

  // Extract unique stores for store filter dropdown
  const storeOptions = useMemo(() => {
    const map = new Map<number, string>();
    proposals.forEach((p) => {
      map.set(p.storeId, p.storeName);
    });
    return Array.from(map.entries()).map(([id, name]) => ({
      value: id,
      label: `🏪 ${name}`,
    }));
  }, [proposals]);

  // Filtered proposals
  const filteredProposals = useMemo(() => {
    return proposals.filter((p) => {
      // 1. Status filter
      if (statusFilter !== 'ALL' && p.status !== statusFilter) {
        return false;
      }

      // 2. Store filter
      if (storeFilter !== 'ALL' && p.storeId !== storeFilter) {
        return false;
      }

      // 3. Keyword search
      if (searchTerm.trim()) {
        const term = searchTerm.toLowerCase();
        const codeMatch = p.code.toLowerCase().includes(term);
        const titleMatch = p.title.toLowerCase().includes(term);
        const storeMatch = p.storeName.toLowerCase().includes(term);
        const reasonMatch = p.proposalReason.toLowerCase().includes(term);
        const proposerMatch = p.proposedBy.toLowerCase().includes(term);
        if (!codeMatch && !titleMatch && !storeMatch && !reasonMatch && !proposerMatch) {
          return false;
        }
      }

      return true;
    });
  }, [proposals, statusFilter, storeFilter, searchTerm]);

  const hasActiveFilters =
    searchTerm.trim() !== '' || statusFilter !== 'ALL' || storeFilter !== 'ALL';

  const handleResetFilters = () => {
    setSearchTerm('');
    setStatusFilter('ALL');
    setStoreFilter('ALL');
  };

  const columns: TableColumnsType<VoucherProposal> = [
    {
      title: t('admin:voucherProposalDemo.proposalCodeColumn'),
      key: 'code',
      width: 310,
      render: (_, record) => (
        <div style={{ maxWidth: 300 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
            <span
              style={{
                fontFamily: 'Quicksand, sans-serif',
                fontWeight: 800,
                fontSize: 14,
                color: '#8F1216',
                backgroundColor: '#F7E5DF',
                padding: '2px 8px',
                borderRadius: 6,
                letterSpacing: '0.04em',
              }}
            >
              {record.code}
            </span>
            <Tooltip title={t('admin:voucherProposalDemo.copyCodeTooltip')}>
              <Button
                type="text"
                size="small"
                icon={<CopyOutlined style={{ color: '#8F1216', fontSize: 12 }} />}
                onClick={() => handleCopyCode(record.code)}
                style={{ width: 22, height: 22, padding: 0 }}
              />
            </Tooltip>
          </div>
          <div
            style={{
              fontFamily: 'var(--font-heading)',
              fontWeight: 700,
              fontSize: 15,
              color: '#16100D',
              lineHeight: 1.3,
            }}
          >
            {record.title}
          </div>
          <div
            style={{
              fontSize: 12,
              color: '#6B6259',
              marginTop: 4,
              lineHeight: 1.45,
              backgroundColor: '#FFFDF9',
              border: '1px dashed #E8E2D9',
              borderRadius: 8,
              padding: '4px 8px',
            }}
          >
            <span style={{ fontWeight: 600, color: '#C9A227' }}>{t('admin:voucherProposalDemo.reasonPrefix')}</span>{' '}
            {record.proposalReason}
          </div>
        </div>
      ),
    },
    {
      title: t('admin:voucherProposalDemo.storeSenderColumn'),
      key: 'store',
      width: 250,
      render: (_, record) => (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
          <Tag
            icon={<ShopOutlined />}
            bordered={false}
            style={{
              alignSelf: 'flex-start',
              borderRadius: 999,
              padding: '3px 10px',
              fontWeight: 700,
              fontSize: 12,
              backgroundColor: '#E0F2FE',
              color: '#0369A1',
              border: '1px solid #BAE6FD',
            }}
          >
            {record.storeName}
          </Tag>
          <div style={{ fontSize: 12, color: '#16100D', display: 'flex', alignItems: 'center', gap: 6 }}>
            <UserOutlined style={{ color: '#6B6259', fontSize: 11 }} />
            <span style={{ fontWeight: 600 }}>{record.proposedBy}</span>
          </div>
          <div style={{ fontSize: 11, color: '#6B6259' }}>
            <CalendarOutlined style={{ marginRight: 4 }} />
            {record.proposedAt}
          </div>
        </div>
      ),
    },
    {
      title: t('admin:voucherProposalDemo.offerTermsColumn'),
      key: 'discount',
      width: 190,
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
            <div style={{ fontSize: 11, color: '#6B6259', lineHeight: 1.4 }}>
              <div>{t('admin:voucherProposalDemo.orderFrom')} <b>{record.minOrderAmount.toLocaleString(numberLocale)} {currencyLabel}</b></div>
              {record.totalLimit ? (
                <div>{t('admin:voucherProposalDemo.quotaAmount')} <b>{t('admin:voucherProposalDemo.quotaUnit', { count: record.totalLimit })}</b></div>
              ) : null}
            </div>
          </div>
        );
      },
    },
    {
      title: t('admin:voucherProposalDemo.statusColumn'),
      key: 'status',
      width: 160,
      render: (_, record) => {
        if (record.status === 'PENDING_APPROVAL') {
          return (
            <span
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 6,
                padding: '4px 10px',
                borderRadius: 999,
                backgroundColor: '#FEF3C7',
                color: '#92400E',
                border: '1px solid #FDE68A',
                fontSize: 12,
                fontWeight: 700,
                fontFamily: 'Quicksand, sans-serif',
              }}
            >
              <ClockCircleOutlined style={{ color: '#D97706' }} />
              {t('admin:voucherProposalDemo.pendingStatus')}
            </span>
          );
        }
        if (record.status === 'APPROVED') {
          return (
            <span
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 6,
                padding: '4px 10px',
                borderRadius: 999,
                backgroundColor: '#ECFDF5',
                color: '#065F46',
                border: '1px solid #A7F3D0',
                fontSize: 12,
                fontWeight: 700,
                fontFamily: 'Quicksand, sans-serif',
              }}
            >
              <CheckCircleOutlined style={{ color: '#10B981' }} />
              {t('admin:voucherProposalDemo.approvedStatus')}
            </span>
          );
        }
        return (
          <span
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              padding: '4px 10px',
              borderRadius: 999,
              backgroundColor: '#FEF2F2',
              color: '#991B1B',
              border: '1px solid #FECACA',
              fontSize: 12,
              fontWeight: 700,
              fontFamily: 'Quicksand, sans-serif',
            }}
          >
            <CloseCircleOutlined style={{ color: '#EF4444' }} />
            {t('admin:voucherProposalDemo.rejectedStatus')}
          </span>
        );
      },
    },
    {
      title: t('admin:voucherProposalDemo.reviewActionsColumn'),
      key: 'actions',
      width: 175,
      fixed: 'right',
      render: (_, record) => (
        <Space size={8}>
          <Button
            size="small"
            type="primary"
            icon={<CheckCircleOutlined />}
            disabled={record.status !== 'PENDING_APPROVAL'}
            style={{
              backgroundColor: record.status === 'PENDING_APPROVAL' ? '#1E7A46' : undefined,
              borderColor: record.status === 'PENDING_APPROVAL' ? '#1E7A46' : undefined,
              borderRadius: 999,
              fontWeight: 700,
              fontSize: 12,
            }}
            onClick={() => handleAction(record, 'APPROVE')}
          >
            {t('admin:voucherProposalDemo.approveAction')}
          </Button>
          <Button
            size="small"
            danger
            icon={<CloseCircleOutlined />}
            disabled={record.status !== 'PENDING_APPROVAL'}
            style={{
              borderRadius: 999,
              fontWeight: 700,
              fontSize: 12,
            }}
            onClick={() => handleAction(record, 'REJECT')}
          >
            {t('admin:voucherProposalDemo.rejectAction')}
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* Explicit Backlog Status Alert */}
      <Alert
        message={
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <span style={{ fontWeight: 800, fontSize: 15, color: '#92400E' }}>
              {t('admin:voucherProposalDemo.alertTitle')}
            </span>
            <Tag color="orange" style={{ fontWeight: 700, borderRadius: 999, margin: 0 }}>
              {t('admin:voucherProposalDemo.alertBadge')}
            </Tag>
            <Tag color="default" style={{ borderRadius: 999, margin: 0 }}>
              {t('admin:voucherProposalDemo.alertMockTag')}
            </Tag>
          </div>
        }
        description={
          <div style={{ marginTop: 6, fontSize: 13, lineHeight: 1.6, color: '#78350F' }}>
            {t('admin:voucherProposalDemo.alertDescription')}
          </div>
        }
        type="warning"
        showIcon
        icon={<BulbOutlined style={{ color: '#D97706', fontSize: 18 }} />}
        style={{
          borderRadius: 16,
          border: '1px solid #FDE68A',
          backgroundColor: '#FFFBEB',
          padding: '16px 20px',
        }}
      />

      {/* Interactive KPI Overview Cards - Styled matching Promotions tab */}
      <div className="promotions-kpi-grid">
        <div
          className={`promotions-kpi-card ${statusFilter === 'PENDING_APPROVAL' ? 'active' : ''}`}
          onClick={() => setStatusFilter(statusFilter === 'PENDING_APPROVAL' ? 'ALL' : 'PENDING_APPROVAL')}
        >
          <div>
            <div className="kpi-label">{t('admin:voucherProposalDemo.kpiPending')}</div>
            <div className="kpi-value" style={{ color: '#D97706' }}>{pendingCount}</div>
          </div>
          <div className="kpi-icon" style={{ background: '#FEF3C7', color: '#D97706' }}>
            <ClockCircleOutlined />
          </div>
        </div>

        <div
          className={`promotions-kpi-card ${statusFilter === 'APPROVED' ? 'active' : ''}`}
          onClick={() => setStatusFilter(statusFilter === 'APPROVED' ? 'ALL' : 'APPROVED')}
        >
          <div>
            <div className="kpi-label">{t('admin:voucherProposalDemo.kpiApprovedMonth')}</div>
            <div className="kpi-value" style={{ color: '#1E7A46' }}>{approvedCount}</div>
          </div>
          <div className="kpi-icon" style={{ background: '#ECFDF5', color: '#10B981' }}>
            <CheckCircleOutlined />
          </div>
        </div>

        <div
          className={`promotions-kpi-card ${statusFilter === 'REJECTED' ? 'active' : ''}`}
          onClick={() => setStatusFilter(statusFilter === 'REJECTED' ? 'ALL' : 'REJECTED')}
        >
          <div>
            <div className="kpi-label">{t('admin:voucherProposalDemo.kpiRejected')}</div>
            <div className="kpi-value" style={{ color: '#991B1B' }}>{rejectedCount}</div>
          </div>
          <div className="kpi-icon" style={{ background: '#FEF2F2', color: '#EF4444' }}>
            <CloseCircleOutlined />
          </div>
        </div>

        <div
          className={`promotions-kpi-card ${statusFilter === 'ALL' ? 'active' : ''}`}
          onClick={() => setStatusFilter('ALL')}
        >
          <div>
            <div className="kpi-label">{t('admin:voucherProposalDemo.kpiTotal')}</div>
            <div className="kpi-value">{proposals.length}</div>
          </div>
          <div className="kpi-icon" style={{ background: '#F7E5DF', color: '#8F1216' }}>
            <ShopOutlined />
          </div>
        </div>
      </div>

      {/* Proposals Table with Search Bar & Filter Nodes */}
      <div className="voucher-admin-table">
        <AdminTable<VoucherProposal>
          columns={columns}
          dataSource={filteredProposals}
          rowKey="id"
          scroll={{ x: 1100 }}
          searchPlaceholder={t('admin:voucherProposalDemo.searchPlaceholder')}
          searchValue={searchTerm}
          onSearchChange={setSearchTerm}
          filterNodes={
            <Space wrap size={10}>
              {/* Store Filter */}
              <Select
                value={storeFilter}
                onChange={setStoreFilter}
                style={{ width: 220 }}
                options={[
                  { value: 'ALL', label: t('admin:voucherProposalDemo.allStores') },
                  ...storeOptions,
                ]}
              />

              {/* Status Filter */}
              <Select
                value={statusFilter}
                onChange={setStatusFilter}
                style={{ width: 180 }}
                options={[
                  { value: 'ALL', label: t('admin:voucherProposalDemo.allStatuses') },
                  { value: 'PENDING_APPROVAL', label: t('admin:voucherProposalDemo.pendingOption') },
                  { value: 'APPROVED', label: t('admin:voucherProposalDemo.approvedOption') },
                  { value: 'REJECTED', label: t('admin:voucherProposalDemo.rejectedOption') },
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

      {/* Notice Modal */}
      <Modal
        open={modalInfo.open}
        title={
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <InfoCircleOutlined style={{ color: '#0284C7' }} />
            <span>{modalInfo.title}</span>
          </div>
        }
        onOk={() => setModalInfo({ open: false, title: '', content: '' })}
        onCancel={() => setModalInfo({ open: false, title: '', content: '' })}
        okText={t('admin:voucherProposalDemo.understood')}
        cancelButtonProps={{ style: { display: 'none' } }}
        styles={{
          body: { background: '#FFFAF3', padding: '16px 0' },
        }}
      >
        <Paragraph style={{ fontSize: 14, color: '#16100D', lineHeight: 1.6 }}>
          {modalInfo.content}
        </Paragraph>
      </Modal>
    </div>
  );
};
