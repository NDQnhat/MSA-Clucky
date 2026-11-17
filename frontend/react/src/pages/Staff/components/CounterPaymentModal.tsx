import i18n from "../../../i18n/index.ts";
import React, { useState, useEffect, useMemo, useRef } from "react";
import { useTranslation } from "react-i18next";
import {
  X,
  Wallet,
  QrCode,
  Layers,
  CheckCircle2,
  AlertTriangle,
  Copy,
  Check,
  ArrowRight,
} from "lucide-react";
import { message } from "../../../components/feedback";
import type { PaymentMethod, StaffOrder } from "../../../mocks/mockStaffOrders";
import styles from "./counter-payment.module.css";
import { getErrorMessage, ordersApi, paymentsApi, type BackendOrder, type CounterSepaySession } from "../../../lib/api";
import { buildVietQrUrl } from "../../../lib/vietqr";
import { CodEvidencePanel } from "../../Orders/components/CodEvidencePanel";

interface CounterPaymentModalProps {
  open: boolean;
  order: StaffOrder | null;
  onClose: () => void;
  onConfirmPayment: (data: {
    orderId: string;
    method: PaymentMethod;
    cashTendered: number;
    cashChange: number;
    bankDeclaredAmount: number;
    manualReason?: string;
    refCode?: string;
  }) => Promise<boolean>;
  onTransferConfirmed?: (confirmedOrder: BackendOrder) => void | Promise<void>;
  onChangeMethod?: (method: 'CASH' | 'BANK_TRANSFER' | 'SPLIT') => Promise<void>;
}

const formatVnd = (val: number) => {
  return new Intl.NumberFormat("vi-VN").format(val) + " đ";
};


