import i18n from "../i18n/index.ts";
export type FulfillmentMode = 'DELIVERY' | 'DINE_IN' | 'TAKE_AWAY' | 'PICKUP_UNKNOWN';

export function displayPaymentStatus(order: { paymentStatus?: string; merchantPaymentStatus?: string | null }, trackingStatus?: string | null) {
  const merchantStatus = order.merchantPaymentStatus ?? trackingStatus;
  if (order.paymentStatus === 'PENDING' && (merchantStatus === 'PAID_SIMULATED' || merchantStatus === 'ADVANCE_RETURNED')) {
    return merchantStatus;
  }
  return order.paymentStatus ?? 'UNKNOWN';
}

export function maskedReceiverPhone(phone: string | null | undefined): string | null {
  const digits = phone?.replace(/\D/g, '') ?? '';
  if (digits.length < 9 || digits.length > 15) return null;
  return `${digits.slice(0, 4)}***${digits.slice(-3)}`;
}

export function getFulfillmentMode(order: {
  orderType?: string | null;
  type?: string | null;
  diningOption?: string | null;
  orderSource?: string | null;
}): FulfillmentMode {
  if (order.orderType === 'DELIVERY' || order.type === 'DELIVERY') return 'DELIVERY';
  if (order.diningOption === 'DINE_IN') return 'DINE_IN';
  if (order.diningOption === 'TAKE_AWAY') return 'TAKE_AWAY';
  return order.orderSource === 'WEB' ? 'TAKE_AWAY' : 'PICKUP_UNKNOWN';
}

export function fulfillmentLabel(order: Parameters<typeof getFulfillmentMode>[0]): string {
  switch (getFulfillmentMode(order)) {
    case 'DELIVERY': return i18n.t("shipping:ui.delivery");
    case 'DINE_IN': return i18n.t("shipping:ui.dineInCustomerCollectsAtTheCounter");
    case 'TAKE_AWAY': return i18n.t("shipping:ui.takeAway");
    default: return i18n.t("shipping:ui.storePickup");
  }
}

export function fulfillmentShortLabel(order: Parameters<typeof getFulfillmentMode>[0]): string {
  switch (getFulfillmentMode(order)) {
    case 'DELIVERY': return i18n.t("shipping:ui.delivery2");
    case 'DINE_IN': return i18n.t("shipping:ui.dineIn");
    case 'TAKE_AWAY': return i18n.t("shipping:ui.takeAway2");
    default: return i18n.t("shipping:ui.storePickup2");
  }
}
