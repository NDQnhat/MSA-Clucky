import { getLocale } from "next-intl/server";
import { HomePageContent } from "@/components/home/home-page-content";
import { normalizeLocale } from "@/lib/i18n/locale";
import {
  catalogApi,
  comboAsProduct,
  type CategoryItem,
  type ComboItem,
  type ProductItem,
  type StoreItem,
} from "@/lib/catalog-and-stores.api";
import { promotionApi, reportPublicApi, type PublicItemHighlightsResponse } from "@/lib/api";
import type { PromoBanner, Voucher } from "@/lib/types/promotion";

export const dynamic = "force-dynamic";

function toHomeCardItem(item: ProductItem): ProductItem {
  return {
    id: item.id,
    slug: item.slug,
    name: item.name,
    nameI18n: item.nameI18n,
    description: item.description,
    descriptionI18n: item.descriptionI18n,
    price: item.price,
    basePrice: item.basePrice,
    image: item.image,
    imageUrl: item.imageUrl,
    category: item.category,
    categoryId: item.categoryId,
    categoryName: item.categoryName,
    categoryNameI18n: item.categoryNameI18n,
    alt: item.alt,
    spicyLevel: item.spicyLevel,
    isBestSeller: item.isBestSeller,
    isActive: item.isActive,
    isAvailable: item.isAvailable,
    isSoldOut: item.isSoldOut,
    rating: item.rating,
    reviewCount: item.reviewCount,
    comboItems: item.comboItems,
  };
}

export default async function Home() {
  const locale = normalizeLocale(await getLocale());

  const [prods, combos, banners, liveCategories, highlights, vouchers, stores] = await Promise.all([
    catalogApi.getProducts({ activeOnly: true, page: 0, size: 100, lang: locale }).catch(() => [] as ProductItem[]),
    catalogApi.getCombos(undefined, true, locale).catch(() => [] as ComboItem[]),
    promotionApi.getActiveBanners(undefined, locale).catch(() => [] as PromoBanner[]),
    catalogApi.getCategories(true, locale).catch(() => [] as CategoryItem[]),
    reportPublicApi.getHighlights().catch(() => null as PublicItemHighlightsResponse | null),
    promotionApi.getAvailableVouchers(locale).catch(() => [] as Voucher[]),
    catalogApi.getStores(true, locale).catch(() => [] as StoreItem[]),
  ]);

  const categories = liveCategories
    .filter((category) => category.isActive !== false)
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map((category) => ({
      id: category.id,
      name: category.name,
      nameI18n: category.nameI18n,
    }));

  const topProductOrder = new Map<number, number>();
  (highlights?.topProductIds ?? []).forEach((id, idx) => topProductOrder.set(Number(id), idx));

  const topComboOrder = new Map<number, number>();
  (highlights?.topComboIds ?? []).forEach((id, idx) => topComboOrder.set(Number(id), idx));

  const featuredProducts: ProductItem[] = prods
    .filter((p) => p.isActive !== false)
    .map((product) => {
      const ratingEntry = highlights?.ratings?.[String(product.id)];
      const rank = topProductOrder.get(product.id);
      return toHomeCardItem({
        ...product,
        isBestSeller: rank !== undefined ? rank < 4 : Boolean(product.isBestSeller),
        rating: ratingEntry?.averageRating ?? product.rating ?? null,
        reviewCount: ratingEntry?.reviewCount ?? product.reviewCount ?? null,
      });
    })
    .sort((a, b) => {
      const rankA = topProductOrder.get(a.id) ?? Number.MAX_SAFE_INTEGER;
      const rankB = topProductOrder.get(b.id) ?? Number.MAX_SAFE_INTEGER;
      if (rankA !== rankB) return rankA - rankB;
      return a.id - b.id;
    })
    .slice(0, 8);

  const featuredCombos: ProductItem[] = combos
    .filter((c) => c.isActive !== false)
    .sort((a, b) => {
      const rankA = topComboOrder.get(a.id) ?? Number.MAX_SAFE_INTEGER;
      const rankB = topComboOrder.get(b.id) ?? Number.MAX_SAFE_INTEGER;
      if (rankA !== rankB) return rankA - rankB;
      return a.id - b.id;
    })
    .slice(0, 4)
    .map((combo) => {
      const rank = topComboOrder.get(combo.id);
      return toHomeCardItem({
        ...comboAsProduct(combo),
        isBestSeller: rank !== undefined && rank < 2,
      });
    });

  const availableVoucherCount = Array.isArray(vouchers) ? vouchers.length : 0;
  const openStoreCount = Array.isArray(stores)
    ? stores.filter((s) => s.status === "OPEN").length
    : 0;
  const comboCategoryId =
    categories.find(
      (c) =>
        /combo/i.test(c.name) ||
        /combo/i.test(c.nameI18n?.vi ?? "") ||
        /combo/i.test(c.nameI18n?.en ?? "")
    )?.id ?? 3;

  return (
    <HomePageContent
      initialBanners={banners}
      categories={categories}
      featuredCombos={featuredCombos}
      featuredProducts={featuredProducts}
      comboCategoryId={comboCategoryId}
      availableVoucherCount={availableVoucherCount}
      openStoreCount={openStoreCount}
    />
  );
}
