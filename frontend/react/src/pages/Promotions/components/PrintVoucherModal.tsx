import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  ConfigProvider,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Select,
  Skeleton,
  Tag,
  Typography,
} from 'antd';
import { PrinterOutlined, ShopOutlined, GlobalOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { AdminModal } from '../../../components/ui/AdminModal';
import { message } from '../../../components/feedback';
import { useAuth } from '../../../context/AuthContext';
import { getErrorMessage, promotionsApi, type Store } from '../../../lib/api';
import type { AdminVoucher, VoucherPrintQuota } from '../../../types/promotion';

const { Text } = Typography;

export interface PrintVoucherModalProps {
  open: boolean;
  voucher: AdminVoucher | null;
  stores: Store[];
  onCancel: () => void;
  onSuccess: () => Promise<void> | void;
}

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

export const PrintVoucherModal: React.FC<PrintVoucherModalProps> = ({
  open,
  voucher,
  stores,
  onCancel,
  onSuccess,
}) => {
  const { t } = useTranslation();
  const { user, isAdmin } = useAuth();
  const [form] = Form.useForm<{ storeId?: number | 'HQ'; quantity: number; note?: string }>();
  const [selectedStore, setSelectedStore] = useState<number | null>(null);
  const [quota, setQuota] = useState<VoucherPrintQuota | null>(null);
  const [quotaLoading, setQuotaLoading] = useState(false);
  const [quotaError, setQuotaError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const eligibleStores = useMemo(() => {
    if (!voucher) return [];
    if (voucher.campaignKind === 'LEGACY') {
      if (voucher.scope === 'STORE' && voucher.storeId != null) {
        return stores.filter((s) => Number(s.id) === Number(voucher.storeId));
      }
      return stores;
    }
    if (voucher.applicability === 'SELECTED' && voucher.storeIds?.length) {
      const set = new Set(voucher.storeIds.map(Number));
      return stores.filter((s) => set.has(Number(s.id)));
    }
    if (voucher.scope === 'STORE' && voucher.storeId != null) {
      return stores.filter((s) => Number(s.id) === Number(voucher.storeId));
    }
    return stores;
  }, [voucher, stores]);

  const isSingleStore = eligibleStores.length === 1 && (
    voucher?.scope === 'STORE' || voucher?.applicability === 'SELECTED'
  );

  useEffect(() => {
    if (!open || !voucher) return;
    form.resetFields();
    if (!isAdmin && user.storeId) {
      const managerStore = Number(user.storeId);
      setSelectedStore(managerStore);
      form.setFieldsValue({ storeId: managerStore, quantity: 10 });
    } else if (isSingleStore && eligibleStores[0]) {
      setSelectedStore(eligibleStores[0].id);
      form.setFieldsValue({ storeId: eligibleStores[0].id, quantity: 10 });
    } else {
      setSelectedStore(null);
      form.setFieldsValue({ storeId: 'HQ', quantity: 10 });
    }
  }, [open, voucher, isAdmin, user.storeId, isSingleStore, eligibleStores, form]);

  useEffect(() => {
    if (!open || !voucher) return;
    const controller = new AbortController();
    setQuotaLoading(true);
    setQuotaError('');
    promotionsApi
      .getPrintQuota(voucher.id, selectedStore, controller.signal)
      .then((data) => {
        setQuota(data);
        const currentQty = form.getFieldValue('quantity');
        const cap = Math.min(200, data.maxPrintableNow);
        if (cap > 0 && (!currentQty || currentQty > cap)) {
          form.setFieldValue('quantity', Math.min(10, cap));
        }
      })
      .catch((err) => {
        if ((err as { name?: string })?.name === 'CanceledError') return;
        setQuota(null);
        setQuotaError(getErrorMessage(err));
      })
      .finally(() => {
        setQuotaLoading(false);
      });
    return () => controller.abort();
  }, [open, voucher, selectedStore, form]);

  const maxBatchLimit = quota ? Math.min(200, Math.max(0, quota.maxPrintableNow)) : 0;

  const handleFinish = async (values: { storeId?: number | 'HQ'; quantity: number; note?: string }) => {
    if (!voucher || submitting || maxBatchLimit <= 0) return;
    setSubmitting(true);
    try {
      const targetStoreId = !isAdmin
        ? Number(user.storeId)
        : values.storeId === 'HQ' || values.storeId == null
          ? null
          : Number(values.storeId);

      const batch = await promotionsApi.createPrintBatch(voucher.id, {
        storeId: targetStoreId,
        quantity: values.quantity,
        note: values.note?.trim() || null,
      });

      const pdf = await promotionsApi.downloadPrintBatchPdf(batch.id);
      triggerPdfDownload(pdf.blob, pdf.filename);
      message.success(t('admin:voucherPrinting.batchCreated', { code: batch.batchCode }));
      await onSuccess();
      onCancel();
    } catch (err) {
      message.error(getErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <ConfigProvider
      theme={{
        token: { colorBgContainer: '#FFFFFF', colorBgElevated: '#FFFFFF' },
        components: { Select: { optionSelectedBg: '#F5F5F5', optionActiveBg: '#FAFAFA' } },
      }}
    >
      <AdminModal
        open={open}
        title={
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
            <PrinterOutlined style={{ color: '#8F1216' }} />
            {t('admin:voucherPrinting.printModalTitle')}
          </span>
        }
        subtitle={t('admin:voucherPrinting.printModalSubtitle')}
        width={620}
        okText={t('admin:voucherPrinting.createAndDownload')}
        okButtonProps={{
          disabled: quotaLoading || !!quotaError || maxBatchLimit <= 0,
        }}
        confirmLoading={submitting}
        onOk={() => form.submit()}
        onCancel={() => {
          if (!submitting) onCancel();
        }}
      >
        {voucher && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label={t('admin:voucherPrinting.campaign')} span={2}>
                <Text strong>{voucher.title || voucher.code || `#${voucher.id}`}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('admin:voucherUi.limit')}>
                <Text strong>{voucher.totalLimit?.toLocaleString() ?? '∞'}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('admin:voucherPrinting.maxPrintPercent')}>
                <Tag color="gold" style={{ fontWeight: 700 }}>
                  {Math.round((voucher.maxPrintRatio ?? 0.5) * 100)}%
                </Tag>
              </Descriptions.Item>
            </Descriptions>

            {quotaLoading ? (
              <Skeleton active paragraph={{ rows: 2 }} />
            ) : quotaError ? (
              <Alert type="error" showIcon title={quotaError} />
            ) : quota ? (
              <div className="print-quota-grid">
                <div className="print-quota-card">
                  <span className="label">{t('admin:voucherPrinting.quotaMaxPrint')}</span>
                  <strong>{quota.maxPrintableLimit.toLocaleString()}</strong>
                </div>
                <div className="print-quota-card">
                  <span className="label">{t('admin:voucherPrinting.quotaPrinted')}</span>
                  <strong>{quota.printedCount.toLocaleString()}</strong>
                </div>
                <div className="print-quota-card">
                  <span className="label">{t('admin:voucherPrinting.quotaPoolRemaining')}</span>
                  <strong>{quota.poolRemaining.toLocaleString()}</strong>
                </div>
                {quota.storePrintLimit != null && (
                  <div className="print-quota-card">
                    <span className="label">{t('admin:voucherPrinting.quotaBranchCap')}</span>
                    <strong>
                      {quota.storePrintedCount} / {quota.storePrintLimit}
                    </strong>
                  </div>
                )}
                <div className="print-quota-card highlight">
                  <span className="label">{t('admin:voucherPrinting.quotaCanPrintNow')}</span>
                  <strong>{quota.maxPrintableNow.toLocaleString()}</strong>
                </div>
              </div>
            ) : null}

            {quota && maxBatchLimit <= 0 && (
              <Alert type="warning" showIcon title={t('admin:voucherPrinting.noQuotaLeft')} />
            )}

            <Form form={form} layout="vertical" onFinish={handleFinish}>
              <Form.Item name="storeId" label={t('admin:voucherPrinting.printStore')}>
                {!isAdmin ? (
                  <Tag icon={<ShopOutlined />} color="blue" style={{ padding: '4px 10px', fontWeight: 600 }}>
                    {stores.find((s) => Number(s.id) === Number(user.storeId))?.name || `#${user.storeId}`}
                  </Tag>
                ) : (
                  <Select
                    disabled={isSingleStore || submitting}
                    onChange={(val) => setSelectedStore(val === 'HQ' || val == null ? null : Number(val))}
                    options={[
                      ...(!isSingleStore
                        ? [
                            {
                              value: 'HQ',
                              label: (
                                <span>
                                  <GlobalOutlined /> {t('admin:voucherPrinting.hqAllStores')}
                                </span>
                              ),
                            },
                          ]
                        : []),
                      ...eligibleStores.map((s) => ({
                        value: s.id,
                        label: (
                          <span>
                            <ShopOutlined /> {s.name} (#{s.id})
                          </span>
                        ),
                      })),
                    ]}
                  />
                )}
              </Form.Item>

              <Form.Item
                name="quantity"
                label={t('admin:voucherPrinting.quantity')}
                rules={[
                  { required: true, message: t('admin:voucherPrinting.quantityRequired') },
                  {
                    validator: async (_, value) => {
                      if (value == null) return;
                      if (!Number.isInteger(value) || value < 1) {
                        return Promise.reject(new Error(t('admin:voucherPrinting.quantityRequired')));
                      }
                      if (maxBatchLimit > 0 && value > maxBatchLimit) {
                        return Promise.reject(
                          new Error(`${t('admin:voucherPrinting.quotaCanPrintNow')}: ${maxBatchLimit}`)
                        );
                      }
                    },
                  },
                ]}
              >
                <InputNumber
                  style={{ width: '100%' }}
                  min={1}
                  max={Math.max(1, maxBatchLimit)}
                  disabled={submitting || maxBatchLimit <= 0}
                  placeholder={t('admin:voucherPrinting.quantityPlaceholder')}
                />
              </Form.Item>

              <Form.Item name="note" label={t('admin:voucherPrinting.note')}>
                <Input.TextArea
                  rows={2}
                  maxLength={500}
                  showCount
                  disabled={submitting}
                  placeholder={t('admin:voucherPrinting.notePlaceholder')}
                />
              </Form.Item>
            </Form>
          </div>
        )}
      </AdminModal>
    </ConfigProvider>
  );
};
