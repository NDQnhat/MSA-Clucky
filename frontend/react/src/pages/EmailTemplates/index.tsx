import { useEffect, useMemo, useState } from 'react';
import { Alert, App, Card, Input, List, Spin, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { getErrorMessage } from '../../lib/api';
import { mailTemplatesApi, type MailTemplateSummary } from '../../lib/mail-templates';
import { TemplateEditor } from './TemplateEditor';
import './mail-templates.css';

export function EmailTemplatesPage() {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const [templates, setTemplates] = useState<MailTemplateSummary[]>([]);
  const [selected, setSelected] = useState('');
  const [search, setSearch] = useState('');
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [unsaved, setUnsaved] = useState(false);
  const [editorBusy, setEditorBusy] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    mailTemplatesApi.list(controller.signal)
      .then(rows => {
        if (controller.signal.aborted) return;
        setTemplates(rows);
        setSelected(current => current || rows[0]?.key || '');
      })
      .catch(failure => {
        if (controller.signal.aborted) return;
        const description = getErrorMessage(failure);
        setError(description);
        void message.error(description);
      })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [revision, message]);

  const visible = useMemo(() =>
    templates.filter(row => `${row.key} ${row.subject}`.toLowerCase().includes(search.trim().toLowerCase())),
  [templates, search]);

  function selectTemplate(nextKey: string) {
    if (nextKey === selected || editorBusy) return;
    const navigate = () => {
      setUnsaved(false);
      setSelected(nextKey);
    };
    if (unsaved) {
      modal.confirm({
        title: t('admin:emailTemplates.unsavedConfirmTitle'),
        content: t('admin:emailTemplates.unsavedConfirmBody'),
        okText: t('admin:emailTemplates.discardChanges'),
        okButtonProps: { danger: true },
        cancelText: t('admin:emailTemplates.keepEditing'),
        onOk: navigate,
      });
      return;
    }
    navigate();
  }

  return <div className="clucky-mail-page">
    <Typography.Title level={2}>{t('admin:emailTemplates.title')}</Typography.Title>
    <Typography.Paragraph type="secondary">{t('admin:emailTemplates.description')}</Typography.Paragraph>
    {error && <Alert type="error" message={error} showIcon closable onClose={() => setError('')} />}
    <div className="clucky-mail-columns">
      <Card className="clucky-mail-directory" title={t('admin:emailTemplates.list')} extra={<Tag>{templates.length}</Tag>}>
        <Input.Search allowClear value={search} onChange={event => setSearch(event.target.value)}
          placeholder={t('admin:emailTemplates.search')} />
        {loading ? <Spin /> : <List dataSource={visible} renderItem={row => <List.Item>
          <button type="button" onClick={() => selectTemplate(row.key)}
            aria-current={selected === row.key ? 'page' : undefined}
            aria-disabled={editorBusy && selected !== row.key}
            className={`clucky-mail-selector${row.key === selected ? ' selected' : ''}`}>
            <strong>{row.key}</strong><span>{row.subject}</span>
          </button>
        </List.Item>} />}
      </Card>
      <Card className="clucky-mail-editor-card">
        {selected ? <TemplateEditor key={selected} keyName={selected}
          onChanged={() => setRevision(value => value + 1)}
          onUnsavedChange={setUnsaved} onPendingChange={setEditorBusy} />
          : <Typography.Text type="secondary">{t('admin:emailTemplates.choose')}</Typography.Text>}
      </Card>
    </div>
  </div>;
}
