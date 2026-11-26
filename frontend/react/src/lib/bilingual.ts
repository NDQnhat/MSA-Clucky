export interface LocalizedText {
  vi: string;
  en: string;
}

export type BilingualValue = LocalizedText | string | null | undefined;

export function pickLocalized(
  i18nValue: LocalizedText | null | undefined,
  fallbackText: string | null | undefined,
  lang: string = 'vi'
): string {
  const normalized = lang.toLowerCase().startsWith('en') ? 'en' : 'vi';
  if (i18nValue) {
    const preferred = normalized === 'en' ? i18nValue.en : i18nValue.vi;
    const secondary = normalized === 'en' ? i18nValue.vi : i18nValue.en;
    if (preferred && preferred.trim()) return preferred;
    if (secondary && secondary.trim()) return secondary;
  }
  return fallbackText ?? '';
}

export function toLocalizedInput(
  i18nValue?: LocalizedText | null,
  fallbackText?: string | null
): LocalizedText {
  const vi = i18nValue?.vi ?? fallbackText ?? '';
  const en = i18nValue?.en ?? '';
  return { vi, en };
}

export function toLocalizedPayload(
  value?: LocalizedText | string | null,
  fallbackText?: string | null
): LocalizedText | undefined {
  if (typeof value === 'string') {
    const trimmed = value.trim();
    return trimmed ? { vi: trimmed, en: trimmed } : undefined;
  }
  const vi = (value?.vi ?? fallbackText ?? '').trim();
  const en = (value?.en ?? '').trim();
  if (!vi && !en) return undefined;
  return {
    vi: vi || en,
    en: en || vi,
  };
}

export function normalizeForBilingualSearch(value: string | null | undefined): string {
  if (!value) return '';
  return value
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .replace(/[\u0110\u0111]/g, 'd')
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .trim();
}

export function matchesBilingualSearch(query: string, ...fields: BilingualValue[]): boolean {
  const needle = normalizeForBilingualSearch(query);
  if (!needle) return true;
  return fields.some((field) => {
    if (!field) return false;
    if (typeof field === 'string') {
      return normalizeForBilingualSearch(field).includes(needle);
    }
    return (
      normalizeForBilingualSearch(field.vi).includes(needle) ||
      normalizeForBilingualSearch(field.en).includes(needle)
    );
  });
}

export function isLocalizedViValid(value?: LocalizedText | null): boolean {
  return Boolean(value && value.vi && value.vi.trim().length > 0);
}