export const CounterPaymentModal: React.FC<CounterPaymentModalProps> = ({
  open,
  order,
  onClose,
  onConfirmPayment,
  onTransferConfirmed,
  onChangeMethod,
}) => {
  const { t } = useTranslation();
  const [activeTab, setActiveTab] = useState<PaymentMethod>("CASH");

  // Tab 1: CASH state
  const [cashTendered, setCashTendered] = useState<number>(0);

  // SePay transfer state. Never accept a cashier's verbal/manual confirmation.
  const [copiedText, setCopiedText] = useState<string>("");

  // Tab 3: SPLIT state
  const [splitCash, setSplitCash] = useState<number>(0);
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);
  const [isChangingMethod, setIsChangingMethod] = useState(false);
  const [sepaySession, setSepaySession] = useState<CounterSepaySession | null>(null);
  const [bankError, setBankError] = useState("");
  const [bankLoading, setBankLoading] = useState(false);
  const [bankCreating, setBankCreating] = useState(false);
  const [qrFailed, setQrFailed] = useState(false);
  const transferAttemptRef = useRef<{ orderId: string; cashAmount: number; key: string } | null>(null);
  const notifiedTransferRef = useRef<string | null>(null);
  const sessionResolvedRef = useRef(false);
  const orderId = order?.id;
  const orderTotal = order?.total;
  const orderPaymentMethod = order?.paymentMethod;
  const isWebCod = order?.orderSource === "WEB" && order.paymentMethod === "COD";
  const isWebBank = order?.orderSource === "WEB" && order.paymentMethod === "BANK_TRANSFER";

  useEffect(() => {
    setSepaySession(null); setBankError(""); setQrFailed(false);
    transferAttemptRef.current = null;
    notifiedTransferRef.current = null;
    sessionResolvedRef.current = false;
    if (!open || !orderId || !Number.isSafeInteger(Number(orderId)) || Number(order?.total) <= 0) return;
    const controller = new AbortController();
    setBankLoading(true);
    let pending = false;
    const refresh = async () => {
      if (pending) return;
      pending = true;
      try {
        const result = await paymentsApi.readSepay(Number(orderId), controller.signal);
        if (controller.signal.aborted) return;
        if (result.orderId !== Number(orderId) || result.orderCode !== order?.orderCode) {
          setBankError(t('business:counterPayment.sepaySessionMismatch'));
          setSepaySession(null);
          return;
        }
        setSepaySession(result);
        setBankError("");
        if (!sessionResolvedRef.current && order?.orderSource === 'COUNTER') {
          const bankPart = Number(result.amount);
          if (Number.isSafeInteger(bankPart) && bankPart > 0 && bankPart <= Number(order.total)) {
            const cashPart = Number(order.total) - bankPart;
            setSplitCash(cashPart);
            setActiveTab(cashPart > 0 ? 'SPLIT' : 'BANK_TRANSFER');
            sessionResolvedRef.current = true;
          }
        }
        if (result.status === 'PAID' && notifiedTransferRef.current !== String(orderId)) {
          // Verify the Order Service's own status before reporting success to POS.
          const confirmedOrder = await ordersApi.getById(Number(orderId), controller.signal);
          if (!controller.signal.aborted && confirmedOrder.orderCode === order?.orderCode
              && Number(confirmedOrder.storeId) === Number(order?.storeId)
              && ['PAID', 'COLLECTED'].includes(confirmedOrder.paymentStatus ?? '')) {
            notifiedTransferRef.current = String(orderId);
            await onTransferConfirmed?.(confirmedOrder);
          }
        }
      } catch (failure) {
        if (!controller.signal.aborted) {
          if ((failure as { response?: { status?: number }; status?: number }).response?.status === 404
              || (failure as { status?: number }).status === 404) {
            setSepaySession(null);
            setBankError("");
          } else setBankError(getErrorMessage(failure));
        }
      } finally { pending = false; if (!controller.signal.aborted) setBankLoading(false); }
    };
    void refresh();
    const timer = window.setInterval(() => { if (!document.hidden) void refresh(); }, 2500);
    return () => { controller.abort(); window.clearInterval(timer); };
  }, [open, orderId, order?.orderCode, order?.storeId, order?.total, order?.orderSource, onTransferConfirmed, t]);

  // Reset when order changes or modal opens
  useEffect(() => {
    if (open && orderId) {
      const total = orderTotal || 0;
      setActiveTab(orderPaymentMethod === "SPLIT" ? "SPLIT" : orderPaymentMethod === "BANK_TRANSFER" ? "BANK_TRANSFER" : "CASH");
      setCashTendered(total);
      setSplitCash(Math.floor(total / 2));
    }
  }, [open, orderId, orderTotal, orderPaymentMethod]);

  const totalDue = order?.total || 0;

  // CASH calculations
  const cashChange = Math.max(0, cashTendered - totalDue);
  const isCashSufficient = cashTendered >= totalDue;

  // Server atomically binds the cash portion and bank remainder for SPLIT.
  const splitBank = Math.max(0, totalDue - splitCash);
  const bankInfo = sepaySession?.bankInfo;
  const reference = sepaySession?.reference ?? "";
  const bankAmountDue = Number(sepaySession?.missingAmount ?? 0);
  const sessionBankAmount = Number(sepaySession?.amount ?? 0);
  const transferSessionMatches = sepaySession?.orderId === Number(order?.id)
    && sepaySession?.orderCode === order?.orderCode
    && Number.isSafeInteger(sessionBankAmount)
    && sessionBankAmount > 0
    && (activeTab === 'SPLIT' ? sessionBankAmount === splitBank : sessionBankAmount === totalDue);
  const canTransfer = transferSessionMatches && sepaySession?.status === 'PENDING'
    && sepaySession.canTransfer && Number.isSafeInteger(bankAmountDue) && bankAmountDue > 0
    && Boolean(sepaySession.expiresAt && Date.parse(sepaySession.expiresAt) > Date.now());
  const transferQr = canTransfer && !bankError ? buildVietQrUrl(bankInfo, bankAmountDue, reference) : null;

  const createSePay = async () => {
    if (!order || isWebBank || order.orderSource !== 'COUNTER'
        || bankCreating || totalDue <= 0 || !Number.isSafeInteger(totalDue)) return;
    const cashAmount = activeTab === 'SPLIT' ? splitCash : 0;
    if (!['BANK_TRANSFER', 'SPLIT'].includes(activeTab) || sepaySession
        || (activeTab === 'SPLIT' && (!Number.isSafeInteger(cashAmount) || cashAmount <= 0 || splitBank <= 0))) return;
    const previous = transferAttemptRef.current;
    if (!previous || previous.orderId !== order.id || previous.cashAmount !== cashAmount) {
      transferAttemptRef.current = { orderId: order.id, cashAmount, key: crypto.randomUUID() };
    }
    const attempt = transferAttemptRef.current;
    if (!attempt) return;
    setBankCreating(true);
    setBankError('');
    try {
      const session = await paymentsApi.initializeCounterSepay(Number(order.id), cashAmount, attempt.key);
      if (session.orderId !== Number(order.id) || session.orderCode !== order.orderCode
          || Number(session.amount) !== totalDue - cashAmount) {
        throw new Error(t('business:counterPayment.sepayAmountMismatch'));
      }
      setSepaySession(session);
      setQrFailed(false);
    } catch (error) {
      setBankError(getErrorMessage(error));
    } finally { setBankCreating(false); }
  };

  useEffect(() => { setQrFailed(false); }, [transferQr]);

  // Quick cash bill suggestions
  const quickBills = useMemo(() => {
    if (!order) return [];
    const list: { label: string; value: number }[] = [];
    list.push({ label: "Đủ tiền: " + formatVnd(totalDue), value: totalDue });

    const presets = [50000, 100000, 200000, 500000];
    presets.forEach((val) => {
      if (val >= totalDue && val !== totalDue) {
        list.push({ label: formatVnd(val), value: val });
      }
    });

    // In case totalDue is large (e.g. 520k)
    if (totalDue > 500000) {
      list.push({ label: formatVnd(Math.ceil(totalDue / 100000) * 100000), value: Math.ceil(totalDue / 100000) * 100000 });
      list.push({ label: formatVnd(1000000), value: 1000000 });
    }

    return list;
  }, [totalDue, order]);

  if (!open || !order) return null;

  const handleCopy = async (text: string, label: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedText(label);
      message.success("Đã sao chép " + label);
      setTimeout(() => setCopiedText(""), 2000);
    } catch (failure) { message.error(getErrorMessage(failure)); }
  };

  const handleConfirm = async () => {
    if (isSubmitting || isWebBank || activeTab !== 'CASH'
        || !isCashSufficient || totalDue <= 0 || sepaySession) return false;

    setIsSubmitting(true);
    try {
      return await onConfirmPayment({
        orderId: order.id,
        method: 'CASH',
        cashTendered,
        cashChange,
        bankDeclaredAmount: 0,
      });
    } finally {
      setIsSubmitting(false);
    }
  };

  const changeMethod = async (method: 'CASH' | 'BANK_TRANSFER' | 'SPLIT') => {
    if (method === activeTab || !order || !onChangeMethod || isChangingMethod || bankLoading || sepaySession
        || order.orderSource !== 'COUNTER' || order.paymentStatus !== 'PENDING') return;
    setIsChangingMethod(true);
    try {
      await onChangeMethod(method);
      setBankError('');
      setActiveTab(method);
    } catch (failure) {
      message.error(getErrorMessage(failure));
    } finally {
      setIsChangingMethod(false);
    }
  };

  const formattedQueue = `#${String(order.queueNumber).padStart(3, "0")}`;

  return (
    <div className={styles.modalOverlay} role="dialog" aria-modal="true" aria-label="Thu tiền quầy">
      <div className={styles.modalWindow}>
        {/* Header */}
        <div className={styles.modalHeader}>
          <div className={styles.headerLeft}>
            <div className={styles.queueBadgeRow}>
              <span className={styles.queueNumber}>{formattedQueue}</span>
              <span className={styles.orderCode}>{order.orderCode}</span>
              <span className={styles.diningBadge}>
                {order.orderType === 'DELIVERY' ? i18n.t("shipping:ui.delivery2") : order.diningOption === "DINE_IN" ? i18n.t("shipping:ui.dineIn") : order.diningOption === 'TAKE_AWAY' ? i18n.t("shipping:ui.takeAway2") : i18n.t("shipping:ui.storePickup2")}
              </span>
            </div>
            <span style={{ fontSize: 13, color: "#6b6259" }}>
              Khách hàng: <strong>{order.customerName || "Khách vãng lai"}</strong>
            </span>
          </div>

          <div style={{ display: "flex", alignItems: "center" }}>
            <div className={styles.headerTotalDue}>
              <div className={styles.dueLabel}>Tổng tiền cần thu</div>
              <div className={styles.dueAmount}>{formatVnd(totalDue)}</div>
            </div>
            <button
              type="button"
              className={styles.closeBtn}
              onClick={onClose}
              aria-label="Đóng"
            >
              <X size={18} />
            </button>
          </div>
        </div>

        {/* 3 Tabs Navigation */}
        <div className={styles.tabNav} role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === "CASH"}
            disabled={isWebBank || bankLoading || isChangingMethod || Boolean(sepaySession) || totalDue <= 0 || order.paymentStatus !== 'PENDING'}
            className={`${styles.tabBtn} ${
              activeTab === "CASH" ? styles.tabBtnActive : ""
            }`}
            onClick={() => void changeMethod('CASH')}
          >
            <Wallet size={16} />
            <span>Tiền mặt (CASH)</span>
          </button>

          <button
            type="button"
            role="tab"
            aria-selected={activeTab === "BANK_TRANSFER"}
            disabled={isSubmitting || isChangingMethod || totalDue <= 0 || (order.orderSource !== 'COUNTER' && !isWebBank)
              || (Boolean(sepaySession) && sessionBankAmount !== totalDue)
              || order.paymentStatus !== 'PENDING' || (bankLoading && !sepaySession)}
            className={`${styles.tabBtn} ${
              activeTab === "BANK_TRANSFER" ? styles.tabBtnActive : ""
            }`}
            onClick={() => { if (!sepaySession) void changeMethod('BANK_TRANSFER'); else setActiveTab('BANK_TRANSFER'); }}
          >
            <QrCode size={16} />
            <span>SePay (VietQR)</span>
          </button>

          <button
            type="button"
            role="tab"
            aria-selected={activeTab === "SPLIT"}
            disabled={isSubmitting || isChangingMethod || totalDue <= 0 || order.orderSource !== 'COUNTER'
              || (Boolean(sepaySession) && sessionBankAmount === totalDue)
              || order.paymentStatus !== 'PENDING' || (bankLoading && !sepaySession)}
            className={`${styles.tabBtn} ${
              activeTab === "SPLIT" ? styles.tabBtnActive : ""
            }`}
            onClick={() => { if (!sepaySession) void changeMethod('SPLIT'); else setActiveTab('SPLIT'); }}
          >
            <Layers size={16} />
            <span>Kết hợp (SPLIT)</span>
          </button>
        </div>

        {order.orderSource === 'COUNTER' && sepaySession && order.paymentStatus === 'PENDING' && (
          <p role="status" className={styles.methodLockHint}>
            Mã QR đã phát hành cho đơn này. Phương thức thanh toán được giữ cố định để tránh ghi nhận tiền hai lần.
          </p>
        )}

        {/* Modal Body */}
        <div className={styles.modalBody}>
          {/* TAB 1: TIỀN MẶT */}
          {activeTab === "CASH" && (
            <div className={styles.cashSection}>
              <div className={styles.fieldLabel}>
                <Wallet size={16} color="#8f1216" />
                <span>Số tiền khách đưa (VND):</span>
              </div>

              <div className={styles.cashInputWrapper}>
                <input
                  type="number"
                  step="1000"
                  className={styles.cashInput}
                  value={cashTendered || ""}
                  onChange={(e) => {
                    const num = parseInt(e.target.value, 10);
                    setCashTendered(isNaN(num) ? 0 : num);
                  }}
                  autoFocus
                />
                <span className={styles.currencyUnit}>đ</span>
              </div>

              {/* Nút chọn nhanh tiền chẵn */}
              <div>
                <span style={{ fontSize: 12, fontWeight: 700, color: "#6b6259", marginBottom: 6, display: "block" }}>
                  Gợi ý mệnh giá tiền nhanh:
                </span>
                <div className={styles.quickCashGrid}>
                  {quickBills.map((bill, idx) => (
                    <button
                      key={idx}
                      type="button"
                      className={`${styles.quickCashBtn} ${
                        cashTendered === bill.value ? styles.quickCashBtnExact : ""
                      }`}
                      onClick={() => setCashTendered(bill.value)}
                    >
                      {bill.label}
                    </button>
                  ))}
                </div>
              </div>

              {/* Box hiển thị tiền thối tự động */}
              {isCashSufficient ? (
                <div className={styles.changeResultBox}>
                  <div className={styles.changeLabel}>
                    <CheckCircle2 size={20} />
                    <span>Tiền thối lại khách:</span>
                  </div>
                  <div className={styles.changeAmount}>
                    {formatVnd(cashChange)}
                  </div>
                </div>
              ) : (
                <div className={styles.shortageBox}>
                  <div style={{ display: "flex", alignItems: "center", gap: 6, fontWeight: 700 }}>
                    <AlertTriangle size={18} />
                    <span>Khách đưa chưa đủ tiền:</span>
                  </div>
                  <strong style={{ fontSize: 18 }}>
                    Thiếu {formatVnd(totalDue - cashTendered)}
                  </strong>
                </div>
              )}

              {isWebCod && <CodEvidencePanel orderId={order.id} onCollect={handleConfirm} />}
            </div>
          )}

          {/* TAB 2: SePay webhook-verified bank transfer. No manual receipts. */}
          {activeTab === "BANK_TRANSFER" && (
            <div className={styles.bankSection}>
              {sepaySession?.status === 'PAID' ? (
                <p role="status" className={styles.bankUnavailable}>
                  {t('business:counterPayment.sepayPaidSyncing')}
                </p>
              ) : sepaySession && sepaySession.status !== 'PENDING' ? (
                <p role="alert" className={styles.bankUnavailable}>
                  {t('business:counterPayment.sepayInactiveStatus', { status: sepaySession.status })}
                </p>
              ) : null}
              {!sepaySession && order.orderSource === 'COUNTER' && order.paymentStatus === 'PENDING' && (
                <button type="button" className={styles.confirmButton} disabled={bankCreating || bankLoading || totalDue <= 0}
                  onClick={() => void createSePay()}>
                  {t(bankCreating ? 'business:counterPayment.sepayBusy' : 'business:counterPayment.sepayCreateQr')}
                </button>
              )}
              {!transferQr || qrFailed ? sepaySession?.status !== 'PAID' && <p role="status" className={styles.bankUnavailable}>
                {bankLoading ? t("business:counterPayment.loadingBank") : bankError || t(!sepaySession
                  ? 'business:counterPayment.sepayMissingSession' : 'business:counterPayment.sepayNotReady')}
              </p> :
              <div className={styles.qrContainer}>
                <div className={styles.qrImageFrame}>
                  <img
                    src={transferQr}
                    alt="Mã VietQR Thanh Toán"
                    className={styles.qrImg}
                    onError={() => setQrFailed(true)}
                  />
                </div>

                <div className={styles.bankDetails}>
                  <div className={styles.bankDetailRow}>
                    <span className={styles.bankDetailLabel}>Ngân hàng thụ hưởng</span>
                    <span className={styles.bankDetailValue}>{bankInfo?.bankName}</span>
                    <span>{bankInfo?.accountName}</span>
                  </div>

                  <div className={styles.bankDetailRow}>
                    <span className={styles.bankDetailLabel}>Số tài khoản quán</span>
                    <div className={styles.bankDetailValue}>
                      <span>{bankInfo?.accountNo}</span>
                      <button
                        type="button"
                        className={styles.copyBtn}
                        aria-label={t("business:financeModal.copyAccount")}
                        onClick={() => handleCopy(bankInfo?.accountNo ?? "", "STK")}
                      >
                        {copiedText === "STK" ? <Check size={14} color="#1e7a46" /> : <Copy size={14} />}
                      </button>
                    </div>
                  </div>

                  <div className={styles.bankDetailRow}>
                    <span className={styles.bankDetailLabel}>Nội dung chuyển khoản</span>
                    <div className={styles.bankDetailValue}>
                      <span style={{ color: "#8f1216" }}>{reference}</span>
                      <button
                        type="button"
                        className={styles.copyBtn}
                        aria-label={t("business:financeModal.copyTransferReference")}
                        onClick={() => handleCopy(reference, "Nội dung")}
                      >
                        {copiedText === "Nội dung" ? <Check size={14} color="#1e7a46" /> : <Copy size={14} />}
                      </button>
                    </div>
                  </div>

                  <div className={styles.bankDetailRow}>
                    <span className={styles.bankDetailLabel}>Số tiền chuyển</span>
                    <span style={{ fontSize: 16, fontWeight: 800, color: "#1e7a46" }}>
                      {formatVnd(bankAmountDue)}
                    </span>
                  </div>
                </div>
              </div>

              }
              <p role="status" className={styles.manualSection}>
                {t('business:counterPayment.sepayWebhookOnly')}
                {sepaySession?.status === 'PENDING' && Number(sepaySession.receivedAmount) > 0
                  ? ` ${t('business:counterPayment.sepayPartialReceived', {
                    received: formatVnd(Number(sepaySession.receivedAmount)), missing: formatVnd(bankAmountDue),
                  })}` : ''}
              </p>
            </div>
          )}

          {activeTab === "SPLIT" && (
            <div className={styles.splitSection}>
              <div className={styles.splitGrid}>
                <div className={styles.splitBox}>
                  <div className={styles.fieldLabel}><Wallet size={15} color="#8f1216" />
                    <span>{t('business:counterPayment.sepaySplitCashLabel')}</span></div>
                  <input type="number" step="1000" min={1} max={totalDue - 1}
                    className={styles.cashInput} value={splitCash || ''} disabled={Boolean(sepaySession) || bankCreating}
                    onChange={event => {
                      const amount = Number(event.target.value);
                      setSplitCash(Number.isSafeInteger(amount) ? Math.max(0, Math.min(totalDue, amount)) : 0);
                    }} />
                  <div style={{ fontSize: 12, color: '#6b6259' }}>
                    {t(sepaySession ? 'business:counterPayment.sepayCashLocked'
                      : 'business:counterPayment.sepayCashLockNote')}
                  </div>
                </div>
                <div className={styles.splitBox}>
                  <div className={styles.fieldLabel}><QrCode size={15} color="#22577a" />
                    <span>{t('business:counterPayment.sepaySplitBankLabel')}</span></div>
                  <div style={{ fontSize: 20, fontWeight: 800, color: '#22577a' }}>{formatVnd(splitBank)}</div>
                  <p style={{ fontSize: 12, color: '#6b6259' }}>
                    {t('business:counterPayment.sepaySplitPending')}
                  </p>
                </div>
              </div>
              {!sepaySession && order.paymentStatus === 'PENDING' && (
                <button type="button" className={styles.confirmButton}
                  disabled={bankCreating || bankLoading || !Number.isSafeInteger(splitCash)
                    || splitCash <= 0 || splitBank <= 0}
                  onClick={() => void createSePay()}>
                  {t(bankCreating ? 'business:counterPayment.sepayBusy' : 'business:counterPayment.sepayLockCreate')}
                </button>
              )}
              {sepaySession?.status === 'PAID' ? (
                <p role="status" className={styles.manualSection}>{t('business:counterPayment.sepaySplitPaidSync')}</p>
              ) : sepaySession && sepaySession.status !== 'PENDING' ? (
                <p role="alert" className={styles.bankUnavailable}>
                  {t('business:counterPayment.sepaySplitClosedStatus', { status: sepaySession.status })}
                </p>
              ) : null}
              {transferQr && !qrFailed ? (
                <div className={styles.qrContainer}>
                  <div className={styles.qrImageFrame}>
                    <img src={transferQr} alt={t('business:counterPayment.sepaySplitQrAlt')}
                      className={styles.qrImg} onError={() => setQrFailed(true)} />
                  </div>
                  <div className={styles.bankDetails}>
                    <strong>{bankInfo?.bankName}</strong>
                    <span>{bankInfo?.accountName} · {bankInfo?.accountNo}</span>
                    <span>{t('business:counterPayment.sepayBankAmountLabel')} <strong>{formatVnd(bankAmountDue)}</strong></span>
                    <span>{t('business:counterPayment.sepayExactReference')} <code>{reference}</code></span>
                    <button type="button" className={styles.copyBtn}
                      onClick={() => void handleCopy(reference, t('business:counterPayment.sepayContentCopy'))}>
                      <Copy size={14} /> {t('business:counterPayment.sepayCopyContent')}</button>
                  </div>
                </div>
              ) : <p role="status" className={styles.bankUnavailable}>
                {bankError || t(bankLoading ? 'business:counterPayment.sepayLoadingStatus'
                  : sepaySession ? 'business:counterPayment.sepayNotTransferable'
                    : 'business:counterPayment.sepaySplitMissingSession')}
              </p>}
              <p role="status" className={styles.manualSection}>
                {t('business:counterPayment.sepayNoManualSplit')}
              </p>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className={styles.modalFooter}>
          <div className={styles.footerHint}>
            {activeTab === "CASH" && (
              <span>Thu tiền mặt trực tiếp và trả lại tiền thừa cho khách</span>
            )}
            {activeTab === "BANK_TRANSFER" && (
              <span>{t('business:counterPayment.sepayAwaitWebhook')}</span>
            )}
            {activeTab === "SPLIT" && (
              <span>{t('business:counterPayment.sepaySplitSummary', { cash: formatVnd(splitCash), bank: formatVnd(splitBank) })}</span>
            )}
          </div>

          {activeTab === 'CASH' && !isWebBank && !sepaySession && totalDue > 0 && <button
            type="button"
            className={styles.confirmButton}
            disabled={!isCashSufficient || isSubmitting}
            onClick={() => void handleConfirm().catch(failure => message.error(getErrorMessage(failure)))}
          >
            <span>{t(isSubmitting ? "business:staffactions.processing" : "business:staffactions.confirmCollection")}</span>
            <ArrowRight size={18} />
          </button>}
        </div>
      </div>
    </div>
  );
};
