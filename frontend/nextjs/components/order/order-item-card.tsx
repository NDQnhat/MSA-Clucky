"use client";
import { useTranslations } from "next-intl";
import { useLocaleTools } from "@/lib/i18n/hooks";

import React from "react";
import { pickLocalized, type LocalizedText } from "@/lib/bilingual";
import { CartItemOption, CartItemTopping } from "@/lib/types/order";
import { FoodImage } from "@/components/order/food-image";
import styles from "./order-shared.module.css";

interface OrderItemCardProps {
  image: string;
  name: string;
  nameI18n?: LocalizedText | null;
  category?: string;
  categoryI18n?: LocalizedText | null;
  unitPrice: number;
  quantity: number;
  lineTotal: number;
  options?: CartItemOption[];
  toppings?: CartItemTopping[];
  note?: string;
  actions?: React.ReactNode;
}

export function OrderItemCard({
  image,
  name,
  nameI18n,
  category,
  categoryI18n,
  unitPrice,
  quantity,
  lineTotal,
  options = [],
  toppings = [],
  note,
  actions,
}: OrderItemCardProps) {
  const t = useTranslations();

  const { locale, money: formatPrice } = useLocaleTools();
  const displayName = pickLocalized(nameI18n, name, locale);
  const displayCategory = category ? pickLocalized(categoryI18n, category, locale) : "";
  const safeOptions = Array.isArray(options) ? options : [];
  const safeToppings = Array.isArray(toppings) ? toppings : [];
  return (
    <article className={styles.itemCard}>
      <FoodImage src={image} alt={displayName} className={styles.itemImage} />
      <div className={styles.itemInfo}>
        {displayCategory && <span className={styles.itemTag}>{displayCategory}</span>}
        <h3 className={styles.itemName}>{displayName}</h3>
        <p className={styles.itemMeta}>
          {formatPrice(unitPrice)} × {quantity} {t("business.cart.servings")} </p>

        {(safeOptions.length > 0 || safeToppings.length > 0) && (
          <div className={styles.itemAddons}>
            {safeOptions.map((opt, idx) => {
              const groupLabel = pickLocalized(opt.groupNameI18n, opt.groupName, locale);
              const optLabel = pickLocalized(opt.nameI18n, opt.name, locale);
              return (
                <span key={`opt-${idx}`} className={styles.addonChip}>
                  {groupLabel ? `${groupLabel}: ${optLabel}` : optLabel}
                  {opt.extraPrice > 0 ? ` (+${formatPrice(opt.extraPrice)})` : ""}
                </span>
              );
            })}
            {safeToppings.map((top, idx) => (
              <span key={`top-${idx}`} className={styles.addonChip}>
                +{pickLocalized(top.nameI18n, top.name, locale)} (+{formatPrice(top.extraPrice)})
              </span>
            ))}
          </div>
        )}

        {note && <p className={styles.itemNote}>{t("business.orderitemcard.note")} {note}</p>}
      </div>

      <div className={styles.itemPriceCol}>
        <strong className={styles.itemPrice}>{formatPrice(lineTotal)}</strong>
        {actions && <div className={styles.itemActions}>{actions}</div>}
      </div>
    </article>
  );
}
