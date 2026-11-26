import React from 'react';
import { Input } from 'antd';
import { useTranslation } from 'react-i18next';
import type { LocalizedText } from '../../lib/bilingual';

interface BilingualFieldProps {
  value?: LocalizedText;
  onChange?: (next: LocalizedText) => void;
  multiline?: boolean;
  rows?: number;
  maxLength?: number;
  disabled?: boolean;
  viLabel?: string;
  enLabel?: string;
  viPlaceholder?: string;
  enPlaceholder?: string;
  requiredVi?: boolean;
  showEnFallbackHint?: boolean;
  fallbackHintText?: string;
}

export const BilingualField: React.FC<BilingualFieldProps> = ({
  value = { vi: '', en: '' },
  onChange,
  multiline = false,
  rows = 2,
  maxLength,
  disabled = false,
  viLabel,
  enLabel,
  viPlaceholder,
  enPlaceholder,
  requiredVi = false,
  showEnFallbackHint = false,
  fallbackHintText,
}) => {
  const { t } = useTranslation();
  const resolvedViLabel = viLabel ?? t('admin:voucherUi.viLanguage');
  const resolvedEnLabel = enLabel ?? t('admin:voucherUi.enLanguage');
  const current: LocalizedText = {
    vi: value?.vi ?? '',
    en: value?.en ?? '',
  };

  const update = (lang: 'vi' | 'en', nextText: string) => {
    onChange?.({
      ...current,
      [lang]: nextText,
    });
  };

  const InputComponent = multiline ? Input.TextArea : Input;
  const commonProps = multiline ? { rows, maxLength } : { maxLength };

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 10 }}>
      <div>
        <div style={{ fontSize: 12, fontWeight: 600, color: '#6B6259', marginBottom: 4 }}>
          VI · {resolvedViLabel}
          {requiredVi ? <span style={{ color: '#8F1216', marginLeft: 3 }}>*</span> : null}
        </div>
        <InputComponent
          {...commonProps}
          disabled={disabled}
          value={current.vi}
          placeholder={viPlaceholder}
          onChange={(e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => update('vi', e.target.value)}
        />
      </div>
      <div>
        <div style={{ fontSize: 12, fontWeight: 600, color: '#6B6259', marginBottom: 4 }}>
          EN · {resolvedEnLabel}
        </div>
        <InputComponent
          {...commonProps}
          disabled={disabled}
          value={current.en}
          placeholder={enPlaceholder}
          onChange={(e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => update('en', e.target.value)}
        />
      </div>
      {showEnFallbackHint && current.vi.trim() && !current.en.trim() && fallbackHintText ? (
        <div style={{ gridColumn: '1 / -1', fontSize: 11.5, color: '#8B7968', marginTop: -4 }}>
          {fallbackHintText}
        </div>
      ) : null}
    </div>
  );
};
