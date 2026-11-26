export interface LocalizedText {
  vi: string;
  en: string;
}

export type BilingualValue = LocalizedText | string | null | undefined;

export function pickLocalized(
  i18nValue: LocalizedText | null | undefined,
  fallbackText: string | null | undefined,
  lang: string = "vi"
): string {
  const normalized = lang.toLowerCase().startsWith("en") ? "en" : "vi";
  if (i18nValue) {
    const preferred = normalized === "en" ? i18nValue.en : i18nValue.vi;
    const secondary = normalized === "en" ? i18nValue.vi : i18nValue.en;
    if (preferred && preferred.trim()) return preferred;
    if (secondary && secondary.trim()) return secondary;
  }
  return fallbackText ?? "";
}

export function normalizeForBilingualSearch(value: string | null | undefined): string {
  if (!value) return "";
  return value
    .normalize("NFD")
    .replace(/\p{M}+/gu, "")
    .replace(/[\u0110\u0111]/g, "d")
    .toLowerCase()
    .replace(/\s+/g, " ")
    .trim();
}

export function matchesBilingualSearch(query: string, ...fields: BilingualValue[]): boolean {
  const needle = normalizeForBilingualSearch(query);
  if (!needle) return true;
  return fields.some((field) => {
    if (!field) return false;
    if (typeof field === "string") {
      return normalizeForBilingualSearch(field).includes(needle);
    }
    return (
      normalizeForBilingualSearch(field.vi).includes(needle) ||
      normalizeForBilingualSearch(field.en).includes(needle)
    );
  });
}
