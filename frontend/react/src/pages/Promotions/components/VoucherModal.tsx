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
} from 'antd';
import { CheckCircleOutlined, StopOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import { AdminModal } from '../../../components/ui/AdminModal';
import { useAuth } from '../../../context/AuthContext';
import type { AdminVoucher, VoucherPayload, VoucherType } from '../../../types/promotion';
import type { Store } from '../../../lib/api';

const { TextArea } = Input;

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
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const selectedApplicability = Form.useWatch('applicability', form);
  const selectedType = Form.useWatch('type', form);

  useEffect(() => {
    if (open) {
      if (editingVoucher) {
        form.setFieldsValue({
          title: editingVoucher.title || '',
          description: editingVoucher.description || '',
          type: editingVoucher.type,
          value: editingVoucher.value,
          minOrderAmount: editingVoucher.minOrderAmount || 0,
          maxDiscount: editingVoucher.maxDiscount ?? undefined,
          totalLimit: editingVoucher.totalLimit ?? undefined,
          dateRange: [
            editingVoucher.startAt ? dayjs(editingVoucher.startAt) : null,
            editingVoucher.endAt ? dayjs(editingVoucher.endAt) : null,
          ],
          applicability: editingVoucher.applicability ||
            (editingVoucher.scope === 'STORE' ? 'SELECTED' : 'ALL'),
          storeIds: editingVoucher.storeIds?.length ? editingVoucher.storeIds.map(Number)
            : editingVoucher.storeId ? [Number(editingVoucher.storeId)] : [],
          status: editingVoucher.status || 'ACTIVE',
        });
      } else {
        // Defaults for new voucher
        form.resetFields();
        form.setFieldsValue({
          type: 'PERCENT',
          value: 10,
          minOrderAmount: 0,
          applicability: isAdmin ? 'ALL' : 'SELECTED',
          storeIds: !isAdmin && user.storeId ? [Number(user.storeId)] : [],
          status: 'ACTIVE',
          dateRange: [dayjs(), dayjs().add(30, 'day')],
        });
      }
    }
  }, [open, editingVoucher, form, isAdmin, user.storeId]);

  const handleFinish = async (values: {
    title?: string;
    description?: string;
    type: VoucherType;
    value: number;
    minOrderAmount?: number;
    maxDiscount?: number;
    totalLimit?: number;
    dateRange: [Dayjs, Dayjs];
    applicability: 'ALL' | 'SELECTED';
    storeIds?: number[];
    status?: string;
  }) => {
    const [startDay, endDay] = values.dateRange;
    const payload: VoucherPayload = {
      code: editingVoucher?.campaignKind === 'LEGACY' ? editingVoucher.code : null,
      title: values.title?.trim(),
      description: values.description?.trim(),
      type: values.type,
      value: values.value,
      minOrderAmount: values.minOrderAmount ?? 0,
      maxDiscount: values.maxDiscount ?? null,
      totalLimit: values.totalLimit ?? null,
      startAt: startDay.toISOString(),
      endAt: endDay.toISOString(),
      applicability: values.applicability,
      storeIds: values.applicability === 'SELECTED' ? values.storeIds || [] : [],
      scope: values.applicability === 'SELECTED' ? 'STORE' : 'ALL',
      storeId: values.applicability === 'SELECTED' && values.storeIds?.length === 1
        ? values.storeIds[0] : null,
      status: (values.status as any) || 'ACTIVE',
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
              name="title"
              label={t('admin:voucherUi.campaignTitle')}
              rules={[{ required: true, message: t('admin:voucherUi.campaignTitleRequired') },
                { max: 255, message: t('admin:voucherUi.campaignTitleLimit') }]}
            >
              <Input placeholder={t('admin:voucherUi.campaignTitlePlaceholder')} />
            </Form.Item>
          </Col>
        </Row>

        <Form.Item name="description" label="Mô tả chi tiết / Thể lệ áp dụng">
          <TextArea
            rows={2}
            placeholder="VD: Áp dụng cho mọi đơn giao hàng tận nơi. Không áp dụng đồng thời chương trình khác."
          />
        </Form.Item>

        <Row gutter={16}>
          <Col xs={24} sm={12}>
            <Form.Item
              name="type"
              label="Loại giảm giá"
              rules={[{ required: true, message: 'Vui lòng chọn loại giảm giá' }]}
            >
              <Radio.Group buttonStyle="solid" style={{ width: '100%' }}>
                <Radio.Button value="PERCENT" style={{ width: '50%', textAlign: 'center' }}>
                  Phần trăm (%)
                </Radio.Button>
                <Radio.Button value="AMOUNT" style={{ width: '50%', textAlign: 'center' }}>
                  Tiền mặt (VNĐ)
                </Radio.Button>
              </Radio.Group>
            </Form.Item>
          </Col>

          <Col xs={24} sm={12}>
            <Form.Item
              name="value"
              label={selectedType === 'PERCENT' ? 'Mức giảm (%)' : 'Mức giảm (VNĐ)'}
              rules={[
                { required: true, message: 'Vui lòng nhập giá trị giảm' },
                {
                  validator: async (_, val) => {
                    if (val === undefined || val === null) return;
                    if (val <= 0) {
                      return Promise.reject(new Error('Giá trị giảm phải lớn hơn 0'));
                    }
                    if (selectedType === 'PERCENT' && val > 100) {
                      return Promise.reject(new Error('Mức giảm theo phần trăm không được vượt quá 100%'));
                    }
                  },
                },
              ]}
            >
              <InputNumber<number>
                style={{ width: '100%' }}
                min={1}
                max={selectedType === 'PERCENT' ? 100 : 10000000}
                placeholder={selectedType === 'PERCENT' ? 'VD: 20' : 'VD: 30000'}
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
              label="Đơn hàng tối thiểu (VNĐ)"
              rules={[
                {
                  validator: async (_, val) => {
                    if (val !== undefined && val !== null && val < 0) {
                      return Promise.reject(new Error('Giá trị đơn hàng tối thiểu không được âm'));
                    }
                  },
                },
              ]}
              extra="0 = Không yêu cầu mức đơn tối thiểu"
            >
              <InputNumber<number>
                style={{ width: '100%' }}
                min={0}
                placeholder="VD: 150000"
                formatter={(val) => `${val}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
                parser={(val) => Number(val?.replace(/\D/g, '') || 0)}
              />
            </Form.Item>
          </Col>

          <Col xs={24} sm={12}>
            <Form.Item
              name="maxDiscount"
              label="Giảm tối đa (VNĐ)"
              extra={selectedType === 'PERCENT' ? 'Giới hạn mức giảm tối đa cho mã %' : 'Bỏ trống nếu không giới hạn'}
              rules={[
                {
                  validator: async (_, val) => {
                    if (val !== undefined && val !== null && val < 0) {
                      return Promise.reject(new Error('Mức giảm tối đa không được âm'));
                    }
                  },
                },
              ]}
            >
              <InputNumber<number>
                style={{ width: '100%' }}
                min={0}
                placeholder="VD: 50000"
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
              rules={[
                {
                  validator: async (_, val) => {
                    if (val !== undefined && val !== null && val < 1) {
                      return Promise.reject(new Error('Giới hạn số lượt dùng tối thiểu là 1'));
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
              label="Trạng thái kích hoạt"
              rules={[{ required: true, message: 'Vui lòng chọn trạng thái' }]}
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
          label="Thời gian hiệu lực (Bắt đầu — Kết thúc)"
          rules={[
            { required: true, message: 'Vui lòng chọn thời gian hiệu lực' },
            {
              validator: async (_, dates) => {
                if (!dates || !dates[0] || !dates[1]) return;
                const [start, end] = dates;
                if (!end.isAfter(start)) {
                  return Promise.reject(new Error('Thời gian kết thúc phải sau thời gian bắt đầu'));
                }
              },
            },
          ]}
        >
          <DatePicker.RangePicker
            showTime={{ format: 'HH:mm' }}
            format="YYYY-MM-DD HH:mm"
            style={{ width: '100%' }}
            placeholder={['Bắt đầu lúc', 'Kết thúc lúc']}
          />
        </Form.Item>

        {/* Scope and Store selector */}
        <Row gutter={16}>
          <Col xs={24} sm={12}>
            <Form.Item
              name="applicability"
              label={t('admin:voucherUi.applicability')}
              rules={[{ required: true, message: 'Vui lòng chọn phạm vi áp dụng' }]}
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
                ]}
                extra={t('admin:voucherUi.selectedStoresHint')}
              >
                <Select
                  mode="multiple"
                  maxTagCount="responsive"
                  placeholder={t('admin:voucherUi.chooseStores')}
                  disabled={!isAdmin}
                  options={stores.filter(s => isAdmin || Number(user.storeId) === Number(s.id)).map((s) => ({
                    value: s.id,
                    label: `${s.name} (#${s.id})`,
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
      </Form>
    </AdminModal>
    </ConfigProvider>
  );
};

