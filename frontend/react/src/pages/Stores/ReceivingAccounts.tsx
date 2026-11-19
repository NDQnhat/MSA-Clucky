import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Form, Input, Modal, Select, Space, Switch, Table, Tag, Typography } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { notify } from '../../components/feedback';
import { api, getErrorMessage, type Store } from '../../lib/api';
import './receiving-accounts.css';

interface ReceivingAccount {
  id: number;
  bankName: string;
  bankCode: string | null;
  accountNo: string;
  accountName: string;
  active: boolean;
  version: number;
}

interface Assignment {
  storeId: number;
  bankAccountId: number | null;
  version: number;
}

interface Overview {
  accounts: ReceivingAccount[];
  assignments: Assignment[];
  sepayKeyConfigured: boolean;
  reconciliationAvailable: boolean;
}

interface Bank {
  name: string;
  shortName: string;
  code: string;
  bin: string;
}

type AccountFields = Pick<ReceivingAccount, 'bankName' | 'bankCode' | 'accountNo' | 'accountName' | 'active'>;
type AssignmentFields = { storeId: number; bankAccountId?: number | null };

function normalizeSearch(text: string) {
  return text.normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[đĐ]/g, 'd').toLowerCase();
}

export function ReceivingAccounts({ stores }: { stores: Store[] }) {
  const { t } = useTranslation('payments');
  const [data, setData] = useState<Overview | null>(null);
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState<ReceivingAccount | 'new' | null>(null);
  const [accountError, setAccountError] = useState('');
  const [assignmentStore, setAssignmentStore] = useState<Store | null>(null);
  const [assignmentError, setAssignmentError] = useState('');
  const [search, setSearch] = useState('');
  const [assignmentStatus, setAssignmentStatus] = useState<string>();
  const [accountFilter, setAccountFilter] = useState<number>();
  const [banks, setBanks] = useState<Bank[]>([]);
  const [bankLoading, setBankLoading] = useState(false);
  const [bankError, setBankError] = useState('');
  const [bankRevision, setBankRevision] = useState(0);
  const [accountForm] = Form.useForm<AccountFields>();
  const [assignmentForm] = Form.useForm<AssignmentFields>();
  const alive = useRef(true);
  const pendingOperation = useRef<{ signature: string; key: string } | null>(null);

  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError('');
    void api.get<Overview>('/api/payments/bank-config', { signal: controller.signal })
      .then(response => {
        if (!controller.signal.aborted) setData(response.data);
      })
      .catch(failure => {
        if (!controller.signal.aborted) {
          setError(getErrorMessage(failure));
          setData(null);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [revision]);

  useEffect(() => {
    if (editing || assignmentStore || busy) return;
    const timer = window.setTimeout(() => setRevision(value => value + 1), 15000);
    return () => window.clearTimeout(timer);
  }, [revision, editing, assignmentStore, busy]);

  useEffect(() => {
    if (!editing) return;
    const controller = new AbortController();
    setBankLoading(true);
    setBankError('');
    void api.get<Bank[]>('/api/payments/bank-config/banks', { signal: controller.signal })
      .then(response => {
        if (!controller.signal.aborted) setBanks(response.data);
      })
      .catch(failure => {
        if (!controller.signal.aborted) setBankError(getErrorMessage(failure));
      })
      .finally(() => {
        if (!controller.signal.aborted) setBankLoading(false);
      });
    return () => controller.abort();
  }, [editing, bankRevision]);

  function operationHeaders(path: string, payload: unknown) {
    const signature = JSON.stringify([path, payload]);
    if (pendingOperation.current?.signature !== signature) {
      pendingOperation.current = { signature, key: crypto.randomUUID() };
    }
    return { 'Idempotency-Key': pendingOperation.current.key };
  }

  function beginEditing(account: ReceivingAccount | 'new') {
    setAccountError('');
    setBankError('');
    setEditing(account);
    accountForm.resetFields();
    accountForm.setFieldsValue(account === 'new'
      ? { active: true }
      : { ...account });
  }

  async function saveAccount(values: AccountFields) {
    if (!editing || busy) return;
    const path = `/api/payments/bank-config${editing === 'new' ? '' : `/${editing.id}`}`;
    const payload = {
      bankName: values.bankName?.trim(),
      bankCode: values.bankCode?.trim().toUpperCase(),
      accountNo: values.accountNo.trim(),
      accountName: values.accountName.trim(),
      active: values.active ?? true,
      version: editing === 'new' ? 0 : editing.version,
    };
    setBusy(true);
    setAccountError('');
    try {
      if (editing === 'new') await api.post(path, payload, { headers: operationHeaders(path, payload) });
      else await api.put(path, payload, { headers: operationHeaders(path, payload) });
      if (!alive.current) return;
      setEditing(null);
      pendingOperation.current = null;
      setRevision(value => value + 1);
      notify.success(t('saved'));
    } catch (failure) {
      if (alive.current) setAccountError(getErrorMessage(failure));
    } finally {
      if (alive.current) setBusy(false);
    }
  }

  function assignedStoreNames(accountId: number) {
    return data?.assignments
      .filter(assignment => assignment.bankAccountId === accountId)
      .map(assignment => stores.find(store => store.id === assignment.storeId)?.name ?? `#${assignment.storeId}`) ?? [];
  }

  async function removeAccount(account: ReceivingAccount) {
    if (busy) return;
    const path = `/api/payments/bank-config/${account.id}`;
    setBusy(true);
    try {
      await api.delete(path, { headers: operationHeaders(path, { id: account.id }) });
      if (!alive.current) return;
      pendingOperation.current = null;
      setRevision(value => value + 1);
      notify.success(t('deleted'));
    } catch (failure) {
      if (!alive.current) return;
      if ((failure as { response?: { status?: number } }).response?.status === 409) {
        try {
          const latest = await api.get<Overview>('/api/payments/bank-config');
          if (!alive.current) return;
          setData(latest.data);
          const currentAssignments = latest.data.assignments
            .filter(assignment => assignment.bankAccountId === account.id)
            .map(assignment => stores.find(store => store.id === assignment.storeId)?.name ?? `#${assignment.storeId}`);
          notify.error(currentAssignments.length
            ? t('deleteAssigned', { stores: currentAssignments.join(', ') })
            : t('deleteConflict'));
        } catch {
          notify.error(t('deleteConflict'));
        }
      } else {
        notify.error(getErrorMessage(failure));
      }
    } finally {
      if (alive.current) setBusy(false);
    }
  }

  function requestDelete(account: ReceivingAccount) {
    const assigned = assignedStoreNames(account.id);
    if (assigned.length) {
      notify.error(t('deleteAssigned', { stores: assigned.join(', ') }));
      return;
    }
    Modal.confirm({
      title: t('deleteConfirmTitle'),
      content: t('deleteConfirmDescription', { bank: account.bankName, account: account.accountNo }),
      okText: t('delete'),
      cancelText: t('cancel'),
      okButtonProps: { danger: true },
      onOk: () => removeAccount(account),
    });
  }

  function beginAssigning(store: Store) {
    if (!data) return;
    setAssignmentError('');
    setAssignmentStore(store);
    assignmentForm.setFieldsValue({
      storeId: store.id,
      bankAccountId: data.assignments.find(item => item.storeId === store.id)?.bankAccountId ?? null,
    });
  }

  async function assign(values: AssignmentFields) {
    if (!data || busy) return;
    const existing = data.assignments.find(item => item.storeId === values.storeId);
    const payload = { bankAccountId: values.bankAccountId ?? null, version: existing?.version ?? 0 };
    const path = `/api/payments/bank-config/stores/${values.storeId}`;
    setBusy(true);
    setAssignmentError('');
    try {
      const result = await api.put<Assignment>(path, payload, { headers: operationHeaders(path, payload) });
      if (!alive.current) return;
      setData(current => current && {
        ...current,
        assignments: [...current.assignments.filter(item => item.storeId !== result.data.storeId), result.data],
      });
      setAssignmentStore(null);
      pendingOperation.current = null;
      setRevision(value => value + 1);
      notify.success(t('saved'));
    } catch (failure) {
      if (alive.current) setAssignmentError(getErrorMessage(failure));
    } finally {
      if (alive.current) setBusy(false);
    }
  }

  const bankOptions = banks.map(bank => ({
    value: bank.code,
    label: `${bank.shortName} · ${bank.name} · ${bank.code} / ${bank.bin}`,
  }));
  const selectedBankCode = editing && editing !== 'new' ? editing.bankCode : null;
  if (selectedBankCode && !bankOptions.some(option => option.value === selectedBankCode)) {
    bankOptions.unshift({ value: selectedBankCode, label: `${editing !== 'new' && editing ? editing.bankName : ''} · ${selectedBankCode}` });
  }

  const assignments = new Map(data?.assignments.map(item => [item.storeId, item.bankAccountId]) ?? []);
  const accounts = new Map(data?.accounts.map(item => [item.id, item]) ?? []);
  const filteredStores = stores.filter(store => {
    const currentAccountId = assignments.get(store.id);
    return normalizeSearch(`${store.id} ${store.name} ${store.address ?? ''}`).includes(normalizeSearch(search))
      && (!assignmentStatus || (assignmentStatus === 'assigned' ? !!currentAccountId : !currentAccountId))
      && (!accountFilter || currentAccountId === accountFilter);
  });

  return <div className="stores-receiving-accounts">
    <Typography.Paragraph type="secondary">{t('assignmentHelp')}</Typography.Paragraph>
    {error && <Alert type="error" showIcon message={error} action={
      <Button onClick={() => setRevision(value => value + 1)} loading={loading}>{t('reload')}</Button>
    } />}
    <Card title={t('accounts')} extra={<Button type="primary" onClick={() => beginEditing('new')} disabled={loading || busy || !data}>
      {t('add')}
    </Button>}>
      <div className="stores-receiving-table" role="region" aria-label={t('accounts')} tabIndex={0}>
        <Table<ReceivingAccount> rowKey="id" dataSource={data?.accounts ?? []} loading={loading} pagination={{ pageSize: 10 }}
          locale={{ emptyText: t('noAccounts') }} scroll={{ x: 920 }} columns={[
            { title: t('bankName'), dataIndex: 'bankName' },
            { title: t('bankCode'), render: (_, account) => account.bankCode || <Tag color="warning">{t('missingCode')}</Tag> },
            { title: t('accountNo'), dataIndex: 'accountNo' },
            { title: t('accountName'), dataIndex: 'accountName' },
            { title: t('status'), render: (_, account) => <Tag color={account.active ? 'success' : 'default'}>{t(account.active ? 'active' : 'inactive')}</Tag> },
            { title: t('stores'), render: (_, account) => data?.assignments
              .filter(assignment => assignment.bankAccountId === account.id)
              .map(assignment => stores.find(store => store.id === assignment.storeId)?.name ?? `#${assignment.storeId}`)
              .join(', ') || t('none') },
            { title: t('actions'), render: (_, account) => <Space size="small">
              <Button disabled={busy || loading} onClick={() => beginEditing(account)}>{t('edit')}</Button>
              <Button danger icon={<DeleteOutlined />} disabled={busy || loading} onClick={() => requestDelete(account)}>
                {t('delete')}
              </Button>
            </Space> },
          ]} />
      </div>
    </Card>
    <Card title={t('assignment')}>
      <div className="stores-receiving-filters">
        <Input.Search aria-label={t('assignments.search')} placeholder={t('assignments.search')} allowClear
          value={search} onChange={event => setSearch(event.target.value)} />
        <Select aria-label={t('assignments.filterStatus')} placeholder={t('assignments.filterStatus')}
          allowClear value={assignmentStatus} onChange={setAssignmentStatus} options={[
            { value: 'assigned', label: t('assignments.assigned') },
            { value: 'missing', label: t('assignments.missing') },
          ]} />
        <Select aria-label={t('receivingAccount')} placeholder={t('assignments.allAccounts')}
          showSearch allowClear optionFilterProp="label" value={accountFilter} onChange={setAccountFilter}
          options={data?.accounts.map(account => ({ value: account.id, label: `${account.bankName} · ${account.accountNo}` }))} />
      </div>
      <div className="stores-receiving-table" role="region" aria-label={t('assignment')} tabIndex={0}>
        <Table<Store> rowKey="id" dataSource={data ? filteredStores : []} loading={loading}
          pagination={{ pageSize: 8 }} scroll={{ x: 680 }} columns={[
            { title: t('store'), render: (_, store) => <><strong>{store.name}</strong><small className="stores-receiving-muted">{store.address}</small></> },
            { title: t('receivingAccount'), render: (_, store) => {
              const account = accounts.get(assignments.get(store.id) ?? -1);
              return account
                ? <>{account.bankName} · {account.accountNo}<small className="stores-receiving-muted">{account.accountName}</small>{!account.active && <Tag color="warning">{t('inactive')}</Tag>}</>
                : <Tag color="warning">{t('assignments.missing')}</Tag>;
            } },
            { title: t('actions'), width: 150, render: (_, store) => <Button disabled={busy || loading} onClick={() => beginAssigning(store)}>
              {assignments.get(store.id) ? t('assignments.change') : t('assignments.assign')}
            </Button> },
          ]} />
      </div>
    </Card>
    <Modal title={t('assignments.modalTitle')} open={assignmentStore !== null}
      onCancel={() => { if (!busy) setAssignmentStore(null); }}
      onOk={() => assignmentForm.submit()} confirmLoading={busy}
      okText={t('saveAssignment')} cancelText={t('cancel')}
      cancelButtonProps={{ disabled: busy }} closable={!busy} maskClosable={false} keyboard={!busy}>
      <Typography.Title level={5}>{assignmentStore?.name}</Typography.Title>
      <Typography.Paragraph type="secondary">{assignmentStore?.address}</Typography.Paragraph>
      {assignmentError && <Alert type="error" showIcon message={assignmentError} style={{ marginBottom: 16 }} />}
      <Form form={assignmentForm} layout="vertical" disabled={busy} onFinish={assign}>
        <Form.Item name="storeId" hidden><Input /></Form.Item>
        <Form.Item name="bankAccountId" label={t('receivingAccount')} extra={t('assignments.onlyFuture')}>
          <Select showSearch allowClear optionFilterProp="label" placeholder={t('assignments.chooseAccount')}
            options={data?.accounts.map(account => ({
              value: account.id,
              disabled: !account.active || !account.bankCode,
              label: `${account.bankName} · ${account.accountNo} · ${account.accountName}${account.active ? '' : ` (${t('inactive')})`}`,
            }))} />
        </Form.Item>
      </Form>
    </Modal>
    <Modal title={t(editing === 'new' ? 'add' : 'edit')} open={editing !== null}
      onCancel={() => { if (!busy) setEditing(null); }}
      onOk={() => accountForm.submit()} confirmLoading={busy}
      okText={t('save')} cancelText={t('cancel')} cancelButtonProps={{ disabled: busy }}
      closable={!busy} maskClosable={false} keyboard={!busy}>
      {accountError && <Alert type="error" showIcon message={accountError} style={{ marginBottom: 16 }} />}
      {bankError && <Alert type="warning" showIcon message={t('bankListUnavailable')} description={bankError}
        action={<Button size="small" onClick={() => setBankRevision(value => value + 1)}>{t('reloadBanks')}</Button>}
        style={{ marginBottom: 16 }} />}
      <Form form={accountForm} layout="vertical" onFinish={saveAccount} disabled={busy}>
        <Form.Item name="bankCode" label={t('bankCode')} extra={t('bankCodeHelp')}
          rules={[{ required: true, pattern: /^[A-Za-z0-9]{2,20}$/, message: t('invalidCode') }]}>
          <Select showSearch placeholder={t('chooseBank')} options={bankOptions} loading={bankLoading}
            disabled={busy || bankLoading || Boolean(bankError)}
            filterOption={(value, option) => normalizeSearch(String(option?.label ?? '')).includes(normalizeSearch(value))}
            onChange={value => accountForm.setFieldValue('bankName', banks.find(bank => bank.code === value)?.shortName ?? '')} />
        </Form.Item>
        <Form.Item name="bankName" hidden rules={[{ required: true, whitespace: true, message: t('required') }]}><Input /></Form.Item>
        <Form.Item name="accountNo" label={t('accountNo')}
          rules={[{ required: true, pattern: /^[0-9]{1,80}$/, message: t('invalidAccount') }]}>
          <Input inputMode="numeric" maxLength={80} autoComplete="off" />
        </Form.Item>
        <Form.Item name="accountName" label={t('accountName')}
          rules={[{ required: true, whitespace: true, max: 160, message: t('required') }]}>
          <Input maxLength={160} autoComplete="off" />
        </Form.Item>
        <Form.Item name="active" label={t('active')} valuePropName="checked" extra={t('inactiveHelp')}>
          <Switch />
        </Form.Item>
      </Form>
    </Modal>
  </div>;
}
