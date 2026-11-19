import { useEffect, useRef, useState } from 'react';
import { Alert, App, Button, Input, Popconfirm, Space, Spin, Table, Tabs, Tag, Typography } from 'antd';
import { CodeOutlined, EyeOutlined, SaveOutlined, SendOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { getErrorMessage } from '../../lib/api';
import {
  mailTemplatesApi,
  type MailDraftInput,
  type MailPreview,
  type MailTemplateDetail,
  type MailTemplateRevision,
} from '../../lib/mail-templates';

type EditableContent = { subject: string; text: string; html: string };

const layoutVariables = ['{{subject}}', '{{title}}', '{{eyebrow}}', '{{details}}', '{{action}}', '{{notice}}', '{{footer}}'];

function eventVariables(keyName: string): string[] {
  const common = ['{{customerName}}', '{{orderCode}}'];
  if (keyName.startsWith('BRANCH_')) return ['{{login}}', '{{roleLabel}}', '{{channelInstruction}}'];
  if (keyName === 'ORDER_READY') return [...common, '{{readyInstruction}}'];
  if (['ORDER_CANCELLED', 'REFUND_REJECTED', 'REFUND_WAITING_BANK_DETAILS'].includes(keyName)) {
    return [...common, '{{reason}}'];
  }
  if (['PAYMENT_CONFIRMED', 'REFUND_PROCESSED'].includes(keyName)) return [...common, '{{amount}}'];
  if (keyName === 'PAYMENT_FAILED') return [...common, '{{paymentExplanation}}'];
  return /^(ORDER_|PAYMENT_|REFUND_)/.test(keyName) ? common : [];
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function isFullHtmlDocument(html: string): boolean {
  return /<!doctype\s+html\b|<html(?:\s|>)/i.test(html);
}

// Existing revisions store HTML fragments. Show them inside the backend's
// complete layout, preserving both the fragment and the remaining placeholders.
// This conversion is only persisted after the user explicitly saves a draft.
function editableFromRevision(
  revision: MailTemplateRevision | null,
  fallbackSubject: string,
  layout: string,
): { content: EditableContent; legacy: boolean } {
  const originalHtml = revision?.html;
  const legacy = !originalHtml || !isFullHtmlDocument(originalHtml);
  const fragment = originalHtml || escapeHtml(revision?.text ?? '').replace(/\r?\n/g, '<br>');
  return {
    content: {
      subject: revision?.subject ?? fallbackSubject,
      text: revision?.text ?? '',
      html: legacy ? layout.replace('{{content}}', () => fragment) : originalHtml,
    },
    legacy: revision !== null && legacy,
  };
}

export function TemplateEditor({
  keyName,
  onChanged,
  onUnsavedChange,
  onPendingChange,
}: {
  keyName: string;
  onChanged: () => void;
  onUnsavedChange: (dirty: boolean) => void;
  onPendingChange: (pending: boolean) => void;
}) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [detail, setDetail] = useState<MailTemplateDetail | null>(null);
  const [layout, setLayout] = useState('');
  const [subject, setSubject] = useState('');
  const [bodyText, setBodyText] = useState('');
  const [bodyHtml, setBodyHtml] = useState('');
  const [baseline, setBaseline] = useState<EditableContent>({ subject: '', text: '', html: '' });
  const [legacy, setLegacy] = useState(false);
  const [pending, setPending] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [preview, setPreview] = useState<MailPreview | null>(null);
  const [tab, setTab] = useState('editor');
  const [reload, setReload] = useState(0);
  const htmlInputRef = useRef<HTMLTextAreaElement>(null);
  const htmlSelectionRef = useRef({ start: 0, end: 0 });
  const tRef = useRef(t);
  const messageRef = useRef(message);

  const dirty = subject !== baseline.subject || bodyText !== baseline.text || bodyHtml !== baseline.html;
  const canPublish = Boolean(detail?.draft && !dirty && !legacy && !pending);

  useEffect(() => { onUnsavedChange(dirty); }, [dirty, onUnsavedChange]);
  useEffect(() => { onPendingChange(pending || loading); }, [pending, loading, onPendingChange]);
  useEffect(() => {
    tRef.current = t;
    messageRef.current = message;
  }, [t, message]);

  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setDetail(null);
    setError('');
    setPreview(null);
    Promise.all([
      mailTemplatesApi.get(keyName, controller.signal),
      mailTemplatesApi.layout(controller.signal),
    ]).then(([next, sharedLayout]) => {
      if (controller.signal.aborted) return;
      if (!isFullHtmlDocument(sharedLayout.html) || !sharedLayout.html.includes('{{content}}')) {
        throw new Error(tRef.current('admin:emailTemplates.invalidLayout'));
      }
      const { content, legacy: fromLegacy } = editableFromRevision(
        next.draft ?? next.published,
        next.template.subject,
        sharedLayout.html,
      );
      setDetail(next);
      setLayout(sharedLayout.html);
      setSubject(content.subject);
      setBodyText(content.text);
      setBodyHtml(content.html);
      setBaseline(content);
      setLegacy(fromLegacy);
    }).catch(failure => {
      if (controller.signal.aborted) return;
      const description = getErrorMessage(failure);
      setError(description);
      void messageRef.current.error(description);
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, [keyName, reload]);

  function applyRevision(revision: MailTemplateRevision | null, sharedLayout: string, fallbackSubject: string) {
    const { content, legacy: fromLegacy } = editableFromRevision(revision, fallbackSubject, sharedLayout);
    setSubject(content.subject);
    setBodyText(content.text);
    setBodyHtml(content.html);
    setBaseline(content);
    setLegacy(fromLegacy);
    htmlSelectionRef.current = { start: 0, end: 0 };
  }

  function input(): MailDraftInput {
    return {
      expectedRevision: detail?.template.latestRevision ?? 0,
      subject: subject.trim(),
      text: bodyText.trim(),
      html: bodyHtml,
    };
  }

  async function perform(task: () => Promise<void>) {
    if (pending || loading || !detail) return;
    setPending(true);
    setError('');
    try {
      await task();
    } catch (failure) {
      const description = getErrorMessage(failure);
      setError(description);
      void message.error(description);
    } finally {
      setPending(false);
    }
  }

  function insertVariable(variable: string) {
    const textarea = htmlInputRef.current;
    if (!textarea) return;
    const { start, end } = htmlSelectionRef.current;
    const from = Math.max(0, Math.min(start, bodyHtml.length));
    const to = Math.max(from, Math.min(end, bodyHtml.length));
    const nextPosition = from + variable.length;
    setBodyHtml(bodyHtml.slice(0, from) + variable + bodyHtml.slice(to));
    setPreview(null);
    htmlSelectionRef.current = { start: nextPosition, end: nextPosition };
    requestAnimationFrame(() => {
      textarea.focus();
      textarea.setSelectionRange(nextPosition, nextPosition);
    });
  }

  function showPreview(revision?: MailTemplateRevision) {
    void perform(async () => {
      const sample = revision
        ? editableFromRevision(revision, detail?.template.subject ?? '', layout).content
        : { subject, text: bodyText, html: bodyHtml };
      const result = await mailTemplatesApi.preview(keyName, {
        expectedRevision: detail?.template.latestRevision ?? 0,
        subject: sample.subject.trim(),
        text: sample.text.trim(),
        html: sample.html,
      });
      setPreview(result);
      setTab('preview');
    });
  }

  function saveDraft() {
    void perform(async () => {
      const result = await mailTemplatesApi.saveDraft(keyName, input());
      setDetail(result);
      applyRevision(result.draft, layout, result.template.subject);
      setPreview(null);
      onChanged();
      void message.success(t('admin:emailTemplates.saved'));
    });
  }

  function publish() {
    if (!canPublish || !detail?.draft) return;
    void perform(async () => {
      const result = await mailTemplatesApi.publish(
        keyName,
        detail.draft!.id,
        detail.template.publishedRevisionId ?? 0,
      );
      setDetail(result);
      setPreview(null);
      onChanged();
      void message.success(t('admin:emailTemplates.published'));
    });
  }

  function restore(revisionId: number) {
    void perform(async () => {
      if (!detail) return;
      const result = await mailTemplatesApi.restore(keyName, revisionId, detail.template.latestRevision);
      setDetail(result);
      applyRevision(result.draft, layout, result.template.subject);
      setTab('editor');
      setPreview(null);
      onChanged();
      void message.success(t('admin:emailTemplates.restored'));
    });
  }

  return <div className="clucky-mail-workspace">
    <Space className="clucky-mail-workspace-heading" direction="vertical" size={2}>
      <Typography.Text type="secondary">{keyName}</Typography.Text>
      <Typography.Title level={4}>{detail?.template.subject || t('admin:emailTemplates.loading')}</Typography.Title>
      <Typography.Text type="secondary">{t('admin:emailTemplates.help')}</Typography.Text>
    </Space>
    {error && <Alert type="error" showIcon message={error} closable onClose={() => setError('')} />}
    {loading ? <Spin /> : !detail
      ? <Button onClick={() => setReload(value => value + 1)}>{t('admin:emailTemplates.retry')}</Button>
      : <>
        <Space wrap>
          {dirty && <Typography.Text type="warning">{t('admin:emailTemplates.unsaved')}</Typography.Text>}
          {legacy && <Typography.Text type="secondary">{t('admin:emailTemplates.convertedHint')}</Typography.Text>}
        </Space>
        <Tabs activeKey={tab} onChange={setTab} items={[
          { key: 'editor', label: t('admin:emailTemplates.editor') },
          { key: 'preview', label: t('admin:emailTemplates.preview') },
          { key: 'history', label: t('admin:emailTemplates.history') },
        ]} />
        {tab === 'editor' && <div className="clucky-mail-form">
          <label htmlFor="clucky-mail-subject">{t('admin:emailTemplates.subject')}</label>
          <Input id="clucky-mail-subject" value={subject} maxLength={255}
            onChange={event => { setSubject(event.target.value); setPreview(null); }} disabled={pending} />
          <label htmlFor="clucky-mail-html">{t('admin:emailTemplates.htmlSource')}</label>
          <Typography.Text type="secondary">{t('admin:emailTemplates.htmlHelp')}</Typography.Text>
          <textarea id="clucky-mail-html" ref={htmlInputRef} className="clucky-mail-source"
            aria-label={t('admin:emailTemplates.htmlSource')} spellCheck={false} wrap="off"
            maxLength={100000} value={bodyHtml} disabled={pending}
            onChange={event => {
              setBodyHtml(event.target.value);
              setPreview(null);
              htmlSelectionRef.current = { start: event.target.selectionStart, end: event.target.selectionEnd };
            }}
            onSelect={event => {
              const target = event.currentTarget;
              htmlSelectionRef.current = { start: target.selectionStart, end: target.selectionEnd };
            }} />
          <Space className="clucky-mail-source-info" wrap>
            <Typography.Text type="secondary"><CodeOutlined /> HTML · {bodyHtml.length.toLocaleString()} / 100,000</Typography.Text>
          </Space>
          <Typography.Text strong>{t('admin:emailTemplates.layoutVariables')}</Typography.Text>
          <Space wrap size={[6, 6]}>
            {layoutVariables.map(variable => <Button key={variable} size="small" disabled={pending}
              onClick={() => insertVariable(variable)}>{variable}</Button>)}
          </Space>
          {eventVariables(keyName).length > 0 && <>
            <Typography.Text strong>{t('admin:emailTemplates.eventVariables')}</Typography.Text>
            <Space wrap size={[6, 6]}>
              {eventVariables(keyName).map(variable => <Button key={variable} size="small" disabled={pending}
                onClick={() => insertVariable(variable)}>{variable}</Button>)}
            </Space>
          </>}
          <Typography.Text type="secondary">{t('admin:emailTemplates.variablesHint')}</Typography.Text>
          <Space wrap>
            <Button icon={<EyeOutlined />} loading={pending} onClick={() => showPreview()}>
              {t('admin:emailTemplates.preview')}
            </Button>
            <Button icon={<SaveOutlined />} loading={pending} onClick={saveDraft} disabled={!dirty && !legacy && !!detail.draft}>
              {t('admin:emailTemplates.saveDraft')}
            </Button>
            <Popconfirm title={t('admin:emailTemplates.confirmPublish')} onConfirm={publish}
              okText={t('admin:emailTemplates.publish')}>
              <Button type="primary" icon={<SendOutlined />} loading={pending} disabled={!canPublish}>
                {t('admin:emailTemplates.publish')}
              </Button>
            </Popconfirm>
          </Space>
          {detail.draft && (dirty || legacy) && <Typography.Text type="secondary">
            {t('admin:emailTemplates.saveBeforePublish')}
          </Typography.Text>}
        </div>}
        {tab === 'preview' && <div className="clucky-mail-preview">
          {preview ? <>
            <Typography.Paragraph><strong>{t('admin:emailTemplates.subject')}:</strong> {preview.subject}</Typography.Paragraph>
            <iframe title={t('admin:emailTemplates.preview')} sandbox="" referrerPolicy="no-referrer" srcDoc={preview.html} />
          </> : <Button onClick={() => showPreview()} loading={pending} icon={<EyeOutlined />}>
            {t('admin:emailTemplates.preview')}
          </Button>}
        </div>}
        {tab === 'history' && <Table<MailTemplateRevision> size="small" rowKey="id"
          pagination={{ pageSize: 8 }} dataSource={detail.history} columns={[
            { title: t('admin:emailTemplates.version'), dataIndex: 'revision', render: value => `v${value}` },
            { title: t('admin:emailTemplates.subject'), dataIndex: 'subject', ellipsis: true },
            { title: t('admin:emailTemplates.state'), render: (_, row) =>
              <Tag color={row.id === detail.template.publishedRevisionId ? 'success' : row.publishedAt ? 'default' : 'gold'}>
                {row.id === detail.template.publishedRevisionId ? t('admin:emailTemplates.current')
                  : row.publishedAt ? t('admin:emailTemplates.archived') : t('admin:emailTemplates.draft')}
              </Tag> },
            { title: t('admin:emailTemplates.actor'), dataIndex: 'actorId',
              render: value => value === 0 ? 'System' : `#${value}` },
            { title: t('admin:emailTemplates.actions'), render: (_, row) => <Space>
              <Button size="small" icon={<EyeOutlined />} loading={pending} onClick={() => showPreview(row)}>
                {t('admin:emailTemplates.preview')}
              </Button>
              <Popconfirm title={t(dirty
                ? 'admin:emailTemplates.confirmRestoreUnsaved'
                : 'admin:emailTemplates.confirmRestore')}
                onConfirm={() => restore(row.id)}>
                <Button size="small" disabled={pending}>{t('admin:emailTemplates.restore')}</Button>
              </Popconfirm>
            </Space> },
          ]} />}
      </>}
  </div>;
}
