import React, { useEffect } from 'react';
import {
  ConfigProvider,
  Form,
  Input,
  InputNumber,
  Radio,
  Select,
  DatePicker,
  Row,
  Col,
  Space,
  Alert,
  Switch,
  Divider,
} from 'antd';
import { CheckCircleOutlined, StopOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import { AdminModal } from '../../../components/ui/AdminModal';
import { BilingualField } from '../../../components/ui/BilingualField';
import { useAuth } from '../../../context/AuthContext';
import {
  isLocalizedViValid,
  pickLocalized,
  toLocalizedInput,
  toLocalizedPayload,
  type LocalizedText,
} from '../../../lib/bilingual';
import type { AdminVoucher, VoucherPayload, VoucherStatus, VoucherType } from '../../../types/promotion';
import type { Store } from '../../../lib/api';

export interface VoucherModalProps {
  open: boolean;
  editingVoucher: AdminVoucher | null;
  stores: Store[];
  loading?: boolean;
  onCancel: () => void;
  onSubmit: (values: VoucherPayload) => Promise<void> | void;
}

export const VoucherModal: React.FC<VoucherModalProps> = ({
  open,
  editingVoucher,
  stores,
  loading = false,
  onCancel,
  onSubmit,
}) => {
  const { user, isAdmin } = useAuth();
  const { t, i18n } = useTranslation();
  const lang = i18n.resolvedLanguage || i18n.language || 'vi';
  const [form] = Form.useForm();
  const selectedApplicability = Form.useWatch('applicability', form);
  const selectedType = Form.useWatch('type', form);
  const isPrintable = Form.useWatch('printable', form);
  const selectedStoreIds = Form.useWatch('storeIds', form);

  useEffect(() => {
    if (open) {
      if (editingVoucher) {
        form.setFieldsValue({
          titleI18n: toLocalizedInput(editingVoucher.titleI18n, editingVoucher.title),
          descriptionI18n: toLocalizedInput(editingVoucher.descriptionI18n, editingVoucher.description),
          type: editingVoucher.type,
          value: editingVoucher.value,
          minOrderAmount: editingVoucher.minOrderAmount || 0,
          maxDiscount: editingVoucher.maxDiscount ?? undefined,
          totalLimit: editingVoucher.totalLimit ?? undefined,
          dateRange: [
            editingVoucher.startAt ? dayjs(editingVoucher.startAt) : null,
            editingVoucher.endAt ? dayjs(editingVoucher.endAt) : null,
          ],
          applicability: editingVoucher.campaignKind === 'LEGACY'
            ? (editingVoucher.scope === 'STORE' ? 'SELECTED' : 'ALL')
            : (editingVoucher.applicability || (editingVoucher.scope === 'STORE' ? 'SELECTED' : 'ALL')),
          storeIds: editingVoucher.storeIds?.length ? editingVoucher.storeIds.map(Number)
            : editingVoucher.storeId ? [Number(editingVoucher.storeId)] : [],
          status: editingVoucher.status || 'ACTIVE',
          printable: Boolean(editingVoucher.printable),
          maxPrintPercent: editingVoucher.maxPrintRatio != null
            ? Math.round(Number(editingVoucher.maxPrintRatio) * 100)
            : 50,
          maxPrintPerStore: editingVoucher.maxPrintPerStore ?? undefined,
        });
      } else {
        // Defaults for new voucher
        form.resetFields();
        form.setFieldsValue({
          titleI18n: { vi: '', en: '' },
          descriptionI18n: { vi: '', en: '' },
          type: 'PERCENT',
          value: 10,
          minOrderAmount: 0,
          applicability: isAdmin ? 'ALL' : 'SELECTED',
          storeIds: !isAdmin && user.storeId ? [Number(user.storeId)] : [],
          status: 'ACTIVE',
          dateRange: [dayjs(), dayjs().add(30, 'day')],
          printable: false,
          maxPrintPercent: 50,
          maxPrintPerStore: undefined,
        });
      }
    }
  }, [open, editingVoucher, form, isAdmin, user.storeId]);

  const handleFinish = async (values: {
    titleI18n?: LocalizedText;
    descriptionI18n?: LocalizedText;
    type: VoucherType;
    value: number;
    minOrderAmount?: number;
    maxDiscount?: number;
    totalLimit?: number;
    dateRange: [Dayjs, Dayjs];
    applicability: 'ALL' | 'SELECTED';
    storeIds?: number[];
    status?: string;
    printable?: boolean;
    maxPrintPercent?: number;
    maxPrintPerStore?: number | null;
  }) => {
    const [startDay, endDay] = values.dateRange;
    const selectedStoreIds = values.applicability === 'SELECTED' ? values.storeIds || [] : [];
    const isLegacyEdit = editingVoucher?.campaignKind === 'LEGACY';
    // Existing campaign scope/store ID must match the backend's stored terms for
    // optimistic concurrency and reservations. PERSONAL campaigns created by Admin
    // retain scope ALL even when applicability selects specific stores.
    const scope = editingVoucher && !isLegacyEdit
      ? editingVoucher.scope
      : values.applicability === 'SELECTED' && (!isAdmin || isLegacyEdit) ? 'STORE' : 'ALL';
    const storeId = editingVoucher && !isLegacyEdit
      ? (editingVoucher.storeId == null ? null : Number(editingVoucher.storeId))
      : scope === 'STORE' && selectedStoreIds.length === 1 ? selectedStoreIds[0] : null;
    const titlePayload = toLocalizedPayload(values.titleI18n);
    const descriptionPayload = toLocalizedPayload(values.descriptionI18n);
    const payload: VoucherPayload = {
      code: editingVoucher?.campaignKind === 'LEGACY' ? editingVoucher.code : null,
      title: titlePayload ?? { vi: '', en: '' },
      titleI18n: titlePayload ?? null,
      description: descriptionPayload ?? null,
      descriptionI18n: descriptionPayload ?? null,
      type: values.type,
      value: values.value,
      minOrderAmount: values.minOrderAmount ?? 0,
      maxDiscount: values.maxDiscount ?? null,
      totalLimit: values.totalLimit ?? null,
      startAt: startDay.toISOString(),
      endAt: endDay.toISOString(),
      applicability: values.applicability,
      storeIds: selectedStoreIds,
      scope,
      storeId,
      status: (values.status as VoucherStatus) || 'ACTIVE',
      printable: isAdmin ? Boolean(values.printable) : false,
      maxPrintRatio: isAdmin && values.maxPrintPercent
        ? Number((values.maxPrintPercent / 100).toFixed(4))
        : 0.5,
      maxPrintPerStore: isAdmin && values.printable ? (values.maxPrintPerStore ?? null) : null,
    };

    await onSubmit(payload);
  };

  return (
    <ConfigProvider theme={{
      token: { colorBgContainer: '#FFFFFF', colorBgElevated: '#FFFFFF' },
      components: { Select: { optionSelectedBg: '#F5F5F5', optionActiveBg: '#FAFAFA' } },
    }}>
    <AdminModal
      open={open}
      title={editingVoucher ? t('admin:voucherUi.editCampaign') : isAdmin ? t('admin:voucherUi.createCampaign') : t('admin:voucherUi.propose')}
      subtitle={
        editingVoucher
          ? t('admin:voucherUi.editCampaignHint', { id: editingVoucher.id })
          : t('admin:voucherUi.personalCampaignHint')
      }
      width={720}
      okText={editingVoucher ? t('admin:voucherUi.saveCampaign') : isAdmin ? t('admin:voucherUi.createCampaign') : t('admin:voucherUi.sendProposal')}
      confirmLoading={loading}
      onOk={() => form.submit()}
      onCancel={onCancel}
      styles={{
        container: { background: '#FFFFFF' },
        header: { background: '#FFFFFF', marginBottom: 16, paddingBottom: 12, borderBottom: '1px solid #EEEEEE' },
        body: { background: '#FFFFFF', padding: '16px 0' },
        footer: { background: '#FFFFFF', marginTop: 20, paddingTop: 16, borderTop: '1px solid #EEEEEE' },
      }}
    >
      {!isAdmin && <Alert type="info" showIcon title={t('admin:voucherUi.managerHelp')} style={{ marginBottom: 16 }} />}
      <Alert type="info" showIcon title={t('admin:voucherUi.noSharedCode')} style={{ marginBottom: 16 }} />
      <Form
        form={form}
        layout="vertical"
        onFinish={handleFinish}
        onFinishFailed={({ errorFields }) => {
          if (errorFields.length > 0) {
            form.scrollToField(errorFields[0].name, { block: 'center' });
          }
        }}
        initialValues={{
          type: 'PERCENT',
          applicability: 'ALL',
          minOrderAmount: 0,
          status: 'ACTIVE',
        }}
      >
        <Row gutter={16}>
          <Col xs={24}>
            <Form.Item
              name="titleI18n"
              label={t('admin:voucherUi.campaignTitle')}
              rules={[
                {
                  validator: async (_, val: LocalizedText | undefined) => {
                    if (!isLocalizedViValid(val)) {
                      return Promise.reject(new Error(t('admin:voucherUi.campaignTitleRequired')));
                    }
                    if ((val?.vi?.trim().length ?? 0) > 255 || (val?.en?.trim().length ?? 0) > 255) {
                      return Promise.reject(new Error(t('admin:voucherUi.campaignTitleLimit')));
                    }
                  },
                },
              ]}
            >
              <BilingualField
                requiredVi
                maxLength={255}
                viLabel={t('admin:voucherUi.viLanguage')}
                enLabel={t('admin:voucherUi.enLanguage')}
                viPlaceholder={t('admin:voucherUi.campaignTitlePlaceholderVi')}
                enPlaceholder={t('admin:voucherUi.campaignTitlePlaceholderEn')}
                showEnFallbackHint
                fallbackHintText={t('admin:voucherUi.fallbackEnHint')}
              />
            </Form.Item>
          </Col>
        </Row>

        <Form.Item name="descriptionI18n" label={t('admin:voucherUi.descriptionLabel')}>
          <BilingualField
            multiline
            rows={2}
            viLabel={t('admin:voucherUi.viLanguage')}
            enLabel={t('admin:voucherUi.enLanguage')}
            viPlaceholder={t('admin:voucherUi.descriptionPlaceholderVi')}
            enPlaceholder={t('admin:voucherUi.descriptionPlaceholderEn')}
          />
        </Form.Item>

        <Row gutter={16}>
          <Col xs={24} sm={12}>
            <Form.Item
              name="type"
              label={t('admin:voucherUi.discountType')}
              rules={[{ required: true, message: t('admin:voucherUi.discountTypeRequired') }]}
            >
              <Radio.Group buttonStyle="solid" style={{ width: '100%' }}>
                <Radio.Button value="PERCENT" style={{ width: '50%', textAlign: 'center' }}>
                  {t('admin:voucherUi.percentUnitLabel')}
                </Radio.Button>
                <Radio.Button value="AMOUNT" style={{ width: '50%', textAlign: 'center' }}>
                  {t('admin:voucherUi.amountUnitLabel')}
                </Radio.Button>
              </Radio.Group>
            </Form.Item>
          </Col>

          <Col xs={24} sm={12}>
            <Form.Item
              name="value"
              label={selectedType === 'PERCENT' ? t('admin:voucherUi.discountPercentLabel') : t('admin:voucherUi.discountAmountLabel')}
              rules={[
                { required: true, message: t('admin:voucherUi.discountValueRequired') },
                {
                  validator: async (_, val) => {
                    if (val === undefined || val === null) return;
                    if (val <= 0) {
                      return Promise.reject(new Error(t('admin:voucherUi.discountValuePositive')));
                    }
                    if (selectedType === 'PERCENT' && val > 100) {
                      return Promise.reject(new Error(t('admin:voucherUi.discountPercentMax')));
                    }
                  },
                },
              ]}
            >
              <InputNumber<number>
                style={{ width: '100%' }}
                min={1}
                max={selectedType === 'PERCENT' ? 100 : 10000000}
                placeholder={selectedType === 'PERCENT' ? t('admin:voucherUi.examplePercentPlaceholder') : t('admin:voucherUi.exampleAmountPlaceholder')}
                formatter={(val) =>
                  selectedType === 'PERCENT'
                    ? `${val}%`
                    : `${val}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
                }
                parser={(val) => Number(val?.replace(/[%,\s]/g, '') || 0)}
              />
            </Form.Item>
          </Col>
        </Row>

        <Row gutter={16}>
          <Col xs={24} sm={12}>
            <Form.Item
              name="minOrderAmount"
              label={t('admin:voucherUi.minOrderLabel')}
              rules={[
                {
                  validator: async (_, val) => {
                    if (val !== undefined && val !== null && val < 0) {
                      return Promise.reject(new Error(t('admin:voucherUi.minOrderNegative')));
                    }
                  },
                },
              ]}
              extra={t('admin:voucherUi.minOrderHint')}
            >
              <InputNumber<number>
                style={{ width: '100%' }}
                min={0}
                placeholder={t('admin:voucherUi.minOrderPlaceholder')}
                formatter={(val) => `${val}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
                parser={(val) => Number(val?.replace(/\D/g, '') || 0)}
              />
            </Form.Item>
          </Col>

          <Col xs={24} sm={12}>
            <Form.Item
              name="maxDiscount"
              label={t('admin:voucherUi.maxDiscountLabel')}
              extra={selectedType === 'PERCENT' ? t('admin:voucherUi.maxDiscountPercentHint') : t('admin:voucherUi.maxDiscountUnlimitedHint')}
              rules={[
                {
                  validator: async (_, val) => {
                    if (val !== undefined && val !== null && val < 0) {
                      return Promise.reject(new Error(t('admin:voucherUi.maxDiscountNegative')));
                    }
                  },
                },
              ]}
            >
              <InputNumber<number>
                style={{ width: '100%' }}
                min={0}
                placeholder={t('admin:voucherUi.maxDiscountPlaceholder')}
                formatter={(val) => (val ? `${val}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',') : '')}
                parser={(val) => Number(val?.replace(/\D/g, '') || 0)}
              />
            </Form.Item>
          </Col>
        </Row>

        <Row gutter={16}>
          <Col xs={24} sm={12}>
            <Form.Item
              name="totalLimit"
              label={t('admin:voucherUi.globalUsageLimit')}
              extra={t('admin:voucherUi.globalUsageLimitHint')}
              dependencies={['printable']}
              rules={[
                {
                  validator: async (_, val) => {
                    const printableEnabled = Boolean(form.getFieldValue('printable'));
                    if (isAdmin && printableEnabled && (val === undefined || val === null || val < 1)) {
                      return Promise.reject(new Error(t('admin:voucherPrinting.printableRequiresLimit')));
                    }
                    if (val !== undefined && val !== null && val < 1) {
                      return Promise.reject(new Error(t('admin:voucherUi.minUsageLimitOne')));
                    }
                  },
                },
              ]}
            >
              <InputNumber<number>
                style={{ width: '100%' }}
                min={1}
                placeholder="VD: 500"
              />
            </Form.Item>
          </Col>

          <Col xs={24} sm={12}>
            <Form.Item
              name="status"
              hidden={!isAdmin}
              label={t('admin:voucherUi.activationStatus')}
              rules={[{ required: true, message: t('admin:voucherUi.statusRequired') }]}
            >
              <Select
                options={[
                  { value: 'ACTIVE', label: <Space size={6}><CheckCircleOutlined style={{ color: '#1E7A46' }} />{t('admin:voucherUi.states.ACTIVE')}</Space> },
                  { value: 'DISABLED', label: <Space size={6}><StopOutlined style={{ color: '#6B6259' }} />{t('admin:voucherUi.states.DISABLED')}</Space> },
                ]}
              />
            </Form.Item>
          </Col>
        </Row>

        <Form.Item
          name="dateRange"
          label={t('admin:voucherUi.validityRange')}
          rules={[
            { required: true, message: t('admin:voucherUi.validityRangeRequired') },
            {
              validator: async (_, dates) => {
                if (!dates || !dates[0] || !dates[1]) return;
                const [start, end] = dates;
                if (!end.isAfter(start)) {
                  return Promise.reject(new Error(t('admin:voucherUi.validityEndAfterStart')));
                }
              },
            },
          ]}
        >
          <DatePicker.RangePicker
            showTime={{ format: 'HH:mm' }}
            format="YYYY-MM-DD HH:mm"
            style={{ width: '100%' }}
            placeholder={[t('admin:voucherUi.startAtPlaceholder'), t('admin:voucherUi.endAtPlaceholder')]}
          />
        </Form.Item>

        {/* Scope and Store selector */}
        <Row gutter={16}>
          <Col xs={24} sm={12}>
            <Form.Item
              name="applicability"
              label={t('admin:voucherUi.applicability')}
              rules={[{ required: true, message: t('admin:voucherUi.applicabilityRequired') }]}
              extra={!isAdmin ? t('admin:voucherUi.managerHelp') : undefined}
            >
              <Radio.Group
                buttonStyle="solid"
                style={{ width: '100%' }}
                disabled={!isAdmin}
              >
                <Radio.Button value="ALL" style={{ width: '50%', textAlign: 'center' }}>
                  {t('admin:voucherUi.allStores')}
                </Radio.Button>
                <Radio.Button value="SELECTED" style={{ width: '50%', textAlign: 'center' }}>
                  {t('admin:voucherUi.selectedStores')}
                </Radio.Button>
              </Radio.Group>
            </Form.Item>
          </Col>

          <Col xs={24} sm={12}>
            {selectedApplicability === 'SELECTED' ? (
              <Form.Item
                name="storeIds"
                label={t('admin:voucherUi.selectedStores')}
                rules={[
                  { required: true, type: 'array', min: 1, message: t('admin:voucherUi.chooseStoresRequired') },
                  ...(editingVoucher?.campaignKind === 'LEGACY'
                    ? [{ type: 'array' as const, max: 1, message: t('admin:voucherUi.legacySingleStore') }]
                    : []),
                ]}
                extra={t('admin:voucherUi.selectedStoresHint')}
              >
                <Select
                  mode="multiple"
                  maxCount={editingVoucher?.campaignKind === 'LEGACY' ? 1 : undefined}
                  maxTagCount="responsive"
                  placeholder={t('admin:voucherUi.chooseStores')}
                  disabled={!isAdmin}
                  options={stores.filter(s => isAdmin || Number(user.storeId) === Number(s.id)).map((s) => ({
                    value: s.id,
                    label: `${pickLocalized(s.nameI18n, s.name, lang)} (#${s.id})`,
                  }))}
                />
              </Form.Item>
            ) : (
              <div style={{ paddingTop: 30, color: '#6B6259', fontSize: 13 }}>
                {t('admin:voucherUi.allStoresHint')}
              </div>
            )}
          </Col>
        </Row>

        {isAdmin && (
          <>
            <Divider style={{ margin: '12px 0 16px' }} />
            <Row gutter={16} align="middle">
              <Col xs={24} sm={12}>
                <Form.Item
                  name="printable"
                  label={t('admin:voucherPrinting.printableSwitch')}
                  valuePropName="checked"
                  extra={t('admin:voucherPrinting.printableHint')}
                >
                  <Switch />
                </Form.Item>
              </Col>

              {isPrintable && (
                <Col xs={24} sm={12}>
                  <Form.Item
                    name="maxPrintPercent"
                    label={t('admin:voucherPrinting.maxPrintPercent')}
                    extra={t('admin:voucherPrinting.maxPrintPercentHint')}
                    rules={[{ required: true, message: t('admin:voucherPrinting.quantityRequired') }]}
                  >
                    <InputNumber<number>
                      style={{ width: '100%' }}
                      min={1}
                      max={100}
                      formatter={(val) => (val != null ? `${val}%` : '')}
                      parser={(val) => Number(val?.replace(/[%\s]/g, '') || 50)}
                    />
                  </Form.Item>
                </Col>
              )}
            </Row>

            {isPrintable && (selectedApplicability === 'ALL' || (selectedStoreIds?.length ?? 0) > 1) && (
              <Row gutter={16}>
                <Col xs={24} sm={12}>
                  <Form.Item
                    name="maxPrintPerStore"
                    label={t('admin:voucherPrinting.maxPrintPerStore')}
                    extra={t('admin:voucherPrinting.maxPrintPerStoreHint')}
                  >
                    <InputNumber<number>
                      style={{ width: '100%' }}
                      min={1}
                      placeholder={t('admin:voucherPrinting.maxPrintPerStorePlaceholder')}
                    />
                  </Form.Item>
                </Col>
              </Row>
            )}
          </>
        )}
      </Form>
    </AdminModal>
    </ConfigProvider>
  );
};

