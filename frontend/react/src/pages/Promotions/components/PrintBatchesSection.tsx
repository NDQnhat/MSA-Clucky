import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Button, Input, Popconfirm, Select, Space, Tag, Tooltip, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import {
  FilePdfOutlined,
  ReloadOutlined,
  RollbackOutlined,
  ShopOutlined,
  GlobalOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import { AdminTable } from '../../../components/ui/AdminTable';
import { message } from '../../../components/feedback';
import { useAuth } from '../../../context/AuthContext';
import { getErrorMessage, promotionsApi, type Store } from '../../../lib/api';
import type { PrintBatchStatus, VoucherPrintBatch } from '../../../types/promotion';

const { Text } = Typography;

export interface PrintBatchesSectionProps {
  stores: Store[];
  refreshToken?: number;
  onChanged?: () => void;
}

const batchStatusColors: Record<PrintBatchStatus, string> = {
  ACTIVE: 'success',
  PARTIALLY_REVOKED: 'warning',
  REVOKED: 'default',
};

function triggerPdfDownload(blob: Blob, filename: string) {
  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = objectUrl;
  anchor.download = filename;
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
}

export const PrintBatchesSection: React.FC<PrintBatchesSectionProps> = ({
  stores,
  refreshToken = 0,
  onChanged,
}) => {
  const { t } = useTranslation();
  const { isAdmin } = useAuth();
  const [batches, setBatches] = useState<VoucherPrintBatch[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [statusFilter, setStatusFilter] = useState<string>('ALL');
  const [storeFilter, setStoreFilter] = useState<number | 'ALL'>('ALL');
  const [searchTerm, setSearchTerm] = useState('');
  const [busyBatchId, setBusyBatchId] = useState<number | null>(null);
  const [revokeReason, setRevokeReason] = useState('');

  const fetchBatches = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    try {
      const data = await promotionsApi.listPrintBatches({
        storeId: isAdmin && storeFilter !== 'ALL' ? storeFilter : undefined,
        status: statusFilter !== 'ALL' ? statusFilter : undefined,
      });
      setBatches(data);
    } catch (err) {
      setLoadError(getErrorMessage(err));
      setBatches([]);
    } finally {
      setLoading(false);
    }
  }, [isAdmin, storeFilter, statusFilter]);

  useEffect(() => {
    void fetchBatches();
  }, [fetchBatches, refreshToken]);

  const handleDownloadPdf = async (batch: VoucherPrintBatch) => {
    if (busyBatchId != null) return;
    setBusyBatchId(batch.id);
    try {
      const result = await promotionsApi.downloadPrintBatchPdf(batch.id);
      triggerPdfDownload(result.blob, result.filename);
      message.success(t('admin:voucherPrinting.pdfDownloaded', { code: batch.batchCode }));
    } catch (err) {
      message.error(getErrorMessage(err));
    } finally {
      setBusyBatchId(null);
    }
  };

  const handleRevoke = async (batch: VoucherPrintBatch) => {
    if (busyBatchId != null) return;
    setBusyBatchId(batch.id);
    try {
      await promotionsApi.revokePrintBatch(batch.id, revokeReason.trim() || undefined);
      setRevokeReason('');
      message.success(t('admin:voucherPrinting.revokedSuccess', { code: batch.batchCode }));
      await fetchBatches();
      onChanged?.();
    } catch (err) {
      message.error(getErrorMessage(err));
    } finally {
      setBusyBatchId(null);
    }
  };

  const filteredBatches = batches.filter((b) => {
    const q = searchTerm.trim().toLowerCase();
    if (!q) return true;
    return [b.batchCode, b.campaignTitle, b.storeName, b.note]
      .some((val) => val?.toLowerCase().includes(q));
  });

  const columns: TableColumnsType<VoucherPrintBatch> = [
    {
      title: t('admin:voucherPrinting.batchCode'),
      dataIndex: 'batchCode',
      key: 'batchCode',
      width: 175,
      render: (_, record) => (
        <div>
          <Text strong style={{ fontFamily: 'monospace', color: '#8F1216' }}>
            {record.batchCode}
          </Text>
          <div style={{ fontSize: 11, color: '#6B6259' }}>
            {record.quantity} {t('admin:voucherPrinting.issuedCountUnit', { defaultValue: 'phiếu' })}
          </div>
        </div>
      ),
    },
    {
      title: t('admin:voucherPrinting.campaign'),
      key: 'campaign',
      width: 240,
      render: (_, record) => (
        <div>
          <Text strong>{record.campaignTitle || `#${record.campaignId}`}</Text>
          {record.discountValue != null && (
            <div style={{ fontSize: 12, color: '#8F1216', fontWeight: 700 }}>
              {record.campaignType === 'PERCENT'
                ? `-${record.discountValue}%`
                : `-${record.discountValue.toLocaleString('vi-VN')} đ`}
            </div>
          )}
          {record.note && (
            <div style={{ fontSize: 11, color: '#6B6259' }}>{record.note}</div>
          )}
        </div>
      ),
    },
    {
      title: t('admin:voucherPrinting.branch'),
      key: 'store',
      width: 170,
      render: (_, record) =>
        record.storeId ? (
          <Tag icon={<ShopOutlined />} color="blue" style={{ borderRadius: 999 }}>
            {record.storeName || `#${record.storeId}`}
          </Tag>
        ) : (
          <Tag icon={<GlobalOutlined />} color="purple" style={{ borderRadius: 999 }}>
            {t('admin:voucherPrinting.hqAllStores')}
          </Tag>
        ),
    },
    {
      title: t('admin:voucherPrinting.breakdown'),
      key: 'breakdown',
      width: 240,
      render: (_, record) => (
        <Space wrap size={[4, 4]}>
          <Tag color="green">
            {t('admin:voucherPrinting.issued')}: {record.issuedCount}
          </Tag>
          <Tag color="gold">
            {t('admin:voucherPrinting.reserved')}: {record.reservedCount}
          </Tag>
          <Tag color="blue">
            {t('admin:voucherPrinting.redeemed')}: {record.redeemedCount}
          </Tag>
          {record.expiredCount > 0 && (
            <Tag>{t('admin:voucherPrinting.expired')}: {record.expiredCount}</Tag>
          )}
          {record.revokedCount > 0 && (
            <Tag color="red">
              {t('admin:voucherPrinting.revoked')}: {record.revokedCount}
            </Tag>
          )}
        </Space>
      ),
    },
    {
      title: t('accounts:status'),
      dataIndex: 'status',
      key: 'status',
      width: 145,
      render: (status: PrintBatchStatus, record) => (
        <div>
          <Tag color={batchStatusColors[status] ?? 'default'} style={{ fontWeight: 700 }}>
            {t(`admin:voucherPrinting.statuses.${status}`)}
          </Tag>
          {record.revokeReason && (
            <div style={{ fontSize: 11, color: '#6B6259', marginTop: 2 }}>{record.revokeReason}</div>
          )}
        </div>
      ),
    },
    {
      title: t('admin:voucherPrinting.createdAt'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 145,
      render: (value: string) => (
        <span style={{ fontSize: 12, color: '#6B6259' }}>
          {dayjs(value).format('DD/MM/YYYY HH:mm')}
        </span>
      ),
    },
    {
      title: t('accounts:action'),
      key: 'actions',
      width: 170,
      fixed: 'right',
      render: (_, record) => {
        const canDownload = record.status !== 'REVOKED' && record.issuedCount > 0;
        const canRevoke = record.status !== 'REVOKED' && record.issuedCount > 0;
        const isBusy = busyBatchId === record.id;
        return (
          <Space size={6}>
            <Tooltip title={t('admin:voucherPrinting.downloadPdf')}>
              <Button
                size="small"
                icon={<FilePdfOutlined />}
                disabled={!canDownload || (busyBatchId != null && !isBusy)}
                loading={isBusy}
                onClick={() => void handleDownloadPdf(record)}
              >
                PDF
              </Button>
            </Tooltip>
            {canRevoke && (
              <Popconfirm
                title={t('admin:voucherPrinting.revokeConfirmTitle', { code: record.batchCode })}
                description={
                  <div style={{ maxWidth: 280 }}>
                    <div style={{ marginBottom: 8 }}>{t('admin:voucherPrinting.revokeConfirmDesc')}</div>
                    <Input
                      size="small"
                      value={revokeReason}
                      maxLength={500}
                      onChange={(e) => setRevokeReason(e.target.value)}
                      placeholder={t('admin:voucherPrinting.revokeReasonPlaceholder')}
                    />
                  </div>
                }
                okText={t('admin:voucherPrinting.revoke')}
                cancelText={t('business:account.cancel')}
                okButtonProps={{ danger: true, loading: isBusy }}
                onConfirm={() => handleRevoke(record)}
                onCancel={() => setRevokeReason('')}
                onOpenChange={(visible) => {
                  if (visible) setRevokeReason('');
                }}
              >
                <Button
                  size="small"
                  danger
                  icon={<RollbackOutlined />}
                  disabled={busyBatchId != null && !isBusy}
                >
                  {t('admin:voucherPrinting.revoke')}
                </Button>
              </Popconfirm>
            )}
          </Space>
        );
      },
    },
  ];

  if (loadError) {
    return (
      <Alert
        type="error"
        showIcon
        title={loadError}
        action={<Button onClick={() => void fetchBatches()}>{t('common:retry')}</Button>}
      />
    );
  }

  return (
    <div className="voucher-admin-table">
      <AdminTable<VoucherPrintBatch>
        columns={columns}
        dataSource={filteredBatches}
        rowKey="id"
        loading={loading}
        scroll={{ x: 1180 }}
        searchPlaceholder={t('admin:voucherUi.reviewSearch')}
        searchValue={searchTerm}
        onSearchChange={setSearchTerm}
        filterNodes={
          <Space wrap size={10}>
            <Select
              value={statusFilter}
              onChange={setStatusFilter}
              style={{ width: 190 }}
              options={[
                { value: 'ALL', label: t('business:paymentspage.allStatuses') },
                { value: 'ACTIVE', label: t('admin:voucherPrinting.statuses.ACTIVE') },
                { value: 'PARTIALLY_REVOKED', label: t('admin:voucherPrinting.statuses.PARTIALLY_REVOKED') },
                { value: 'REVOKED', label: t('admin:voucherPrinting.statuses.REVOKED') },
              ]}
            />
            {isAdmin && (
              <Select
                value={storeFilter}
                onChange={setStoreFilter}
                style={{ width: 220 }}
                options={[
                  { value: 'ALL', label: t('business:stores.allStores') },
                  ...stores.map((s) => ({
                    value: s.id,
                    label: (
                      <Space size={6}>
                        <ShopOutlined />
                        {s.name}
                      </Space>
                    ),
                  })),
                ]}
              />
            )}
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void fetchBatches()}>
              {t('admin:voucherUi.refresh')}
            </Button>
          </Space>
        }
      />
    </div>
  );
};
