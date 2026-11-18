"use client";
import { useState } from "react";
import { useTranslations } from "next-intl";
import { useLocaleTools } from "@/lib/i18n/hooks";


import Link from "@/components/ui-loader/navigation-link";
import { useOrder } from "@/components/order/order-provider";
import { useNavigationTask } from "@/components/ui-loader";
import { OrderProgress } from "@/components/order/order-progress";
import {
  ShoppingBag,
  ArrowRight,
  ChefHat,
  Plus,
  Minus,
  Trash2,
  CheckCircle2,
  StickyNote,
  ReceiptText,
} from "lucide-react";
import { FoodImage } from "@/components/order/food-image";
import { Modal } from "@/components/ui/modal";
import styles from "./cart.module.css";

export default function CartPage() {
  const t = useTranslations();
  const { money: formatPrice } = useLocaleTools();
  const {
    items,
    count,
    subtotal,
    updateQuantity,
    removeItem,
    updateNote,
    ready,
    cartPending,
  } = useOrder();
  const [itemPendingRemoval, setItemPendingRemoval] = useState<string | null>(null);
  const selectedItem = items.find((item) => item.id === itemPendingRemoval);
  useNavigationTask(!ready);

  return (
    <>
    <main className={`${styles.cartContainer} m3-page`}>
        <OrderProgress stage="cart" />
        <div className={styles.cartHeading}>
          <span className={styles.eyebrow}>{t("business.cart.aDeliciousMealStartsHere")}</span>
          <h1 className={styles.title}> {t("business.cart.yourCart")} <span className={styles.countPill}>{count} {t("business.cart.servings")}</span>
          </h1>
          <p className={styles.subtitle}>
            {items.length > 0
              ? t("business.cart.youHaveDeliciousItemsInYourCart", { value1: items.reduce((s, i) => s + i.quantity, 0) })
              : t("business.cart.yourCartIsEmpty")}
          </p>
        </div>

        {!ready ? (
          <div className="order-skeleton" aria-hidden="true" />
        ) : items.length === 0 ? (
          <div className={styles.emptyCart}>
            <div className={styles.emptyEmoji} aria-hidden="true">
              <ShoppingBag size={52} />
            </div>
            <h2 className={styles.emptyTitle}>{t("business.cart.noItemsInYourCartYet")}</h2>
            <p className={styles.emptyDesc}> {t("business.cart.chooseGoldenCrispyChickenAndSpecialValueCombosFromClucky")} </p>
            <Link href="/menu" className={styles.browseBtn}> {t("business.cart.exploreTheMenuNow")} </Link>
          </div>
        ) : (
          <div className={styles.cartGrid}>
            <section
              className={styles.itemsList}
              aria-label={t("business.cart.cartItems")}
            >
              <div className={styles.listHeading}>
                <span>
                  <ChefHat size={18} /> {t("business.cart.yourChosenDishes")} </span>
                <Link href="/menu">
                  <Plus size={16} /> {t("business.cart.addItems")} </Link>
              </div>
              {items.map((item) => (
                <article className={styles.cartCard} key={item.id}>
                  <div className={styles.imageFrame}>
                    <FoodImage
                      src={item.image}
                      alt={item.name}
                      className={styles.itemImage}
                    />
                  </div>

                  <div className={styles.itemDetails}>
                    <div className={styles.itemTopline}>
                      <span className={styles.itemCategory}>{item.comboId != null ? t("business.cart.comboItem") : item.category}</span>
                      <span className={styles.itemUnitPrice}>
                        {formatPrice(item.unitPrice)} {t("business.cart.serving")}
                      </span>
                    </div>
                    <h2 className={styles.itemName}>{item.name}</h2>

                    {Boolean(item.options?.length || item.toppings?.length) && (
                      <div className={styles.selectionsPanel}>
                        <div className={styles.selectionsHeading}>
                          <CheckCircle2 size={17} aria-hidden="true" />
                          <span>{t("business.cart.selectedOptions")}</span>
                        </div>
                        <div className={styles.selectionsList}>
                          {item.options?.map((opt, i) => (
                            <div key={`opt-${i}`} className={styles.selectionRow}>
                              <span className={styles.selectionName}>{opt.groupName ? `${opt.groupName}: ${opt.name}` : opt.name}</span>
                              {opt.extraPrice > 0 && <span className={styles.selectionPrice}>+{formatPrice(opt.extraPrice)}</span>}
                            </div>
                          ))}
                          {item.toppings?.map((top, i) => (
                            <div key={`top-${i}`} className={styles.selectionRow}>
                              <span className={styles.selectionName}>{t("business.cart.toppingLabel")}: {top.name}</span>
                              {top.extraPrice > 0 && <span className={styles.selectionPrice}>+{formatPrice(top.extraPrice)}</span>}
                            </div>
                          ))}
                        </div>
                      </div>
                    )}

                    <div className={styles.noteInputWrapper}>
                      <label className={styles.noteLabel} htmlFor={`cart-note-${item.id}`}>
                        <StickyNote size={14} aria-hidden="true" /> {t("business.cart.kitchenNoteLabel")}
                      </label>
                      <input
                        id={`cart-note-${item.id}`}
                        type="text"
                        placeholder={t("business.cart.notesForTheRestaurantEGLessIceMediumSpice")}
                        key={`${item.id}-${item.note ?? ""}`}
                        defaultValue={item.note || ""}
                        onBlur={(event) => {
                          if (event.target.value !== (item.note ?? "")) updateNote(item.id, event.target.value);
                        }}
                        disabled={cartPending}
                        className={styles.noteInput}
                        aria-label={t("business.cart.notesFor", { value1: item.name })}
                      />
                    </div>
                  </div>

                  <div className={styles.itemRight}>
                    <div className={styles.lineTotalGroup}>
                      <span>{t("business.cart.lineTotal")}</span>
                      <strong className={styles.lineTotal}>
                        {formatPrice(item.unitPrice * item.quantity)}
                      </strong>
                    </div>

                    <div className={styles.itemActions}>
                      <div
                        className={styles.stepper}
                        role="group"
                        aria-label={t("business.cart.changeQuantity")}
                      >
                        <button
                          type="button"
                          onClick={() => item.quantity <= 1 ? setItemPendingRemoval(item.id) : updateQuantity(item.id, -1)}
                          disabled={cartPending}
                          className={styles.stepperBtn}
                          aria-label={item.quantity <= 1
                            ? t("business.cart.removeNamedItem", { itemName: item.name })
                            : t("business.cart.decrease", { value1: item.name })}
                        >
                          <Minus size={16} />
                        </button>
                        <span className={styles.stepperVal}>{item.quantity}</span>
                        <button
                          type="button"
                          onClick={() => updateQuantity(item.id, 1)}
                          disabled={cartPending}
                          className={styles.stepperBtn}
                          aria-label={t("business.cart.increase", { value1: item.name })}
                        >
                          <Plus size={16} />
                        </button>
                      </div>

                      <button
                        type="button"
                        onClick={() => setItemPendingRemoval(item.id)}
                        disabled={cartPending}
                        className={styles.removeBtn}
                        aria-label={t("business.cart.removeNamedItem", { itemName: item.name })}
                        title={t("business.cart.removeNamedItem", { itemName: item.name })}
                      >
                        <Trash2 size={21} strokeWidth={2.8} aria-hidden="true" />
                      </button>
                    </div>
                  </div>
                </article>
              ))}
            </section>

            <aside className={styles.summaryBox}>
              <div className={styles.receiptLabel}><ReceiptText size={16} aria-hidden="true" /> {t("business.cart.yourMeal")}</div>
              <h2 className={styles.summaryTitle}>{t("business.cart.orderSummary")}</h2>

              <div className={styles.summaryRow}>
                <span>{t("business.cart.subtotal")}</span>
                <strong>{formatPrice(subtotal)}</strong>
              </div>

              <div className={styles.summaryRow}>
                <span>{t("business.cart.itemQuantity")}</span>
                <strong>{count} {t("business.cart.servings")}</strong>
              </div>

              <hr className={styles.divider} />

              <div className={styles.summaryTotal}>
                <span>{t("business.cart.subtotalAmount")}</span>
                <strong className={styles.totalPrice}>
                  {formatPrice(subtotal)}
                </strong>
              </div>

              <Link href="/checkout" className={styles.checkoutBtn}> {t("business.cart.checkout")} <ArrowRight size={18} />
              </Link>

              <p className={styles.summaryHint}> {t("business.cart.deliveryFeesAndDiscountsWillBeCalculatedAtCheckout")} </p>
              <div className={styles.kitchenNote}>
                <ChefHat size={24} />
                <div>
                  <strong>{t("business.cart.fromOurKitchenWithCare")}</strong>
                  <p>{t("business.cart.yourMealIsPreparedAfterYourOrderIsConfirmed")}</p>
                </div>
              </div>
              <Link href="/menu" className={styles.continueShopping}> {t("business.cart.continueShopping")} </Link>
            </aside>
          </div>
        )}
    </main>
    <Modal
      open={Boolean(selectedItem)}
      onOpenChange={(open) => {
        if (!open && !cartPending) setItemPendingRemoval(null);
      }}
      dismissible={!cartPending}
      title={t("business.cart.confirmRemoveTitle")}
      description={t("business.cart.confirmRemoveDescription", { itemName: selectedItem?.name ?? "" })}
    >
      <div className={styles.removeModalActions}>
        <button
          type="button"
          className={styles.cancelRemoveBtn}
          disabled={cartPending}
          onClick={() => setItemPendingRemoval(null)}
        >
          {t("business.cart.keepItem")}
        </button>
        <button
          type="button"
          className={styles.confirmRemoveBtn}
          disabled={cartPending || !selectedItem}
          onClick={() => {
            if (!selectedItem || cartPending) return;
            removeItem(selectedItem.id);
            setItemPendingRemoval(null);
          }}
        >
          <Trash2 size={18} strokeWidth={2.6} aria-hidden="true" />
          {t("business.cart.removeItem")}
        </button>
      </div>
    </Modal>
    </>
  );
}
