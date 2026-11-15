import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { Alert, Badge, Button, Empty, Pagination, Popover, Space, Spin, Typography } from 'antd';
import { BellOutlined, CheckOutlined } from '@ant-design/icons';
import { useAuth } from '../../context/AuthContext';
import { useLocaleTools } from '../../i18n/hooks';
import { api, getErrorMessage, ordersApi } from '../../lib/api';

type BranchNotice = {
  id: number;
  title: string;
  content: string;
  type: 'ORDER' | 'INVENTORY' | 'PAYMENT' | 'REFUND' | 'PROMOTION' | 'SYSTEM';
  refId: number | null;
  isRead: boolean;
  createdAt: string;
};

type BranchInbox = {
  content: BranchNotice[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  unreadCount: number;
};

/** Notifications for the authenticated Store Manager or shared Staff account. */
export function AdminNotificationCenter() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const { date } = useLocaleTools();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [page, setPage] = useState(0);
  const [reload, setReload] = useState(0);
  const [inbox, setInbox] = useState<BranchInbox | null>(null);
  const [unread, setUnread] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const sequence = useRef(0);

  useEffect(() => {
    const controller = new AbortController();
    const request = ++sequence.current;
    setError('');
    setLoading(open);
    if (open) setInbox(null);
    api.get<BranchInbox>('/api/notifications/my', {
      params: { page: open ? page : 0, size: open ? 10 : 1 }, signal: controller.signal,
    }).then(({ data }) => {
      if (controller.signal.aborted || request !== sequence.current) return;
      setUnread(data.unreadCount);
      if (open) setInbox(data);
    }).catch(failure => {
      if (!controller.signal.aborted && request === sequence.current) setError(getErrorMessage(failure));
    }).finally(() => {
      if (!controller.signal.aborted && request === sequence.current) setLoading(false);
    });
    return () => controller.abort();
  }, [user.id, open, page, reload]);

  useEffect(() => {
    if (saving) return;
    const controller = new AbortController();
    let pending = false;
    const refresh = async () => {
      if (document.hidden || pending) return;
      pending = true;
      try {
        const { data } = await api.get<BranchInbox>('/api/notifications/my', {
          params: { page: open ? page : 0, size: open ? 10 : 1 }, signal: controller.signal,
        });
        if (!controller.signal.aborted) {
          setUnread(data.unreadCount);
          if (open) setInbox(data);
          setError('');
        }
      } catch (failure) {
        if (!controller.signal.aborted) setError(getErrorMessage(failure));
      } finally { pending = false; }
    };
    const interval = window.setInterval(() => void refresh(), 15000);
    window.addEventListener('focus', refresh);
    return () => { controller.abort(); window.clearInterval(interval); window.removeEventListener('focus', refresh); };
  }, [user.id, page, open, saving]);

  async function markRead(id?: number) {
    setSaving(true);
    setError('');
    try {
      if (id === undefined) await api.put('/api/notifications/read-all');
      else await api.put(`/api/notifications/${id}/read`);
      setReload(value => value + 1);
    } catch (failure) { setError(getErrorMessage(failure)); }
    finally { setSaving(false); }
  }

  async function viewNotice(notice: BranchNotice) {
    setSaving(true);
    setError('');
    try {
      if (notice.type === 'ORDER' && notice.refId) {
        const order = await ordersApi.getById(notice.refId);
        await api.put(`/api/notifications/${notice.id}/read`);
        setOpen(false);
        navigate(`/admin/orders?orderId=${encodeURIComponent(String(order.id))}`);
      } else if (notice.type === 'INVENTORY') {
        await api.put(`/api/notifications/${notice.id}/read`);
        setOpen(false);
        navigate('/admin/inventory');
      }
      setReload(value => value + 1);
    } catch (failure) { setError(getErrorMessage(failure)); }
    finally { setSaving(false); }
  }

  const content = <div style={{ width: 'min(380px, calc(100vw - 48px))' }}>
    <Space style={{ marginBottom: 12 }}>
      <Typography.Text strong>{t('admin:ui.adminNotifications')}</Typography.Text>
      <Button size="small" disabled={!unread || saving || loading} loading={saving}
        onClick={() => void markRead()}>{t('admin:ui.markAllAsRead')}</Button>
    </Space>
    {error ? <Alert type="error" showIcon title={t('admin:ui.unableToLoadOrUpdateNotifications')} description={error} />
      : loading ? <Spin aria-label={t('admin:ui.loadingNotifications')} />
      : inbox?.content.length ? <ul style={{ padding: 0, margin: 0, listStyle: 'none', maxHeight: 420, overflowY: 'auto' }}>
        {inbox.content.map(notice => <li key={notice.id} style={{ padding: '12px 0', borderBottom: '1px solid #eee' }}>
          <Typography.Text strong={!notice.isRead}>{notice.title}</Typography.Text>
          <Typography.Paragraph style={{ marginBottom: 4 }}>{notice.content}</Typography.Paragraph>
          <Space wrap>
            {(notice.type === 'ORDER' || notice.type === 'INVENTORY') && notice.refId &&
              <Button size="small" type="link" disabled={saving} onClick={() => void viewNotice(notice)}>
                {t('business:orderspage.viewDetails')}
              </Button>}
            <Typography.Text type="secondary">{date(notice.createdAt, { dateStyle: 'short', timeStyle: 'short' })}</Typography.Text>
            {notice.isRead ? <Typography.Text type="secondary">{t('admin:ui.read')}</Typography.Text>
              : <Button size="small" icon={<CheckOutlined />} disabled={saving}
                  aria-label={t('admin:ui.markAsRead', { value1: notice.title })}
                  onClick={() => void markRead(notice.id)}>{t('business:promotionUi.markRead')}</Button>}
          </Space>
        </li>)}
      </ul> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('admin:ui.noNotificationsYet')} />}
    {!error && <Pagination size="small" current={page + 1} pageSize={10}
      total={inbox?.totalElements ?? 0} showSizeChanger={false} disabled={loading || saving}
      onChange={next => setPage(next - 1)} style={{ marginTop: 12 }} />}
  </div>;

  return <Popover trigger="click" placement="bottomRight" open={open} onOpenChange={setOpen} content={content}>
    <Badge count={unread ?? 0} dot={Boolean(error)}>
      <Button type="text" icon={<BellOutlined />} aria-expanded={open}
        aria-label={error ? t('admin:ui.unableToLoadNotifications')
          : t('admin:ui.adminNotifications272', { value1: unread === null ? '' : t('admin:ui.unread', { value1: unread }) })} />
    </Badge>
  </Popover>;
}
