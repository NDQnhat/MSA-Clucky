"use client";

import { useState } from "react";
import { Check, ChevronDown } from "lucide-react";
import { useTranslations } from "next-intl";
import type { Product } from "@/lib/products";
import type { ComboItem } from "@/lib/catalog-and-stores.api";
import { FoodImage } from "@/components/order/food-image";
import styles from "./combo-customizer.module.css";

export type ComboComponent = {
  item: ComboItem["items"][number];
  product: Product | null;
};

export function comboChoiceKey(comboItemId: number, groupId: string): string {
  return `${comboItemId}:${groupId}`;
}

export function configuredComboOption(component: ComboComponent, group: NonNullable<Product["options"]>[number]) {
  return group.choices.find(choice => Number(choice.id.replace(/^opt-/, "")) === component.item.optionId);
}

export function ComboCustomizer({
  components,
  selectedOptions,
  selectedToppings,
  onSelectOption,
  onToggleTopping,
  formatPrice,
}: {
  components: ComboComponent[];
  selectedOptions: Record<string, string>;
  selectedToppings: Record<string, string[]>;
  onSelectOption: (key: string, value: string) => void;
  onToggleTopping: (itemId: number, id: string) => void;
  formatPrice: (amount: number) => string;
}) {
  const t = useTranslations("business.productdetailclient");
  // All items start expanded, including after a new combo is loaded.
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});

  return (
    <section className={styles.combo} aria-labelledby="combo-contents-heading">
      <div className={styles.sectionHeading}>
        <h2 id="combo-contents-heading">{t("comboItems")}</h2>
        <span>{t("comboItemCount", { count: components.length })}</span>
      </div>
      <div className={styles.items}>
        {components.map((component, index) => {
          const { item, product } = component;
          const itemKey = String(item.id ?? `component-${index}`);
          const isOpen = expanded[itemKey] !== false;
          const panelId = `combo-options-${itemKey}`;
          const options = item.id == null ? [] : product?.options ?? [];
          const toppings = item.id == null ? [] : product?.toppings ?? [];
          const hasChoices = options.length > 0 || toppings.length > 0;
          const itemHeading = (
            <>
              <FoodImage
                src={product?.imageUrl || product?.image}
                alt=""
                className={styles.itemImage}
              />
              <span className={styles.itemTitle}>
                <strong>{item.productName}</strong>
                {!hasChoices && <small>{t("comboIncluded")}</small>}
              </span>
              <span className={styles.quantityBadge}>×{item.quantity}</span>
              {hasChoices && <ChevronDown className={`${styles.chevron} ${isOpen ? styles.chevronOpen : ""}`} size={19} aria-hidden="true" />}
            </>
          );

          return (
            <article className={styles.item} key={itemKey}>
              {hasChoices ? (
                <button
                  type="button"
                  className={styles.itemToggle}
                  onClick={() => setExpanded(previous => ({ ...previous, [itemKey]: !isOpen }))}
                  aria-expanded={isOpen}
                  aria-controls={panelId}
                >
                  {itemHeading}
                </button>
              ) : (
                <div className={styles.itemToggle}>{itemHeading}</div>
              )}

              {hasChoices && (
                <div className={styles.itemPanel} id={panelId} hidden={!isOpen}>
                  <div className={styles.optionsBody}>
                      <h3>{t("comboOptions")}</h3>
                      {options.map(group => {
                        const fixed = configuredComboOption(component, group);
                        const choiceKey = comboChoiceKey(item.id!, group.id);
                        return (
                          <fieldset className={styles.group} key={group.id}>
                            <legend>
                              {group.name}
                              <small>{fixed ? t("comboFixedOption") : group.required ? t("comboChooseOne") : t("comboOptional")}</small>
                            </legend>
                            <div className={styles.choices}>
                              {!fixed && !group.required && (
                                <label className={`${styles.choice} ${!selectedOptions[choiceKey] ? styles.selected : ""}`}>
                                  <input type="radio" name={choiceKey} value="" checked={!selectedOptions[choiceKey]}
                                    onChange={() => onSelectOption(choiceKey, "")} />
                                  <span className={styles.radioMark}><Check size={12} strokeWidth={3} aria-hidden="true" /></span>
                                  <span className={styles.choiceName}>{t("comboNoExtraOption")}</span>
                                </label>
                              )}
                              {(fixed ? [fixed] : group.choices).map(choice => {
                                const checked = Boolean(fixed) || selectedOptions[choiceKey] === choice.id;
                                return (
                                  <label className={`${styles.choice} ${checked ? styles.selected : ""} ${fixed ? styles.fixed : ""}`} key={choice.id}>
                                    <input type="radio" name={choiceKey} value={choice.id} checked={checked}
                                      disabled={Boolean(fixed)} onChange={() => onSelectOption(choiceKey, choice.id)} />
                                    <span className={styles.radioMark}><Check size={12} strokeWidth={3} aria-hidden="true" /></span>
                                    <span className={styles.choiceName}>{choice.name}</span>
                                    {fixed ? <span className={styles.priceLabel}>{t("comboIncluded")}</span>
                                      : choice.priceAdjustment > 0 && <span className={styles.priceLabel}>+{formatPrice(choice.priceAdjustment * item.quantity)}</span>}
                                  </label>
                                );
                              })}
                            </div>
                          </fieldset>
                        );
                      })}
                      {toppings.length > 0 && (
                        <fieldset className={styles.group}>
                          <legend>{t("comboExtraToppings")} <small>{t("comboOptional")}</small></legend>
                          <div className={styles.choices}>
                            {toppings.map(topping => (
                              <label className={`${styles.choice} ${(selectedToppings[item.id!] ?? []).includes(topping.id) ? styles.selected : ""}`} key={topping.id}>
                                <input type="checkbox" checked={(selectedToppings[item.id!] ?? []).includes(topping.id)}
                                  onChange={() => onToggleTopping(item.id!, topping.id)} />
                                <span className={styles.checkboxMark}><Check size={12} strokeWidth={3} aria-hidden="true" /></span>
                                <span className={styles.choiceName}>{topping.name}</span>
                                {topping.price > 0 && <span className={styles.priceLabel}>+{formatPrice(topping.price * item.quantity)}</span>}
                              </label>
                            ))}
                          </div>
                        </fieldset>
                      )}
                  </div>
                </div>
              )}
            </article>
          );
        })}
      </div>
    </section>
  );
}
