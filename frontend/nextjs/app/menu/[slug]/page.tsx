import { getLocale, getTranslations } from 'next-intl/server';
import Link from '@/components/ui-loader/navigation-link';
import { ProductDetailClient } from '@/components/product-detail-client';
import { normalizeLocale } from '@/lib/i18n/locale';
import type { Product } from '@/lib/products';
import { catalogApi, comboAsProduct } from '@/lib/catalog-and-stores.api';

export default async function ProductPage({
  params,
  searchParams,
}: {
  params: Promise<{ slug: string }>;
  searchParams: Promise<{ storeId?: string | string[] }>;
}) {
  const [{ slug }, query, rawLocale] = await Promise.all([params, searchParams, getLocale()]);
  const locale = normalizeLocale(rawLocale);
  const isCombo = /^combo-[1-9]\d*$/.test(slug);
  const idSuffix = slug.match(/(?:^|-)([1-9]\d*)$/);
  const id = idSuffix ? Number(idSuffix[1]) : NaN;
  const storeIdValue = Array.isArray(query.storeId) ? query.storeId[0] : query.storeId;
  const parsedStoreId = storeIdValue === undefined ? undefined : Number(storeIdValue);
  const storeId = Number.isSafeInteger(parsedStoreId) && (parsedStoreId ?? 0) > 0 ? parsedStoreId : undefined;

  let product: Product | null = null;
  let productIsCombo = isCombo;
  try {
    if (Number.isSafeInteger(id) && id > 0) {
      if (isCombo) {
        product = await catalogApi.getComboProductById(id, storeId, locale);
      } else {
        // Catalog also emits name-derived combo slugs (e.g. combo-clucky-classic-1).
        // Combo and product IDs overlap; prefer the combo endpoint for combo-prefixed
        // links and verify the actual slug before rendering either item.
        const prefixedCombo = slug.startsWith('combo-')
          ? await catalogApi.getComboById(id, storeId, locale).catch(() => null)
          : null;
        if (prefixedCombo?.slug === slug) {
          product = comboAsProduct(prefixedCombo);
          productIsCombo = true;
        } else {
          const candidate = await catalogApi.getProductById(id, storeId, locale).catch(() => null);
          if (candidate && (candidate.slug === slug || String(id) === slug)) {
            product = candidate;
          } else {
            const combo = prefixedCombo ?? await catalogApi.getComboById(id, storeId, locale).catch(() => null);
            if (combo?.slug === slug) {
              product = comboAsProduct(combo);
              productIsCombo = true;
            }
          }
        }
      }
    } else {
      const [allProducts, combos] = await Promise.all([
        catalogApi.getProducts({ storeId, activeOnly: true, lang: locale }),
        catalogApi.getCombos(storeId, true, locale).catch(() => []),
      ]);
      const legacyCombo = combos.find((combo) => combo.slug === slug);
      if (legacyCombo) {
        product = comboAsProduct(legacyCombo);
        productIsCombo = true;
      } else {
        const matched = allProducts.find((p) => p.slug === slug);
        if (matched) {
          productIsCombo = matched.comboItems !== undefined;
          product = matched;
        }
      }
    }
  } catch {
    product = null;
  }

  if (!product) {
    const t = await getTranslations();
    return (
      <main style={{ maxWidth: 1240, margin: '60px auto', padding: '0 24px', textAlign: 'center' }}>
        <h1 style={{ fontFamily: 'Baloo 2, sans-serif', fontSize: 28, color: '#16100D', marginBottom: 12 }}>
          {t("menu.empty.title")}
        </h1>
        <p style={{ fontFamily: 'Quicksand, sans-serif', color: '#6B6259', marginBottom: 24 }}>
          {t("menu.empty.description")}
        </p>
        <Link href="/menu" className="btn-primary" style={{ display: 'inline-flex', padding: '10px 24px' }}>
          {t("menu.empty.reset")}
        </Link>
      </main>
    );
  }

  // Each combo component retains the catalog's own option/topping groups.
  // Fetch public product detail so customers can configure only existing choices.
  const comboComponents = productIsCombo
    ? await Promise.all((product.comboItems ?? []).map(async item => ({
        item,
        product: await catalogApi.getProductById(item.productId, storeId, locale).catch(() => null),
      })))
    : undefined;

  return (
    <main>
      <ProductDetailClient key={`${productIsCombo ? "combo" : "product"}-${product.id}-${storeId ?? "all"}-${locale}`}
        product={product} storeId={storeId} itemType={productIsCombo ? "COMBO" : "PRODUCT"} comboComponents={comboComponents} />
    </main>
  );
}
