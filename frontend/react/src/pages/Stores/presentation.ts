import i18n from "../../i18n/index.ts";
import dayjs from 'dayjs';
import { matchesBilingualSearch, type BilingualValue } from '../../lib/bilingual';

export function storeTime(value: string | null | undefined) {
  if (!value || !/^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$/.test(value)) return null;
  const [hours, minutes] = value.split(':').map(Number);
  return dayjs().startOf('day').hour(hours).minute(minutes);
}

export function matchesStoreSearch(values: BilingualValue[], query: string) {
  return matchesBilingualSearch(query, ...values);
}

export function readinessLabel(value?: string) {
  return ({ get READY() { return i18n.t("business:orderprogress.ready"); }, get UNPROVISIONED() { return i18n.t("admin:ui.noAccountAvailable"); }, get PENDING() { return i18n.t("admin:ui.provisioningAccounts"); }, get PROVISIONED() { return i18n.t("admin:ui.awaitingActivation"); } } as Record<string, string>)[value ?? ''] ?? i18n.t("admin:ui.unknown");
}
