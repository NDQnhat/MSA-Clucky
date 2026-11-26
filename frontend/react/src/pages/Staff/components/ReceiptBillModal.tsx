import i18n from "../../../i18n/index.ts";
import React, { useState, useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { useTranslation } from "react-i18next";
import {
  Printer,
  ChefHat,
  FileText,
  X,
  CheckCircle2,
  Wifi,
  Sparkles,
} from "lucide-react";
import { message } from "../../../components/feedback";
import { useAuth } from "../../../context/AuthContext";
import { useOrders } from "../../../context/OrderContext";
import type { StaffOrder } from "../../../mocks/mockStaffOrders";
import { paymentsApi } from "../../../lib/api";
import { fulfillmentShortLabel, getFulfillmentMode } from "../../../lib/order-fulfillment";
import { pickLocalized } from "../../../lib/bilingual";
import styles from "./receipt-bill.module.css";

interface ReceiptBillModalProps {
  open: boolean;
  order: StaffOrder | null;
  onClose: () => void;
  onPrintSuccess?: (orderId: string, newPrintCount: number) => void;
  initialMode?: "RECEIPT" | "KOT";
}

export const ReceiptBillModal: React.FC<ReceiptBillModalProps> = ({
  open,
  order,
  onClose,
  onPrintSuccess,
  initialMode = "RECEIPT",
}) => {
  const { t, i18n: i18next } = useTranslation();
  const lang = i18next.resolvedLanguage || i18next.language || "vi";
  const formatVnd = (val: number) =>
    `${new Intl.NumberFormat(lang.startsWith("en") ? "en-US" : "vi-VN").format(val)} ${t("common:currency")}`;
  const { user } = useAuth();
  const { stores, currentStore } = useOrders();
  const matchedStore = stores.find((s) => Number(s.id) === Number(order?.storeId)) || currentStore;
  const resolvedStoreName = pickLocalized(matchedStore?.nameI18n, matchedStore?.name, lang) || order?.storeName || user?.storeName || "Clucky";
  const resolvedStoreAddress = matchedStore?.address || "";
  const resolvedStoreHotline = matchedStore?.phone || "1900 6868";
  const [mode, setMode] = useState<"RECEIPT" | "KOT">(initialMode);
  const [isPaperFeeding, setIsPaperFeeding] = useState<boolean>(false);
  const [printCount, setPrintCount] = useState<number>(0);
  const scrollContainerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (open && order) {
      setMode(initialMode);
      setPrintCount(order.printCount || 0);
      setIsPaperFeeding(false);
    }
  }, [open, order, initialMode]);

  useEffect(() => {
    if (open && scrollContainerRef.current) {
      scrollContainerRef.current.scrollTop = 0;
    }
  }, [open, mode]);

  if (!open || !order) return null;

  const formattedQueue = `#${String(order.queueNumber || 0).padStart(3, "0")}`;
  const isReprint = printCount >= 1;

  // Print simulation action
  const handlePrint = async (printMode: "RECEIPT" | "KOT") => {
    setIsPaperFeeding(true);

    let updatedCount = printCount + 1;

    if (printMode === "RECEIPT") {
      try {
        let rId = order.receiptId;
        if (!rId && order.orderCode) {
          const fetched = await paymentsApi
            .getReceiptByOrderCode(order.orderCode)
            .catch(() => null);
          if (fetched?.id) {
            rId = fetched.id;
          }
        }
        if (rId) {
          const printRes = await paymentsApi.trackPrint(rId).catch(() => null);
          if (printRes?.printCount) {
            updatedCount = printRes.printCount;
          }
        }
      } catch (err) {
        console.warn("receipt print tracking failed:", err);
      }
    }

    setTimeout(() => {
      setPrintCount(updatedCount);
      onPrintSuccess?.(order.id, updatedCount);

      if (printMode === "RECEIPT") {
        message.success(
          isReprint
            ? t("business:receipt.reprintedSuccess", { count: updatedCount })
            : t("business:receipt.printedSuccess"),
        );
      } else {
        message.success(t("business:receipt.kotPrintedSuccess"));
      }

      setIsPaperFeeding(false);
    }, 400);
  };

  const nowString = new Date().toLocaleString(lang.startsWith("en") ? "en-US" : "vi-VN", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });

  const createdAtDate = order.createdAt ? new Date(order.createdAt) : null;
  const orderDateLabel =
    createdAtDate && !Number.isNaN(createdAtDate.getTime())
      ? new Intl.DateTimeFormat(lang.startsWith("en") ? "en-US" : "vi-VN", {
          timeZone: "Asia/Ho_Chi_Minh",
          day: "2-digit",
          month: "2-digit",
          year: "numeric",
        }).format(createdAtDate)
      : "";

  const modalNode = (
    <div className={styles.modalOverlay} role="dialog" aria-modal="true" aria-label={t("business:receipt.dialogAria")}>
      <div className={styles.modalWindow}>
        {/* Action Toolbar */}
        <div className={styles.actionsToolbar}>
          {mode === "RECEIPT" ? (
            <>
              <button
                type="button"
                className={`${styles.actionBtn} ${styles.printBtn}`}
                onClick={() => handlePrint("RECEIPT")}
                title={t("business:receipt.printReceiptTitle")}
              >
                <Printer size={16} />
                <span>
                  {t("business:receipt.printReceiptBtn")}
                  {isReprint ? t("business:receipt.printAttemptSuffix", { count: printCount + 1 }) : ""}
                </span>
              </button>

              <button
                type="button"
                className={`${styles.actionBtn} ${styles.kotBtn}`}
                onClick={() => setMode("KOT")}
                title={t("business:receipt.switchToKotTitle")}
              >
                <ChefHat size={16} />
                <span>{t("business:receipt.viewKotBtn")}</span>
              </button>
            </>
          ) : (
            <>
              <button
                type="button"
                className={`${styles.actionBtn} ${styles.kotBtn}`}
                onClick={() => handlePrint("KOT")}
                title={t("business:receipt.printKotTitle")}
              >
                <Printer size={16} />
                <span>{t("business:receipt.printKotBtn")}</span>
              </button>

              <button
                type="button"
                className={`${styles.actionBtn} ${styles.modeToggleBtn}`}
                onClick={() => setMode("RECEIPT")}
                title={t("business:receipt.backToReceiptTitle")}
              >
                <FileText size={16} />
                <span>{t("business:receipt.viewReceiptBtn")}</span>
              </button>
            </>
          )}

          <button
            type="button"
            className={`${styles.actionBtn} ${styles.closeBtn}`}
            onClick={onClose}
            aria-label={t("common:close")}
          >
            <X size={16} />
            <span>{t("common:close")}</span>
          </button>
        </div>

        {/* Paper Scroll Area */}
        <div className={styles.paperScrollContainer} ref={scrollContainerRef}>
          <article
            className={`${styles.receiptPaper} ${
              isPaperFeeding ? styles.paperFeeding : ""
            }`}
            tabIndex={0}
            aria-label={t("business:receipt.paperAria")}
          >
            {/* Mode 1: Receipt */}
            {mode === "RECEIPT" ? (
              <>
                {isReprint && (
                  <div className={styles.reprintWatermark}>
                    {t("business:receipt.reprintWatermark", { count: printCount })}
                  </div>
                )}

                {/* Header Clucky */}
                <div className={styles.receiptHeader}>
                  <img
                    src="/logo_primary.png"
                    alt="Clucky Logo"
                    className={styles.logoImage}
                    onError={(e) => {
                      (e.target as HTMLImageElement).style.display = "none";
                    }}
                  />
                  <h1 className={styles.brandName}>CLUCKY</h1>
                  <div className={styles.brandSlogan}>
                    {t("business:receipt.slogan")}
                  </div>
                  <div className={styles.storeInfo}>
                    {t("business:receipt.branchLabel")} <strong>{resolvedStoreName}</strong>
                    {resolvedStoreAddress && (
                      <>
                        <br />
                        {resolvedStoreAddress}
                      </>
                    )}
                    <br />
                    Hotline: {resolvedStoreHotline} · Website: clucky.vn
                  </div>
                </div>

                <div className={styles.doubleDivider} />

                <div className={styles.queueCallout}>
                  <div className={styles.queueCalloutLeft}>
                    <span className={styles.queueTitle}>{t("business:receipt.queueCalloutTitle")} {orderDateLabel ? `(${orderDateLabel})` : ""}</span>
                    <span className={styles.queueNumberLarge}>
                      {formattedQueue}
                    </span>
                  </div>
                  <span className={styles.diningOptionTag}>
                    [ {fulfillmentShortLabel(order)} ]
                  </span>
                </div>

                <div className={styles.dashedDivider} />

                <div className={styles.metaRow}>
                  <span>{t("business:receipt.orderCodeLabel")}</span>
                  <strong>{order.orderCode}</strong>
                </div>
                <div className={styles.metaRow}>
                  <span>{t("business:receipt.placedAtLabel")}</span>
                  <span>{order.createdAt ? new Date(order.createdAt).toLocaleString(lang.startsWith("en") ? "en-US" : "vi-VN") : nowString}</span>
                </div>
                <div className={styles.metaRow}>
                  <span>{t("business:receipt.cashierLabel")}</span>
                  <span>{order.cashierName || (user?.fullName ? `${user.fullName} (${user.role === "STAFF" ? "Staff" : user.role})` : t("business:staffactions.staff"))}</span>
                </div>
                {order.customerName && (
                  <div className={styles.metaRow}>
                    <span>{t("business:receipt.customerLabel")}</span>
                    <span>{order.customerName}</span>
                  </div>
                )}
                {getFulfillmentMode(order) === 'DELIVERY' && (
                  <div className={styles.metaRow}>
                    <span>{i18n.t("shipping:ui.deliveryAddress")}</span>
                    <span>{order.deliveryAddress || i18n.t("shipping:ui.deliveryDetailsMissing")}</span>
                  </div>
                )}

                <div className={styles.dashedDivider} />

                <table className={styles.itemsTable}>
                  <thead>
                    <tr>
                      <th className={styles.itemColName}>{t("business:receipt.colItemName")}</th>
                      <th className={styles.itemColQty}>{t("business:receipt.colQty")}</th>
                      <th className={styles.itemColPrice}>{t("business:receipt.colUnitPrice")}</th>
                      <th className={styles.itemColTotal}>{t("business:receipt.colLineTotal")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {order.items.map((item, index) => (
                      <React.Fragment key={item.id || index}>
                        <tr>
                          <td className={styles.itemColName}>
                            <strong>{item.itemName}</strong>
                            {item.options && (
                              <div className={styles.itemOptionsText}>
                                {item.options}
                              </div>
                            )}
                          </td>
                          <td className={styles.itemColQty}>
                            {String(item.quantity).padStart(2, "0")}
                          </td>
                          <td className={styles.itemColPrice}>
                            {formatVnd(item.unitPrice)}
                          </td>
                          <td className={styles.itemColTotal}>
                            {formatVnd(item.lineTotal || item.unitPrice * item.quantity)}
                          </td>
                        </tr>
                      </React.Fragment>
                    ))}
                  </tbody>
                </table>

                <div className={styles.dashedDivider} />

                <div className={styles.totalSection}>
                  <div className={styles.totalRow}>
                    <span>{t("business:receipt.subtotalLabel")}</span>
                    <span>{formatVnd(order.subtotal || order.total)}</span>
                  </div>
                  {order.discount > 0 && (
                    <div className={styles.totalRow}>
                      <span>{t("business:receipt.discountLabel")}</span>
                      <span>-{formatVnd(order.discount)}</span>
                    </div>
                  )}
                  <div className={styles.grandTotalRow}>
                    <span>{t("business:receipt.grandTotalLabel")}</span>
                    <span className={styles.grandTotalAmount}>
                      {formatVnd(order.total)}
                    </span>
                  </div>
                </div>

                <div className={styles.dashedDivider} />

                <div className={styles.paymentSection}>
                  <div className={styles.metaRow}>
                    <span>{t("business:receipt.methodLabel")}</span>
                    <strong>
                      {order.paymentMethod === "CASH"
                        ? t("business:receipt.methodCash")
                        : order.paymentMethod === "BANK_TRANSFER"
                        ? t("business:receipt.methodBank")
                        : t("business:receipt.methodSplit")}
                    </strong>
                  </div>

                  {order.cashTendered !== undefined && order.cashTendered > 0 && (
                    <>
                      <div className={styles.metaRow}>
                        <span>{t("business:receipt.cashTenderedLabel")}</span>
                        <span>{formatVnd(order.cashTendered)}</span>
                      </div>
                      <div className={styles.metaRow}>
                        <span>{t("business:receipt.cashChangeLabel")}</span>
                        <strong style={{ color: "#1e7a46" }}>
                          {formatVnd(order.cashChange || 0)}
                        </strong>
                      </div>
                    </>
                  )}

                  {order.bankRefCode && (
                    <div className={styles.metaRow}>
                      <span>{t("business:receipt.bankRefLabel")}</span>
                      <span>{order.bankRefCode}</span>
                    </div>
                  )}

                  {order.manualAcceptanceReason && (
                    <div className={styles.metaRow}>
                      <span>{t("business:receipt.cashierNoteLabel")}</span>
                      <span>{order.manualAcceptanceReason}</span>
                    </div>
                  )}
                </div>

                <div className={styles.doubleDivider} />

                <div className={styles.receiptFooter}>
                  <div className={styles.thankYouText}>
                    {t("business:receipt.thankYou")}
                  </div>
                  <div className={styles.wifiText}>
                    <Wifi size={12} style={{ verticalAlign: "middle", marginRight: 4 }} />
                    Wifi: <strong>Clucky_Guest</strong> ({t("business:receipt.wifiPasswordLabel")} <strong>12345678</strong>)
                  </div>

                  {isReprint && (
                    <div className={styles.reprintFooterNotice}>
                      {t("business:receipt.reprintNoticeTime", { time: nowString })}
                      <br />
                      {t("business:receipt.reprintNoticeInternal")}
                    </div>
                  )}
                </div>
              </>
            ) : (
              /* Mode 2: Kitchen Order Ticket (KOT) */
              <>
                <div className={styles.kotHeader}>
                  <h2 className={styles.kotTitle}>{t("business:receipt.kotTitle")}</h2>
                  <div style={{ fontSize: 11, color: "#444444", marginTop: 4 }}>
                    {t("business:receipt.kotBranchLabel")} <strong>{resolvedStoreName}</strong>
                    <br />
                    {t("business:receipt.kotReceivedLabel")} {order.time || t("business:receipt.nowLabel")} · {order.createdAt ? new Date(order.createdAt).toLocaleTimeString(lang.startsWith("en") ? "en-US" : "vi-VN") : ""}
                  </div>
                </div>

                <div className={styles.queueCallout} style={{ backgroundColor: "#000000", color: "#ffffff" }}>
                  <div className={styles.queueCalloutLeft}>
                    <span style={{ fontSize: 10, color: "#aaaaaa" }}>{t("business:receipt.kotQueueTitle")} {orderDateLabel ? `(${orderDateLabel})` : ""}</span>
                    <span style={{ fontFamily: "Fredoka, sans-serif", fontSize: 28, fontWeight: 800, color: "#ffffff" }}>
                      {formattedQueue}
                    </span>
                  </div>
                  <span style={{ fontSize: 13, fontWeight: 800, padding: "4px 8px", backgroundColor: "#ffffff", color: "#000000", borderRadius: 4 }}>
                    {fulfillmentShortLabel(order)}
                  </span>
                </div>

                <div className={styles.metaRow} style={{ marginTop: 8 }}>
                  <span>{t("business:receipt.kotOrderCodeLabel")} <strong>{order.orderCode}</strong></span>
                  <span>{t("business:receipt.kotSourceLabel")} <strong>{order.orderSource === "COUNTER" ? t("business:receipt.kotSourceCounter") : t("business:receipt.kotSourceWeb")}</strong></span>
                </div>

                <div className={styles.doubleDivider} />

                <table className={styles.itemsTable}>
                  <thead>
                    <tr>
                      <th style={{ width: "18%", textAlign: "center", fontSize: 13 }}>{t("business:receipt.colQty")}</th>
                      <th style={{ width: "82%", fontSize: 13 }}>{t("business:receipt.kotItemsHeader")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {order.items.map((item, index) => (
                      <tr key={item.id || index} style={{ borderBottom: "1px dashed #dddddd" }}>
                        <td style={{ textAlign: "center", fontSize: 16, fontWeight: 800, verticalAlign: "top" }}>
                          {item.quantity}x
                        </td>
                        <td style={{ padding: "6px 0" }}>
                          <strong style={{ fontSize: 14 }}>{item.itemName}</strong>
                          {item.options && (
                            <div style={{ fontSize: 12, color: "#222222", fontWeight: 700, marginTop: 2, paddingLeft: 6, borderLeft: "2px solid #000000" }}>
                              &gt;&gt;&gt; {item.options}
                            </div>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>

                {order.note && (
                  <div className={styles.kotNoteBox}>
                    ⚠️ {t("business:receipt.kotNotePrefix")} {order.note}
                  </div>
                )}

                <div className={styles.doubleDivider} />
                <div style={{ textAlign: "center", fontSize: 11, fontStyle: "italic", color: "#555555" }}>
                  {t("business:receipt.kotFooterHint")}
                </div>
              </>
            )}

            {/* Viền răng cưa giấy in nhiệt dưới đáy */}
            <div className={styles.zigzagBottom} />
          </article>
        </div>
      </div>
    </div>
  );

  return typeof document !== "undefined" ? createPortal(modalNode, document.body) : modalNode;
};
