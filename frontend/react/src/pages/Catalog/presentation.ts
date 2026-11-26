import { matchesBilingualSearch, type BilingualValue } from '../../lib/bilingual';

export function matchesCatalogSearch(values: BilingualValue[], query: string) {
  return matchesBilingualSearch(query, ...values);
}

export function isCatalogImage(file: { type: string; size: number }) {
  return ['image/png', 'image/jpeg', 'image/webp'].includes(file.type)
    && Number.isFinite(file.size) && file.size > 0 && file.size <= 5 * 1024 * 1024;
}
