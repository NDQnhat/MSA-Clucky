/**
 * API client and types for Module M2 (Catalog & Store Service).
 *
 * All requests route through the API Gateway.
 */
import { request } from "./api";
import type { LocalizedText } from "./bilingual";
import type { StoreStatus } from "./types/store";
import { stores as fallbackStores } from "./products";

export interface StoreItem {
  id: number;
  name: string;
  nameI18n?: LocalizedText | null;
  address: string;
  district?: string | null;
  districtI18n?: LocalizedText | null;
  city?: string | null;
  cityI18n?: LocalizedText | null;
  phone: string;
  openTime: string;
  closeTime: string;
  status: StoreStatus;
  deliveryArea: string;
  deliveryAreaI18n?: LocalizedText | null;
  managerName?: string;
  isOpenNow?: boolean;
}

export interface CategoryItem {
  id: number;
  name: string;
  nameI18n?: LocalizedText | null;
  imageUrl?: string;
  sortOrder: number;
  isActive: boolean;
}

export interface ProductItem {
  id: number;
  slug: string;
  name: string;
  nameI18n?: LocalizedText | null;
  categoryId?: number;
  categoryName?: string;
  categoryNameI18n?: LocalizedText | null;
  category?: string;
  description: string;
  descriptionI18n?: LocalizedText | null;
  basePrice: number;
  price: number;
  imageUrl?: string | null;
  image?: string | null;
  alt?: string;
  kcal?: number | null;
  spicyLevel?: number | null;
  isActive: boolean;
  isAvailable?: boolean | null;
  isSoldOut?: boolean | null;
  isBestSeller?: boolean | null;
  rating?: number | null;
  reviewCount?: number | null;
  options?: {
    id: string;
    name: string;
    nameI18n?: LocalizedText | null;
    required: boolean;
    choices: { id: string; name: string; nameI18n?: LocalizedText | null; priceAdjustment: number }[];
  }[];
  toppings?: { id: string; name: string; nameI18n?: LocalizedText | null; price: number }[];
  comboItems?: ComboItem["items"];
}

export interface ComboItem {
  id: number;
  name: string;
  nameI18n?: LocalizedText | null;
  slug: string;
  description: string;
  descriptionI18n?: LocalizedText | null;
  comboPrice: number;
  imageUrl?: string | null;
  startAt?: string;
  endAt?: string;
  isActive: boolean;
  isAvailable?: boolean | null;
  isSoldOut?: boolean | null;
  items: {
    id?: number;
    productId: number;
    productName: string;
    productNameI18n?: LocalizedText | null;
    quantity: number;
    optionId?: number | null;
  }[];
}

export function comboAsProduct(combo: ComboItem): ProductItem {
  return {
    id: combo.id,
    // The catalog's name-based slug is not guaranteed to start with "combo-".
    // Use a stable, typed URL so a combo never resolves as a product sharing its ID.
    slug: `combo-${combo.id}`,
    name: combo.name,
    nameI18n: combo.nameI18n,
    categoryId: 3,
    categoryName: "Combo",
    categoryNameI18n: { vi: "Combo", en: "Combos" },
    category: "Combo",
    description: combo.description,
    descriptionI18n: combo.descriptionI18n,
    basePrice: combo.comboPrice,
    price: combo.comboPrice,
    imageUrl: combo.imageUrl,
    image: combo.imageUrl,
    alt: combo.name,
    isActive: combo.isActive,
    isAvailable: combo.isAvailable,
    isSoldOut: combo.isSoldOut,
    options: [],
    toppings: [],
    comboItems: combo.items,
  };
}

