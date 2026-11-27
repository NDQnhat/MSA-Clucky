import React, { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Alert,
  Card,
  Col,
  Drawer,
  Empty,
  Progress,
  Row,
  Skeleton,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { ShopOutlined } from '@ant-design/icons';
import { useLocaleTools } from '../../i18n/hooks';
import { getBrowserLocale, getErrorMessage } from '../../i18n/core';
import { pickLocalized, type LocalizedText } from '../../lib/bilingual';
import {
  reportsApi,
  type ItemStoreComparisonResponse,
  type StoreItemMetric,
} from '../../lib/api';

const { Text } = Typography;

export interface SelectedReportItem {
  itemType: string;
  itemRefId: number;
  itemName: string;
  itemNameI18n?: LocalizedText | null;
}

interface ItemStoreBreakdownDrawerProps {
  open: boolean;
  onClose: () => void;
  selectedItem: SelectedReportItem | null;
  fromIso: string;
  toIso: string;
  storeId?: number;
  resolveStoreName: (storeId: number) => string;
}

export const ItemStoreBreakdownDrawer: React.FC<ItemStoreBreakdownDrawerProps> = ({
  open,
  onClose,
  selectedItem,
  fromIso,
  toIso,
  storeId,
  resolveStoreName,
}) => {
  const { t } = useTranslation();
  const { money, number, locale } = useLocaleTools();
  const [data, setData] = useState<ItemStoreComparisonResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open || !selectedItem) {
      setData(null);
      setError('');
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setError('');
    reportsApi
      .getItemStoreComparison(
        selectedItem.itemType,
        selectedItem.itemRefId,
        { storeId, from: fromIso, to: toIso },
        controller.signal
      )
      .then((res) => {
        if (!controller.signal.aborted) setData(res);
      })
      .catch((err: unknown) => {
        if (!controller.signal.aborted) {
          setError(getErrorMessage(err, getBrowserLocale()));
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [fromIso, open, selectedItem, storeId, toIso]);

  const titleName = selectedItem
    ? pickLocalized(selectedItem.itemNameI18n, selectedItem.itemName, locale)
    : '';

  const columns: TableColumnsType<StoreItemMetric> = [
    {
      title: t('business:dashboard.storeName'),
      dataIndex: 'storeId',
      key: 'storeId',
      render: (id: number) => (
        <span style={{ fontWeight: 600, color: '#16100D' }}>
          <ShopOutlined style={{ color: '#C9A227', marginRight: 6 }} />
          {resolveStoreName(id)}
        </span>
      ),
    },
    {
      title: t('business:dashboard.quantitySold'),
      dataIndex: 'quantitySold',
      key: 'quantitySold',
      align: 'right',
      render: (val: number) => <Text strong>{number(val)}</Text>,
    },
    {
      title: t('business:reports.orderCount'),
      dataIndex: 'orderCount',
      key: 'orderCount',
      align: 'right',
      render: (val: number) => <span>{number(val)}</span>,
    },
    {
      title: t('business:dashboard.grossItemRevenue'),
      dataIndex: 'grossRevenue',
      key: 'grossRevenue',
      align: 'right',
      render: (val: number) => (
        <Text strong style={{ color: '#8F1216' }}>
          {money(Number(val || 0))}
        </Text>
      ),
    },
    {
      title: t('business:dashboard.netItemRevenue'),
      dataIndex: 'allocatedNetRevenue',
      key: 'allocatedNetRevenue',
      align: 'right',
      render: (val: number) => (
        <Text style={{ color: '#1E7A46', fontWeight: 600 }}>
          {money(Number(val || 0))}
        </Text>
      ),
    },
    {
      title: t('business:dashboard.revenueShare'),
      dataIndex: 'sharePct',
      key: 'sharePct',
      width: 120,
      render: (pct: number) => (
        <Progress
          percent={Math.min(100, Math.round(pct))}
          size="small"
          strokeColor="#8F1216"
          format={() => `${pct}%`}
        />
      ),
    },
  ];

  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={720}
      title={
        <div>
          <Tag
            color={selectedItem?.itemType === 'COMBO' ? 'gold' : 'red'}
            style={{ marginRight: 8 }}
          >
            {selectedItem?.itemType === 'COMBO'
              ? t('business:dashboard.combosOnly')
              : t('business:dashboard.singleProducts')}
          </Tag>
          <span>{titleName || t('business:dashboard.itemStoreBreakdownTitle')}</span>
        </div>
      }
    >
      {error && (
        <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />
      )}
      {loading ? (
        <Skeleton active paragraph={{ rows: 5 }} />
      ) : !data || data.stores.length === 0 ? (
        <Empty description={t('business:dashboard.noItemRevenueData')} />
      ) : (
        <>
          <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
            <Col span={8}>
              <Card size="small" style={{ background: '#FFFAF3', borderColor: '#E8E2D9' }}>
                <Statistic
                  title={t('business:dashboard.quantitySold')}
                  value={number(data.totalQuantitySold)}
                />
              </Card>
            </Col>
            <Col span={8}>
              <Card size="small" style={{ background: '#FFFAF3', borderColor: '#E8E2D9' }}>
                <Statistic
                  title={t('business:dashboard.grossItemRevenue')}
                  value={money(Number(data.totalGrossRevenue || 0))}
                  styles={{ content: { color: '#8F1216', fontSize: 18 } }}
                />
              </Card>
            </Col>
            <Col span={8}>
              <Card size="small" style={{ background: '#FFFAF3', borderColor: '#E8E2D9' }}>
                <Statistic
                  title={t('business:dashboard.netItemRevenue')}
                  value={money(Number(data.totalAllocatedNetRevenue || 0))}
                  styles={{ content: { color: '#1E7A46', fontSize: 18 } }}
                />
              </Card>
            </Col>
          </Row>

          <Table
            rowKey={(row) => String(row.storeId)}
            columns={columns}
            dataSource={data.stores}
            pagination={false}
            size="small"
            scroll={{ x: 560 }}
          />
        </>
      )}
    </Drawer>
  );
};
