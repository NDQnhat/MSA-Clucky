import type { CartItem, CartItemOption, CartItemTopping } from "./types/order";
import type { BackendCartDto, CartItemPayload } from "./api";
import type { LocalizedText } from "./bilingual";

function parseSnapshotArray<T>(raw: unknown, mapper: (entry: Record<string, unknown>, idx: number) => T): T[] {
  if (!raw) return [];
  let parsed = raw;
  if (typeof raw === "string") {
    try {
      parsed = JSON.parse(raw);
    } catch {
      return [];
    }
  }
  if (!Array.isArray(parsed)) return [];
  return parsed
    .filter((entry): entry is Record<string, unknown> => Boolean(entry) && typeof entry === "object")
    .map(mapper);
}

export function catalogSelectionId(value: string, prefix: "opt" | "top"): number {
  const match = new RegExp("^(?:" + prefix + "-)?([1-9]\\d*)$").exec(value);
  return match && Number.isSafeInteger(Number(match[1])) ? Number(match[1]) : NaN;
}

export function toCartPayload(item: CartItem): CartItemPayload {
  const parseId = (value: unknown): number | undefined => {
    if (typeof value === "number" && Number.isSafeInteger(value) && value > 0) return value;
    if (typeof value === "string" && /^\d+$/.test(value.trim())) {
      const num = parseInt(value.trim(), 10);
      if (Number.isSafeInteger(num) && num > 0) return num;
    }
    return undefined;
  };

  const validId = (value: number | undefined) => Number.isSafeInteger(value) && Number(value) > 0;
  const productId = parseId(item.productId);
  const comboId = parseId(item.comboId);
  const rawOptionIds = item.optionIds ?? [];
  const rawToppingIds = item.toppingIds ?? [];
  const optionIds = rawOptionIds.map((id) => parseId(id) ?? id);
  const toppingIds = rawToppingIds.map((id) => parseId(id) ?? id);
  const comboSelections = (item.comboSelections ?? []).map(selection => ({
    comboItemId: parseId(selection.comboItemId) ?? 0,
    optionIds: (selection.optionIds ?? []).map(id => parseId(id) ?? 0),
    toppingIds: (selection.toppingIds ?? []).map(id => parseId(id) ?? 0),
  }));

  if ((productId == null) === (comboId == null)
      || !validId(productId ?? comboId)
      || !Number.isSafeInteger(item.quantity) || item.quantity < 1
      || !optionIds.every((id) => validId(id as number)) || !toppingIds.every((id) => validId(id as number))
      || comboSelections.some(selection => !validId(selection.comboItemId)
        || !selection.optionIds.every(validId) || !selection.toppingIds.every(validId))
      || (productId != null && comboSelections.length > 0)) {
    throw { code: "VALIDATION_ERROR" };
  }
  return {
    productId,
    comboId,
    itemName: item.name,
    itemNameI18n: item.nameI18n,
    unitPrice: item.unitPrice,
    quantity: item.quantity,
    optionIds: optionIds as number[],
    toppingIds: toppingIds as number[],
    ...(comboId != null && comboSelections.length > 0 ? { comboSelections } : {}),
    ...((item.optionsSnapshot ?? item.options) != null ? { optionsSnapshot: item.optionsSnapshot ?? item.options } : {}),
    ...((item.toppingsSnapshot ?? item.toppings) != null ? { toppingsSnapshot: item.toppingsSnapshot ?? item.toppings } : {}),
    note: item.note,
  };
}

export function mapBackendCart(cart: BackendCartDto, previous: CartItem[] = []): CartItem[] {
  const ids = (values: number[] = []) => JSON.stringify([...values].sort((first, second) => first - second));
  const comboSignature = (selections: { comboItemId: number; optionIds: number[]; toppingIds: number[] }[] = []) =>
    JSON.stringify(selections.map(selection => [
      selection.comboItemId,
      ids(selection.optionIds),
      ids(selection.toppingIds),
    ]).sort((first, second) => Number(first[0]) - Number(second[0])));
  return cart.items.map((item) => {
    const saved = previous.find((candidate) => candidate.itemKey === item.itemKey
      || ((candidate.productId ?? null) === (item.productId ?? null) && (candidate.comboId ?? null) === (item.comboId ?? null)
        && ids(candidate.optionIds) === ids(item.optionIds)
        && ids(candidate.toppingIds) === ids(item.toppingIds)
        && comboSignature(candidate.comboSelections) === comboSignature(item.comboSelections)));
    const options = parseSnapshotArray<CartItemOption>(item.optionsSnapshot, (o, idx) => ({
      id: typeof o.id === "number" ? o.id : Number(o.id ?? idx),
      groupName: String(o.groupName ?? ""),
      groupNameI18n: (o.groupNameI18n as LocalizedText | null | undefined) ?? undefined,
      name: String(o.name ?? ""),
      nameI18n: (o.nameI18n as LocalizedText | null | undefined) ?? undefined,
      extraPrice: Number(o.extraPrice ?? 0),
    }));
    const toppings = parseSnapshotArray<CartItemTopping>(item.toppingsSnapshot, (t, idx) => ({
      id: typeof t.id === "number" ? t.id : Number(t.id ?? idx),
      name: String(t.name ?? ""),
      nameI18n: (t.nameI18n as LocalizedText | null | undefined) ?? undefined,
      extraPrice: Number(t.extraPrice ?? 0),
    }));
    return {
      ...saved,
      id: item.itemKey,
      itemKey: item.itemKey,
      storeId: item.storeId ?? saved?.storeId,
      productId: item.productId ?? undefined,
      comboId: item.comboId ?? undefined,
      itemType: item.productId != null ? "PRODUCT" : "COMBO",
      optionIds: item.optionIds ?? [],
      toppingIds: item.toppingIds ?? [],
      comboSelections: item.comboSelections ?? saved?.comboSelections,
      options: options.length > 0 ? options : saved?.options,
      toppings: toppings.length > 0 ? toppings : saved?.toppings,
      optionsSnapshot: item.optionsSnapshot ?? saved?.optionsSnapshot,
      toppingsSnapshot: item.toppingsSnapshot ?? saved?.toppingsSnapshot,
      slug: saved?.slug ?? (item.comboId != null ? `combo-${item.comboId}` : String(item.productId)),
      name: item.itemName,
      nameI18n: item.itemNameI18n ?? saved?.nameI18n,
      category: saved?.category ?? "",
      categoryI18n: saved?.categoryI18n,
      basePrice: saved?.basePrice ?? Number(item.unitPrice),
      unitPrice: Number(item.unitPrice),
      quantity: item.quantity,
      image: saved?.image ?? "/images/food-placeholder.svg",
      note: item.note,
    };
  });
}

// Options, toppings and notes distinguish separate portions of the same dish.
export function addCartItem(items: CartItem[], incoming: CartItem): CartItem[] {
  const signature = (item: CartItem) =>
    JSON.stringify([
      item.slug,
      item.unitPrice,
      item.options ?? [],
      item.toppings ?? [],
      item.comboSelections ?? [],
      item.note?.trim() ?? "",
    ]);
  const existing = items.find(
    (item) => signature(item) === signature(incoming),
  );
  return existing
    ? items.map((item) =>
        item.id === existing.id
          ? { ...item, quantity: item.quantity + incoming.quantity }
          : item,
      )
    : [...items, incoming];
}

export function cartTotals(items: CartItem[]) {
  return items.reduce(
    (total, item) => ({
      count: total.count + item.quantity,
      subtotal: total.subtotal + item.unitPrice * item.quantity,
    }),
    { count: 0, subtotal: 0 },
  );
}