export const catalogApi = {
  getStores: async (activeOnly = false, lang?: string): Promise<StoreItem[]> => {
    try {
      const data = await request<StoreItem[]>(
        `/api/stores?activeOnly=${activeOnly}${lang ? `&lang=${encodeURIComponent(lang)}` : ""}`,
        { lang }
      );
      if (Array.isArray(data) && data.length > 0) return data;
      return (fallbackStores as StoreItem[]) || [];
    } catch {
      return (fallbackStores as StoreItem[]) || [];
    }
  },
  getStoreById: (id: number, lang?: string) =>
    request<StoreItem>(`/api/stores/${id}${lang ? `?lang=${encodeURIComponent(lang)}` : ""}`, { lang }),
  getCategories: (activeOnly = true, lang?: string) =>
    request<CategoryItem[]>(
      `/api/catalog/categories?activeOnly=${activeOnly}${lang ? `&lang=${encodeURIComponent(lang)}` : ""}`,
      { lang }
    ),
  getProducts: async (params?: {
    storeId?: number;
    categoryId?: number;
    keyword?: string;
    spicyLevel?: number;
    activeOnly?: boolean;
    page?: number;
    size?: number;
    lang?: string;
  }): Promise<ProductItem[]> => {
    const query = new URLSearchParams();
    if (params?.storeId) query.set("storeId", String(params.storeId));
    if (params?.categoryId) query.set("categoryId", String(params.categoryId));
    if (params?.keyword) query.set("keyword", params.keyword);
    if (params?.spicyLevel !== undefined) query.set("spicyLevel", String(params.spicyLevel));
    if (params?.activeOnly !== undefined) query.set("activeOnly", String(params.activeOnly));
    if (params?.page !== undefined) query.set("page", String(params.page));
    if (params?.size !== undefined) query.set("size", String(params.size));
    if (params?.lang) query.set("lang", params.lang);
    const qs = query.toString();

    const raw = await request<
      ProductItem[] | { content: ProductItem[]; page: number; size: number; totalElements: number; totalPages: number }
    >(`/api/catalog/products${qs ? `?${qs}` : ""}`, { lang: params?.lang });
    const products = Array.isArray(raw) ? raw : (raw?.content || []);
    if (params?.categoryId !== undefined) return products;
    if (params?.page !== undefined || params?.size !== undefined) return products;

    const comboQuery = new URLSearchParams();
    if (params?.storeId) comboQuery.set("storeId", String(params.storeId));
    comboQuery.set("activeOnly", String(params?.activeOnly ?? true));
    if (params?.lang) comboQuery.set("lang", params.lang);
    const combos = await request<ComboItem[]>(`/api/catalog/combos?${comboQuery.toString()}`, { lang: params?.lang }).catch(() => []);
    const mappedCombos = combos.map(comboAsProduct);
    const keyword = params?.keyword?.toLocaleLowerCase();
    const filteredCombos = keyword
      ? mappedCombos.filter((combo) =>
          [combo.name, combo.nameI18n?.vi, combo.nameI18n?.en, combo.description, combo.descriptionI18n?.vi, combo.descriptionI18n?.en]
            .some((part) => part?.toLocaleLowerCase().includes(keyword))
        )
      : mappedCombos;

    return [...products, ...filteredCombos];
  },
  getProductById: async (id: number, storeId?: number, lang?: string): Promise<ProductItem> => {
    const query = new URLSearchParams();
    if (storeId) query.set("storeId", String(storeId));
    if (lang) query.set("lang", lang);
    const qs = query.toString();
    return request<ProductItem>(`/api/catalog/products/${id}${qs ? `?${qs}` : ""}`, { lang });
  },
  getComboProductById: async (id: number, storeId?: number, lang?: string): Promise<ProductItem> =>
    comboAsProduct(await catalogApi.getComboById(id, storeId, lang)),
  getCombos: (storeId?: number, activeOnly = true, lang?: string) => {
    const query = new URLSearchParams({ activeOnly: String(activeOnly) });
    if (storeId) query.set("storeId", String(storeId));
    if (lang) query.set("lang", lang);
    return request<ComboItem[]>(`/api/catalog/combos?${query.toString()}`, { lang });
  },
  getComboById: (id: number, storeId?: number, lang?: string) => {
    const query = new URLSearchParams();
    if (storeId) query.set("storeId", String(storeId));
    if (lang) query.set("lang", lang);
    const qs = query.toString();
    return request<ComboItem>(`/api/catalog/combos/${id}${qs ? `?${qs}` : ""}`, { lang });
  },
};
