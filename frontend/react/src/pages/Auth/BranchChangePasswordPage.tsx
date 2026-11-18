import { useRef, useState } from 'react';
import { Alert, Button, Card, Form, Input, Steps, Typography } from 'antd';
import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../context/AuthContext';
import { api, getErrorMessage } from '../../lib/api';
import { expireSession } from '../../lib/session';

export function BranchChangePasswordPage() {
  const { t } = useTranslation('accounts');
  const { user } = useAuth();
  const navigate = useNavigate();
  const [requestForm] = Form.useForm();
  const [finishForm] = Form.useForm();
  const [challengeId, setChallengeId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const lock = useRef(false);

  async function execute(action: () => Promise<void>) {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setError('');
    try { await action(); } catch (failure) { setError(getErrorMessage(failure)); }
    finally { lock.current = false; setBusy(false); }
  }

  async function request(values: { currentPassword: string }) {
    await execute(async () => {
      const response = await api.post<{ challengeId: string }>('/api/auth/branch/change-password/request', values);
      setChallengeId(response.data.challengeId);
      requestForm.resetFields();
      finishForm.resetFields();
    });
  }

  async function finish(values: { otp: string; newPassword: string; confirmPassword: string }) {
    if (!challengeId) return;
    await execute(async () => {
      await api.post('/api/auth/branch/change-password/confirm', {
        challengeId,
        otp: values.otp,
        newPassword: values.newPassword,
      });
      // The protected route can redirect as soon as its session disappears.
      // Keep this one-time, non-sensitive success notice in the current tab.
      try { sessionStorage.setItem('clucky_admin_password_updated', '1'); } catch {}
      // Preserve the success message when the protected route unmounts after
      // session invalidation. Identity has already revoked the server session.
      navigate('/login', { replace: true, state: { branchPasswordUpdated: true } });
      expireSession();
    });
  }

  return <main style={{ display: 'flex', justifyContent: 'center', padding: '20px 0 48px' }}>
    <Card style={{ width: 'min(640px, 100%)', borderRadius: 18, background: '#FFFAF3' }}>
      <Typography.Title level={2} style={{ fontFamily: 'Fredoka, sans-serif' }}>{t('selfPasswordTitle')}</Typography.Title>
      <Typography.Paragraph type="secondary">{t('selfPasswordHelp')}</Typography.Paragraph>
      <Typography.Text strong>{user.fullName}</Typography.Text>
      <Steps style={{ margin: '24px 0' }} size="small" current={challengeId ? 1 : 0}
        items={[{ title: t('selfPasswordVerifyStep') }, { title: t('selfPasswordNewStep') }]} />
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 18 }} />}
      {challengeId ? <>
        <Alert type="info" showIcon message={t('selfPasswordSent')} style={{ marginBottom: 18 }} />
        <Form form={finishForm} layout="vertical" disabled={busy} onFinish={finish}>
          <Form.Item name="otp" label={t('otp')} rules={[{ required: true, pattern: /^\d{8}$/ }]}>
            <Input inputMode="numeric" maxLength={8} size="large" autoComplete="one-time-code" />
          </Form.Item>
          <Form.Item name="newPassword" label={t('password')} rules={[{ required: true, pattern: /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,72}$/ }]}>
            <Input.Password size="large" maxLength={72} autoComplete="new-password" />
          </Form.Item>
          <Form.Item name="confirmPassword" label={t('confirmPassword')} dependencies={['newPassword']}
            rules={[{ required: true }, ({ getFieldValue }) => ({ validator: (_, value: string) =>
              !value || value === getFieldValue('newPassword') ? Promise.resolve() : Promise.reject(new Error(t('passwordMismatch'))) })]}>
            <Input.Password size="large" maxLength={72} autoComplete="new-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={busy}>{t('selfPasswordVerify')}</Button>
        </Form>
        <Button type="link" disabled={busy} onClick={() => { setChallengeId(null); finishForm.resetFields(); setError(''); }}>
          {t('selfPasswordReturn')}
        </Button>
      </> : <Form form={requestForm} layout="vertical" disabled={busy} onFinish={request} style={{ marginTop: 24 }}>
        <Form.Item name="currentPassword" label={t('selfPasswordOld')} rules={[{ required: true }]}>
          <Input.Password size="large" maxLength={72} autoComplete="current-password" />
        </Form.Item>
        <Button type="primary" htmlType="submit" block loading={busy}>{t('selfPasswordRequest')}</Button>
      </Form>}
      <Link to="/admin" style={{ display: 'inline-block', marginTop: 22 }}>{t('backToDashboard')}</Link>
    </Card>
  </main>;
}
