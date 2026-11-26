import { useTranslation } from "react-i18next";
import { useEffect, useRef, useState } from 'react';
import { Alert, Button, DatePicker, Form, InputNumber, Modal, Progress, Select, Switch } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { getErrorMessage, type Product } from '../../lib/api';
import type { AdminCombo, ComboPayload } from '../../lib/catalog-administration';
import { isLocalizedViValid, pickLocalized, toLocalizedInput, toLocalizedPayload, type LocalizedText } from '../../lib/bilingual';
import { BilingualField } from '../../components/ui/BilingualField';
import { CatalogImage, ImageField } from './ImageField';
import { LanguageSwitcher } from '../../i18n/language-switcher';
import { matchesCatalogSearch } from './presentation';

type Values = {
  name: LocalizedText;
  description: LocalizedText;
  comboPrice: number;
  isActive: boolean;
  items: ComboPayload['items'];
  startAt?: Dayjs | null;
  endAt?: Dayjs | null;
};

export function ComboForm({ open, combo, products, onClose, onSave }: {
  open: boolean; combo: AdminCombo | null; products: Product[]; onClose: () => void;
  onSave: (data: ComboPayload, file?: File, progress?: (percent: number) => void) => Promise<void>;
}) {
  const { t, i18n } = useTranslation();
  const [form] = Form.useForm<Values>();
  const items = Form.useWatch('items', form) as Values['items'] | undefined;
  const [file, setFile] = useState<File | null>(null);
  const [saving, setSaving] = useState(false);
  const lock = useRef(false);
  const [error, setError] = useState('');
  const [progress, setProgress] = useState(0);
  useEffect(() => {
    if (!open) return;
    form.resetFields();
    form.setFieldsValue(combo ? {
      name: toLocalizedInput(combo.nameI18n, combo.name),
      description: toLocalizedInput(combo.descriptionI18n, combo.description),
      comboPrice: combo.comboPrice,
      isActive: combo.isActive,
      items: structuredClone(combo.items),
      startAt: combo.startAt ? dayjs(combo.startAt) : null,
      endAt: combo.endAt ? dayjs(combo.endAt) : null,
    } : { name: { vi: '', en: '' }, description: { vi: '', en: '' }, comboPrice: 0, isActive: true, items: [] });
    setFile(null); setError(''); setProgress(0);
  }, [open, combo, form]);
  const save = async (values: Values) => {
    if (lock.current) return;
    lock.current = true; setSaving(true); setError('');
    try {
      await onSave({
        name: toLocalizedPayload(values.name) ?? { vi: '', en: '' },
        description: toLocalizedPayload(values.description) ?? { vi: '', en: '' },
        comboPrice: values.comboPrice,
        isActive: Boolean(values.isActive),
        startAt: values.startAt?.toISOString() ?? null,
        endAt: values.endAt?.toISOString() ?? null,
        items: values.items.map(item => ({ ...item, optionId: item.optionId ?? null, productName: products.find(product => product.id === item.productId)?.name ?? '' })),
      }, file ?? undefined, setProgress);
      onClose();
    } catch (failure) {
      if (!(failure && typeof failure === 'object' && 'errorFields' in failure)) setError(getErrorMessage(failure));
    } finally { lock.current = false; setSaving(false); }
  };
  return <Modal className="catalog-modal" open={open} width={1040} centered
    footer={buttons => <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}><LanguageSwitcher /><div>{buttons}</div></div>}
    title={<div className="catalog-modal-heading"><span className="catalog-eyebrow">{t("admin:ui.menuCombos")}</span><h3>{combo ? t("admin:ui.editCombo") : t("admin:ui.createANewCombo")}</h3><p>{t("admin:ui.combineDishesQuantitiesAndOptionsAtOnePrice")}</p></div>}
    okText={combo ? t("business:account.saveChanges") : t("admin:ui.createCombo")} cancelText={t("business:account.cancel")} confirmLoading={saving} cancelButtonProps={{ disabled: saving }} keyboard={!saving}
    onOk={() => form.submit()} onCancel={() => { if (!lock.current) onClose(); }} maskClosable={false} closable={!saving}>
    {error && <Alert type="error" showIcon title={error} description={t("admin:ui.ifTheDataHasChangedCloseThisForm")} />}
    <Form name="catalog-combo" form={form} layout="vertical" disabled={saving} scrollToFirstError={{ focus: true }} onFinish={values => void save(values)}>
      <div className="catalog-editor"><div className="catalog-editor-main">
      <section className="catalog-section">
        <div className="catalog-section-heading"><span>01</span><h4>{t("admin:ui.comboInformation")}</h4></div>
        <Form.Item name="name" label={t("admin:ui.comboName")} required rules={[{ validator: async (_, val: LocalizedText) => { if (!isLocalizedViValid(val)) throw new Error(t("admin:ui.enterAComboName")); if ((val?.vi?.length ?? 0) > 180 || (val?.en?.length ?? 0) > 180) throw new Error('Max 180'); } }]}><BilingualField maxLength={180} requiredVi viPlaceholder={t("admin:ui.comboNameShownOnTheMenu")} enPlaceholder="English combo name" /></Form.Item>
        <Form.Item name="description" label={t("admin:ui.comboDescription")} required rules={[{ validator: async (_, val: LocalizedText) => { if (!isLocalizedViValid(val)) throw new Error(t("admin:ui.addAComboDescription")); } }]}><BilingualField multiline rows={3} requiredVi viPlaceholder={t("admin:ui.describeTheDishesAndPortionsInThisCombo")} enPlaceholder="English combo description" /></Form.Item>
        <Form.Item name="comboPrice" label={t("admin:ui.comboPrice")} rules={[{ required: true }, { type: 'integer', min: 0, max: 999999999999 }]}><InputNumber<number> min={0} max={999999999999} precision={0} formatter={value => `${value ?? ''}`.replace(/\B(?=(\d{3})+(?!\d))/g, '.')} parser={value => Number(value?.replace(/\./g, ''))} /></Form.Item>
      </section>
      <section className="catalog-section">
      <div className="catalog-section-heading"><span>02</span><h4>{t("admin:ui.dishesInThisCombo")}</h4></div>
      <Form.List name="items" rules={[{ validator: async (_, rows) => { if (!rows?.length) throw new Error(t("admin:ui.aComboMustContainAtLeastOneDish")); } }]}>
        {(fields, { add, remove }, meta) => <>
          {!fields.length && <div className="catalog-empty-note">{products.length ? t("admin:ui.chooseTheDishesThenSetTheirQuantitiesAnd") : t("admin:ui.noDishesAvailableCreateAMenuItemBefore")}</div>}
          {fields.map(field => {
            const product = products.find(candidate => candidate.id === items?.[field.name]?.productId);
            const options = product?.options?.flatMap(group => group.choices.map(choice => ({
              value: Number(choice.id.replace(/^opt-/, '')),
              label: `${pickLocalized(group.nameI18n, group.name, i18n.language)}: ${pickLocalized(choice.nameI18n, choice.name, i18n.language)}`,
            }))) ?? [];
            return <div key={field.key} className="catalog-option-card">
              <div className="catalog-option-header"><span>{t("admin:ui.dish")} {field.name + 1}</span><Button size="small" type="text" danger icon={<DeleteOutlined />} disabled={saving} onClick={() => remove(field.name)} aria-label={t("admin:ui.removeDishFromTheCombo", { value1: field.name + 1 })}>{t("admin:ui.removeDish")}</Button></div>
              <Form.Item name={[field.name, 'id']} hidden><InputNumber /></Form.Item>
              <div className="catalog-combo-product">
              <div className="catalog-thumb"><CatalogImage url={product?.imageUrl} name={pickLocalized(product?.nameI18n, product?.name, i18n.language) || t("admin:ui.noDishSelected")} /></div>
              <Form.Item name={[field.name, 'productId']} label={t("business:catalog.dish")} rules={[{ required: true, message: t("admin:ui.selectADish") }]}><Select showSearch placeholder={t("admin:ui.searchAndSelectADish")} optionFilterProp="label"
                filterOption={(input, option) => matchesCatalogSearch([option?.label, option?.nameI18n], input)}
                onChange={() => form.setFieldValue(['items', field.name, 'optionId'], null)}
                optionRender={option => <div className="catalog-select-option"><div className="catalog-thumb"><CatalogImage url={option.data.imageUrl} name={option.data.label} preview={false} /></div><span>{option.data.label}</span></div>}
                options={products.map(candidate => ({ value: candidate.id, label: `${pickLocalized(candidate.nameI18n, candidate.name, i18n.language)}${candidate.isActive ? '' : t("admin:ui.notForSale")}`, nameI18n: candidate.nameI18n, imageUrl: candidate.imageUrl }))} /></Form.Item>
              </div>
              <div className="catalog-field-grid">
              <Form.Item name={[field.name, 'quantity']} label={t("admin:ui.quantity")} rules={[{ required: true }, { type: 'integer', min: 1, max: 2147483647 }]}><InputNumber min={1} max={2147483647} precision={0} /></Form.Item>
              <Form.Item name={[field.name, 'optionId']} label={t("admin:ui.option")} rules={[{ validator: async (_, selected) => {
                if (selected != null && !options.some(option => option.value === selected)) throw new Error(t("admin:ui.thisOptionDoesNotBelongToTheSelected"));
              } }]}><Select allowClear disabled={!product || !options.length || saving} placeholder={product && !options.length ? t("admin:ui.thisDishHasNoOptions") : t("admin:ui.noFixedOption")} options={options} /></Form.Item>
              </div>
            </div>;
          })}
          <Form.ErrorList errors={meta.errors} />
          <Button className="catalog-add-row" icon={<PlusOutlined />} disabled={saving || !products.length} onClick={() => add({ quantity: 1, optionId: null })}>{t("admin:ui.addADishToTheCombo")}</Button>
        </>}
      </Form.List>
      </section>
      <section className="catalog-section">
        <div className="catalog-section-heading"><span>03</span><h4>{t("admin:ui.availabilityPeriod")}</h4></div>
        <div className="catalog-field-grid">
          <Form.Item name="startAt" label={t("admin:ui.startsAt")}><DatePicker showTime placeholder={t("admin:ui.noStartLimit")} format="DD/MM/YYYY HH:mm" /></Form.Item>
          <Form.Item name="endAt" label={t("admin:ui.endsAt")} dependencies={['startAt']} rules={[({ getFieldValue }) => ({ validator: async (_, end) => {
            const start = getFieldValue('startAt') as Dayjs | null;
            if (end && start && !end.isAfter(start)) throw new Error(t("admin:ui.theEndMustBeAfterTheStart"));
          } })]}><DatePicker showTime placeholder={t("admin:ui.noEndLimit")} format="DD/MM/YYYY HH:mm" /></Form.Item>
        </div><p className="catalog-hint">{t("admin:ui.leaveDatesEmptyForUnlimitedAvailabilityTheSale")}</p>
      </section>
      </div><aside className="catalog-editor-aside">
        <h4>{t("admin:ui.comboImage")}</h4>
        <ImageField file={file} url={combo?.imageUrl} name={combo?.name || t("admin:ui.newCombo")} disabled={saving} onChange={setFile} />
        {saving && file && <Progress percent={progress} size="small" />}
        <div className="catalog-publication"><div><strong>{t("admin:ui.availableForSale")}</strong><p>{t("admin:ui.comboAvailabilityAlsoDependsOnItsScheduleAnd")}</p></div><Form.Item name="isActive" valuePropName="checked"><Switch aria-label={t("admin:ui.allowComboSales")} /></Form.Item></div>
      </aside></div>
    </Form>
  </Modal>;
}
