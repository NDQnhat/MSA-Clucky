"use client";
import { useLocale, useTranslations } from "next-intl";
import { useLocaleTools } from "@/lib/i18n/hooks";
import { pickLocalized } from "@/lib/bilingual";

import { FoodImage } from "@/components/order/food-image";
import Link from "@/components/ui-loader/navigation-link";
import type { Product } from "@/lib/products";
import { Plus, Flame, Star } from "lucide-react";

export function ProductCard({ product, storeId }: { product: Product; storeId?: number }) {
  const t = useTranslations();
  const locale = useLocale();
  const { money: formatPrice } = useLocaleTools();
  const productName = pickLocalized(product.nameI18n, product.name, locale);
  const productDescription = pickLocalized(product.descriptionI18n, product.description, locale);
  const categoryName = pickLocalized(product.categoryNameI18n, product.categoryName || product.category || "", locale);
  const isSoldOut = product.isSoldOut === true || product.isAvailable === false;
  const productHref = `/menu/${product.slug}${storeId ? `?storeId=${storeId}` : ''}`;
  const hasRating = typeof product.rating === "number" && product.rating > 0 && (product.reviewCount ?? 0) > 0;
  const comboPartsSummary = product.comboItems && product.comboItems.length > 0
    ? product.comboItems
        .slice(0, 2)
        .map((part) => `${part.quantity}× ${pickLocalized(part.productNameI18n, part.productName, locale)}`)
        .join(" · ") + (product.comboItems.length > 2 ? ` +${product.comboItems.length - 2}` : "")
    : null;

  return (
    <article className="product-card">
      <Link
        href={productHref}
        className="product-card-media"
        aria-label={t("menu.product.view", { name: productName })}
      >
        <FoodImage
          src={product.imageUrl || product.image}
          alt={product.alt || productName}
          loading="lazy"
        />

        {product.isBestSeller && !isSoldOut && (
          <span className="badge-best-seller">
            <Flame size={12} aria-hidden="true" />
            {t("home.bestSellerBadge")}
          </span>
        )}
        {isSoldOut && <span className="badge-sold-out">{t("menu.product.soldOut")}</span>}
      </Link>

      <div className="product-card-body">
        <div
          style={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            gap: 8,
          }}
        >
          <span className="card-tag">
            {categoryName}
          </span>
          <div style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
            {hasRating && (
              <span className="product-rating-chip">
                <Star size={12} fill="#C9A227" color="#C9A227" aria-hidden="true" />
                {product.rating!.toFixed(1)}
                <span>({product.reviewCount})</span>
              </span>
            )}
            {(product.spicyLevel ?? 0) > 0 && (
              <span
                style={{
                  display: "inline-flex",
                  alignItems: "center",
                  gap: 2,
                  fontSize: 11,
                  fontWeight: 700,
                  color: (product.spicyLevel ?? 0) >= 2 ? "#B42318" : "#D97706",
                }}
              >
                <Flame size={13} />
                {t((product.spicyLevel ?? 0) >= 2 ? "menu.spice.hot" : "menu.spice.medium")}
              </span>
            )}
          </div>
        </div>

        <h2>
          <Link href={productHref}>{productName}</Link>
        </h2>

        {comboPartsSummary ? (
          <p className="combo-parts-line">{comboPartsSummary}</p>
        ) : (
          <p>{productDescription}</p>
        )}

        <div className="product-meta">
          <span className="price">
            {formatPrice(product.basePrice || product.price)}
          </span>
          <Link
            className="add-button"
            href={productHref}
            aria-label={t("menu.product.customise", { name: productName })}
          >
            <Plus size={20} />
          </Link>
        </div>
      </div>
    </article>
  );
}

export default ProductCard;
