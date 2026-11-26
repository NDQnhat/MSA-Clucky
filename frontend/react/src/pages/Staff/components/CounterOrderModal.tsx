import React, { memo, useDeferredValue, useState, useMemo, useEffect, useCallback, useRef } from "react";
import { useTranslation } from "react-i18next";
import {
  X,
  Search,
  Utensils,
  ShoppingBag,
  Trash2,
  Plus,
  Minus,
  Sparkles,
  Loader2,
  Check,
  Flame,
  Lock,
  Clock,
  Camera,
  UserRound,
  Ticket,
} from "lucide-react";
import {
  POS_CATEGORIES,
  type PosProduct,
  type PosCartItem,
  type PosCartItemOption,
} from "../../../mocks/mockPosCatalog";
import type { DiningOption, PaymentMethod, StaffOrder } from "../../../mocks/mockStaffOrders";
import { useAuth } from "../../../context/AuthContext";
import { useOrders } from "../../../context/OrderContext";
import { message } from "../../../components/feedback";
import {
  catalogApi,
  getErrorMessage,
  ordersApi,
  paymentsApi,
  counterLoyaltyApi,
  type CounterOrderItemPayload,
  type CreateCounterOrderPayload,
  type BackendOrder,
  type CounterLoyaltyLookupKind,
  type CounterOrderPreview,
} from "../../../lib/api";
import { CounterQrScanner } from './CounterQrScanner';
import styles from "./counter-order.module.css";
import { FoodImage } from "../../../components/ui/FoodImage";
import { posUnavailableReason } from "../../../lib/pos-availability";

interface CounterOrderModalProps {
  open: boolean;
  onClose: () => void;
  onSubmitOrder?: (order: Partial<StaffOrder>) => Promise<void> | void;
  onSuccess?: (created?: BackendOrder, result?: { cashCollectionNeedsSync: boolean }) => Promise<void> | void;
  existingOrderCount?: number;
}

const currencyFormatter = new Intl.NumberFormat("vi-VN");
const formatVnd = (val: number) => {
  return currencyFormatter.format(val) + " đ";
};

type SafeCounterDraft = {
  savedAt: number;
  cartItems: PosCartItem[];
  diningOption: DiningOption;
  paymentMethod: PaymentMethod;
  orderNote: string;
  isCashCollected: boolean;
  cashTenderedInput: number;
};
type TabCounterDraft = SafeCounterDraft & {
  voucherInput: string;
  voucherCode: string;
  loyaltyKind: CounterLoyaltyLookupKind;
  loyaltyInput: string;
};

// Keep bearer voucher text in JS memory only (not sessionStorage). No QR proof,
// loyalty subject or point redemption authority is ever persisted.
const counterTabDrafts = new Map<string, TabCounterDraft>();
const COUNTER_DRAFT_TTL_MS = 12 * 60 * 60 * 1000;

function isSafeCounterDraft(value: unknown): value is SafeCounterDraft {
  if (!value || typeof value !== 'object') return false;
  const data = value as SafeCounterDraft;
  return Number.isFinite(data.savedAt) && Date.now() - data.savedAt < COUNTER_DRAFT_TTL_MS
    && data.savedAt <= Date.now() && Array.isArray(data.cartItems) && data.cartItems.length <= 100
    && data.cartItems.every(item => item && typeof item.id === 'string'
      && typeof item.productId === 'string' && typeof item.name === 'string'
      && Array.isArray(item.options) && item.options.every(option => option
        && typeof option.choiceId === 'string' && Number.isFinite(option.extraPrice))
      && Number.isInteger(item.quantity) && item.quantity > 0
      && Number.isFinite(item.unitPrice) && Number.isFinite(item.lineTotal))
    && (data.diningOption === 'DINE_IN' || data.diningOption === 'TAKE_AWAY')
    && (data.paymentMethod === 'CASH' || data.paymentMethod === 'BANK_TRANSFER' || data.paymentMethod === 'SPLIT')
    && typeof data.orderNote === 'string' && typeof data.isCashCollected === 'boolean'
    && Number.isFinite(data.cashTenderedInput);
}

const CounterProductCard = memo(function CounterProductCard({ product, storeReason, onSelect }: {
  product: PosProduct; storeReason?: string; onSelect: (product: PosProduct) => void;
}) {
  const { t } = useTranslation();
  const reason = storeReason || (product.unavailableReason ? t(`business:posAvailability.${product.unavailableReason}`) : undefined);
  const disabled = !product.inStock || !!storeReason;
  return <button type="button" disabled={disabled} className={`${styles.productCard} ${disabled ? styles.productCardSoldOut : ''}`}
    onClick={() => onSelect(product)} title={reason || product.name}>
    <div className={styles.imageContainer}>
      <FoodImage src={product.image} alt={product.name} className={styles.productImage} />
      {product.badge && !disabled && <span className={`${styles.productBadge} ${styles.badgeHot}`}>{product.badge}</span>}
    </div>
    <div className={styles.productCardBody}>
      <div className={styles.productName}>{product.name}</div><div className={styles.productPrice}>{formatVnd(product.price)}</div>
      {disabled ? <span className={styles.unavailableReason}><Lock size={12} />{reason || t('business:posAvailability.branchDisabled')}</span>
        : !!product.optionGroups?.length && <div className={styles.customizableHint}>{t('business:posUi.options')}</div>}
    </div>
  </button>;
});

export const CounterOrderModal: React.FC<CounterOrderModalProps> = ({
  open,
  onClose,
  onSubmitOrder,
  onSuccess,
  existingOrderCount = 0,
}) => {
  const { t } = useTranslation();
  const { user } = useAuth();
  const { currentStore, storeOpenState } = useOrders();
  const effectiveStoreId = Number(user?.storeId || currentStore?.id || 0);
  const draftStorageKey = `clucky:counter-draft:v1:${user.id}:${effectiveStoreId}`;
  // 1. Filter states
  const [activeCategory, setActiveCategory] = useState<string>("ALL");
  const [step, setStep] = useState<1 | 2 | 3 | 4>(1);
  const [searchQuery, setSearchQuery] = useState<string>("");
  const deferredSearch = useDeferredValue(searchQuery);

  // 2. Cart states
  const [diningOption, setDiningOption] = useState<DiningOption>("DINE_IN");
  const [cartItems, setCartItems] = useState<PosCartItem[]>([]);
  const [orderNote, setOrderNote] = useState<string>("");
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>("CASH");
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);
  const [isCashCollected, setIsCashCollected] = useState<boolean>(true);
  const [cashTenderedInput, setCashTenderedInput] = useState<number>(0);
  const [loyaltyKind, setLoyaltyKind] = useState<CounterLoyaltyLookupKind>('PHONE');
  const [loyaltyInput, setLoyaltyInput] = useState('');
  const [loyalty, setLoyalty] = useState<{ loyaltySubject: string; maskedAccount?: string; mode: 'TYPED' | 'QR' } | null>(null);
  const [scanProof, setScanProof] = useState<{ value: string; draftKey: string; draftHash: string; expiresAt: number } | null>(null);
  const [pointsUsed, setPointsUsed] = useState(0);
  const [voucherInput, setVoucherInput] = useState('');
  const [voucherCode, setVoucherCode] = useState('');
  const [voucherWarning, setVoucherWarning] = useState('');
  const [scanner, setScanner] = useState<'LOYALTY' | 'VOUCHER' | null>(null);
  const [identityBusy, setIdentityBusy] = useState(false);
  const [quoteBusy, setQuoteBusy] = useState(false);
  const [quoteError, setQuoteError] = useState('');
  const [quote, setQuote] = useState<{ key: string; value: CounterOrderPreview } | null>(null);
  const [clock, setClock] = useState(() => Date.now());
  const [hydratedDraftKey, setHydratedDraftKey] = useState('');
  const draftKeyRef = useRef('');
  const lookupSerial = useRef(0);
  const scanSerial = useRef(0);
  const submissionInFlight = useRef(false);
  const createAttempt = useRef<{
    fingerprint: string;
    key: string;
    payload: CreateCounterOrderPayload;
    quote: CounterOrderPreview;
  } | null>(null);

  const normalizeVoucherCode = (raw: string) =>
    raw.trim().toUpperCase().replace(/^CLUCKY:(?:V1|P1):/, '').trim();

  // 3. Option Customization Modal state
  const [customizingProduct, setCustomizingProduct] = useState<PosProduct | null>(null);
  const [selectedChoices, setSelectedChoices] = useState<Record<string, string>>({});
  const [customizingQty, setCustomizingQty] = useState<number>(1);

  // Search input ref to auto-focus
  const searchInputRef = useRef<HTMLInputElement>(null);

  // Danh mục sản phẩm POS lấy từ backend catalog-store-service
  const [posProducts, setPosProducts] = useState<PosProduct[]>([]);
  const [catalogLoading, setCatalogLoading] = useState(false);
  const [catalogError, setCatalogError] = useState('');
  const [catalogRevision, setCatalogRevision] = useState(0);

  useEffect(() => {
    if (!open) return;
    let active = true;
    const controller = new AbortController();
    setCatalogLoading(true); setCatalogError(''); setPosProducts([]);

    const loadBackendCatalog = async () => {
      try {
        const effectiveStoreId = user?.storeId ? Number(user.storeId) : currentStore?.id;
        if (!effectiveStoreId) throw new Error(t('business:posUi.noBranch'));
        const [productsData, combosData] = await Promise.all([
          catalogApi.listProducts({ storeId: effectiveStoreId, activeOnly: true }, controller.signal),
          catalogApi.listCombos(true, controller.signal),
        ]);

        if (!active) return;

        const convertedProducts: PosProduct[] = [];

        const rawProducts = Array.isArray(productsData)
          ? productsData
          : Array.isArray((productsData as any)?.content)
            ? (productsData as any).content
            : [];
        const rawCombos = Array.isArray(combosData)
          ? combosData
          : Array.isArray((combosData as any)?.content)
            ? (combosData as any).content
            : [];

        // Chuyển đổi sản phẩm đơn từ backend
        if (rawProducts.length > 0) {
          rawProducts.forEach((p: any) => {
            let category: PosProduct["category"] = "CHICKEN";
            const nameLower = (p.name || "").toLowerCase();
            const catNameLower = (p.categoryName || "").toLowerCase();

            if (
              p.categoryId === 2 ||
              catNameLower.includes("burger") ||
              catNameLower.includes("cơm") ||
              nameLower.includes("burger") ||
              nameLower.includes("cơm")
            ) {
              category = "BURGER_RICE";
            } else if (
              p.categoryId === 4 ||
              p.categoryId === 5 ||
              catNameLower.includes("nước") ||
              catNameLower.includes("tráng miệng") ||
              nameLower.includes("trà") ||
              nameLower.includes("nước") ||
              nameLower.includes("khoai tây")
            ) {
              category = "DESSERT_DRINK";
            } else if (p.categoryId === 1 || catNameLower.includes("gà")) {
              category = "CHICKEN";
            }

            convertedProducts.push({
              id: String(p.id),
              name: p.name,
              category,
              price: p.basePrice,
              image: p.imageUrl || '',
              inStock: !posUnavailableReason(p),
              unavailableReason: posUnavailableReason(p),
              badge: p.isBestSeller ? "Bán chạy" : undefined,
              description: p.description,
              optionGroups:
                p.options && p.options.length > 0
                  ? p.options.map((opt: any) => ({
                      id: opt.id,
                      name: opt.name,
                      required: opt.required,
                      choices: opt.choices.map((c: any) => ({
                        id: c.id,
                        name: c.name,
                        extraPrice: c.priceAdjustment || 0,
                      })),
                    }))
                  : undefined,
            });
          });
        }

        // Chuyển đổi Combo từ backend
        if (rawCombos.length > 0) {
          const availableProducts = new Set(convertedProducts.filter(product => product.inStock).map(product => product.id));
          rawCombos.forEach((c: any) => {
            const unavailableReason = posUnavailableReason(c) || (!c.items?.length || c.items.some((item: { productId: number }) => !availableProducts.has(String(item.productId))) ? 'comboUnavailable' : undefined);
            convertedProducts.push({
              id: `combo-${c.id}`,
              name: c.name,
              category: "COMBO",
              price: c.comboPrice,
              image: c.imageUrl || '',
              inStock: !unavailableReason,
              unavailableReason,
              badge: "Combo Tiết Kiệm",
              description:
                c.description ||
                c.items?.map((it: any) => `${it.quantity}x ${it.productName}`).join(" + "),
            });
          });
        }

        setPosProducts(convertedProducts);
      } catch (err) {
        if (active) { setPosProducts([]); setCatalogError(getErrorMessage(err)); }
      } finally {
        if (active) setCatalogLoading(false);
      }
    };

    loadBackendCatalog();
    return () => {
      active = false;
      controller.abort();
    };
  }, [open, user?.storeId, currentStore?.id, catalogRevision, t]);

  // Switching admin routes unmounts the POS. Restore its unsent draft in the
  // same staff/store scope rather than clearing the basket on every open.
  useEffect(() => {
    if (open) {
      setStep(1);
      lookupSerial.current++;
      scanSerial.current++;
      let stored: SafeCounterDraft | null = null;
      try {
        const text = window.sessionStorage.getItem(draftStorageKey);
        if (text) {
          const candidate: unknown = JSON.parse(text);
          if (isSafeCounterDraft(candidate)) stored = candidate;
          else window.sessionStorage.removeItem(draftStorageKey);
        }
      } catch {
        // Storage may be disabled; the in-memory tab draft still works.
      }
      const cached = counterTabDrafts.get(draftStorageKey);
      const tabDraft = isSafeCounterDraft(cached) ? cached : null;
      const restored = tabDraft ?? stored;
      setCartItems(restored?.cartItems ?? []);
      setOrderNote(restored?.orderNote ?? "");
      setDiningOption(restored?.diningOption ?? "DINE_IN");
      setPaymentMethod(restored?.paymentMethod ?? "CASH");
      setIsCashCollected(restored?.isCashCollected ?? true);
      setCashTenderedInput(restored?.cashTenderedInput ?? 0);
      setIsSubmitting(false);
      setLoyaltyInput(tabDraft?.loyaltyInput ?? '');
      setLoyaltyKind(tabDraft?.loyaltyKind ?? 'PHONE');
      setLoyalty(null);
      setScanProof(null);
      setPointsUsed(0);
      setVoucherCode(tabDraft?.voucherCode ?? '');
      setVoucherInput(tabDraft?.voucherInput ?? '');
      setVoucherWarning('');
      setScanner(null);
      setQuote(null);
      setQuoteError('');
      setCustomizingProduct(null);
      setHydratedDraftKey(draftStorageKey);
      const timer = window.setTimeout(() => {
        searchInputRef.current?.focus();
      }, 100);
      return () => window.clearTimeout(timer);
    }
  }, [open, draftStorageKey]);

  useEffect(() => {
    // Guard against writing initial empty React state over a saved draft
    // before hydration has completed. A successful order removes this key.
    if (!open || hydratedDraftKey !== draftStorageKey) return;
    const snapshot: TabCounterDraft = {
      savedAt: Date.now(), cartItems, orderNote, diningOption, paymentMethod,
      isCashCollected, cashTenderedInput, voucherCode, voucherInput, loyaltyKind, loyaltyInput,
    };
    const isEmpty = !cartItems.length && !orderNote.trim()
      && !voucherCode && !voucherInput.trim() && !loyaltyInput.trim()
      && diningOption === 'DINE_IN' && paymentMethod === 'CASH';
    if (isEmpty) counterTabDrafts.delete(draftStorageKey);
    else counterTabDrafts.set(draftStorageKey, snapshot);
    try {
      if (isEmpty) window.sessionStorage.removeItem(draftStorageKey);
      else {
        const safe: SafeCounterDraft = {
          savedAt: snapshot.savedAt, cartItems, orderNote, diningOption,
          paymentMethod, isCashCollected, cashTenderedInput,
        };
        window.sessionStorage.setItem(draftStorageKey, JSON.stringify(safe));
      }
    } catch {
      // Volatile in-memory draft remains available for route switching.
    }
  }, [open, hydratedDraftKey, draftStorageKey, cartItems, orderNote, diningOption,
    paymentMethod, isCashCollected, cashTenderedInput, voucherCode, voucherInput, loyaltyKind, loyaltyInput]);

  useEffect(() => {
    if (!open) {
      setHydratedDraftKey('');
      lookupSerial.current++;
      scanSerial.current++;
      setScanner(null);
      setLoyalty(null);
      setLoyaltyInput('');
      setScanProof(null);
      setPointsUsed(0);
      setVoucherInput('');
      setVoucherCode('');
      setVoucherWarning('');
      setIdentityBusy(false);
      setQuoteBusy(false);
      setQuote(null);
      setQuoteError('');
      createAttempt.current = null;
    }
  }, [open]);

  const addItemToCart = useCallback((
    product: PosProduct,
    options: PosCartItemOption[],
    optionsSummary: string,
    quantity: number,
  ) => {
    const extraPriceTotal = options.reduce((sum, opt) => sum + opt.extraPrice, 0);
    const unitPrice = product.price + extraPriceTotal;
    const cartItemId = `${product.id}-${options.map((o) => o.choiceId).sort().join("-") || "standard"}`;

    setCartItems((prev) => {
      const existingIdx = prev.findIndex((item) => item.id === cartItemId);
      if (existingIdx > -1) {
        const next = [...prev];
        const newQty = next[existingIdx].quantity + quantity;
        next[existingIdx] = {
          ...next[existingIdx],
          quantity: newQty,
          lineTotal: newQty * unitPrice,
        };
        return next;
      }
      return [
        ...prev,
        {
          id: cartItemId,
          productId: product.id,
          name: product.name,
          unitPrice,
          quantity,
          options,
          optionsSummary,
          lineTotal: unitPrice * quantity,
          image: product.image,
        },
      ];
    });
  }, []);

  // Handle open options modal for a product
  const handleOpenCustomizer = useCallback((product: PosProduct) => {
    if (!product.inStock || !storeOpenState.isOpen) return;

    if (!product.optionGroups || product.optionGroups.length === 0) {
      // Add directly to cart
      addItemToCart(product, [], "", 1);
      return;
    }

    // Initialize default choices
    const initialChoices: Record<string, string> = {};
    product.optionGroups.forEach((g) => {
      const defaultChoice = g.choices.find((c) => c.isDefault) || g.choices[0];
      if (defaultChoice) {
        initialChoices[g.id] = defaultChoice.id;
      }
    });

    setCustomizingProduct(product);
    setSelectedChoices(initialChoices);
    setCustomizingQty(1);
  }, [addItemToCart, storeOpenState.isOpen]);

  // Confirm custom options and add to cart
  const handleConfirmCustomOptions = () => {
    if (!customizingProduct) return;

    const chosenOptions: PosCartItemOption[] = [];
    let summaryParts: string[] = [];

    customizingProduct.optionGroups?.forEach((g) => {
      const chosenChoiceId = selectedChoices[g.id];
      const choice = g.choices.find((c) => c.id === chosenChoiceId);
      if (choice) {
        chosenOptions.push({
          groupId: g.id,
          groupName: g.name,
          choiceId: choice.id,
          choiceName: choice.name,
          extraPrice: choice.extraPrice,
        });
        summaryParts.push(
          choice.extraPrice > 0
            ? `${choice.name} (+${formatVnd(choice.extraPrice)})`
            : choice.name,
        );
      }
    });

    addItemToCart(
      customizingProduct,
      chosenOptions,
      summaryParts.join(" · "),
      customizingQty,
    );
    setCustomizingProduct(null);
  };


  // Quantity updates in cart
  const handleUpdateQty = (cartItemId: string, delta: number) => {
    setCartItems((prev) =>
      prev
        .map((item) => {
          if (item.id !== cartItemId) return item;
          const nextQty = item.quantity + delta;
          if (nextQty <= 0) return null;
          return {
            ...item,
            quantity: nextQty,
            lineTotal: nextQty * item.unitPrice,
          };
        })
        .filter(Boolean) as PosCartItem[],
    );
  };

  const handleRemoveItem = (cartItemId: string) => {
    setCartItems((prev) => prev.filter((item) => item.id !== cartItemId));
  };

  // Calculate totals
  const subtotal = useMemo(() => {
    return cartItems.reduce((acc, it) => acc + it.lineTotal, 0);
  }, [cartItems]);
  const orderItems: CounterOrderItemPayload[] = useMemo(() => cartItems.map(ci => {
    const isCombo = ci.productId.startsWith('combo-');
    const optionIds = ci.options.map(option => Number(String(option.choiceId ?? '').replace(/^opt-/i, '')))
      .filter(id => Number.isInteger(id) && id > 0);
    return {
      productId: isCombo ? undefined : Number(ci.productId),
      comboId: isCombo ? Number(ci.productId.slice('combo-'.length)) : undefined,
      quantity: ci.quantity,
      optionIds: optionIds.length ? optionIds : undefined,
      note: ci.note || undefined,
    };
  }), [cartItems]);
  const draft: CreateCounterOrderPayload = useMemo(() => ({
    storeId: effectiveStoreId,
    diningOption,
    paymentMethod,
    note: orderNote.trim() || undefined,
    items: orderItems,
    voucherCode: voucherCode || undefined,
  }), [effectiveStoreId, diningOption, paymentMethod, orderNote, orderItems, voucherCode]);
  // A proof cannot be reused for another cart, store or voucher.
  const draftKey = JSON.stringify({ storeId: effectiveStoreId, items: orderItems, voucherCode });
  draftKeyRef.current = draftKey;
  const liveProof = scanProof && scanProof.draftKey === draftKey && scanProof.expiresAt > Date.now() ? scanProof : null;
  const currentLoyalty = loyalty?.mode === 'QR' && !liveProof ? null : loyalty;
  const allowedPointsUsed = currentLoyalty?.mode === 'QR' && liveProof ? pointsUsed : 0;
  const pricedPayload: CreateCounterOrderPayload = {
    ...draft,
    loyaltySubject: currentLoyalty?.loyaltySubject,
    loyaltyScanProof: liveProof?.value,
    pointsUsed: allowedPointsUsed,
  };
  // Keep an ambiguous attempt tied to the original request even if its QR expires.
  // The backend compares the complete payload hash for this Idempotency-Key.
  const attemptFingerprint = JSON.stringify({
    ...draft,
    loyaltySubject: loyalty?.loyaltySubject,
    loyaltyScanProof: scanProof?.value,
    pointsUsed,
  });
  const retryableAttempt = createAttempt.current?.fingerprint === attemptFingerprint
    ? createAttempt.current : null;
  const quoteKey = JSON.stringify(pricedPayload);
  const activeQuote = retryableAttempt?.quote ?? (quote?.key === quoteKey ? quote.value : null);
  const totalAmount = activeQuote ? Number(activeQuote.totalAmount) : subtotal;
  const noPayment = activeQuote?.totalAmount === 0;
  const voucherDiscount = Math.max(0, Number(activeQuote?.voucherDiscount ?? 0));
  const tierDiscount = Math.max(0, Number(activeQuote?.tierDiscount ?? 0));
  const pointsDiscount = Math.max(0, Number(activeQuote?.pointsAmount ?? 0));
  const maxRedeemablePoints = Math.max(0, Number(activeQuote?.maxRedeemablePoints ?? 0));
  const hasDiscount = voucherDiscount > 0 || tierDiscount > 0 || pointsDiscount > 0;
  const effectiveCashTendered = cashTenderedInput >= totalAmount ? cashTenderedInput : totalAmount;
  const effectiveCashChange = Math.max(0, effectiveCashTendered - totalAmount);

  useEffect(() => {
    setCashTenderedInput((prev) => (prev < totalAmount ? totalAmount : prev));
  }, [totalAmount]);

  useEffect(() => {
    if (!open) return;
    const interval = window.setInterval(() => setClock(Date.now()), 1000);
    return () => window.clearInterval(interval);
  }, [open]);

  useEffect(() => {
    setScanProof(null);
    setPointsUsed(0);
    if (loyalty?.mode === 'QR') setLoyalty(null);
    scanSerial.current++;
    setScanner(null);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draftKey]);

  useEffect(() => {
    if (createAttempt.current?.fingerprint !== attemptFingerprint) {
      createAttempt.current = null;
    }
  }, [attemptFingerprint]);

  useEffect(() => {
    if (!open || !effectiveStoreId || !orderItems.length) {
      setQuote(null);
      setQuoteError('');
      setQuoteBusy(false);
      return;
    }
    const controller = new AbortController();
    let live = true;
    setQuoteBusy(true);
    setQuoteError('');
    const timer = window.setTimeout(async () => {
      try {
        const response = await ordersApi.previewCounterOrder(pricedPayload, controller.signal);
        if (!live) return;
        if (pricedPayload.voucherCode && !response.voucherValid) {
          const reason = response.voucherMessage || t('business:counterLoyalty.voucherInvalidFallback');
          setVoucherWarning(`${reason} ${t('business:counterLoyalty.voucherFallbackOriginalPrice')}`);
          setVoucherCode('');
          setQuote({ key: quoteKey, value: response });
        } else {
          if (pricedPayload.voucherCode && response.voucherValid) {
            setVoucherWarning('');
          }
          setQuote({ key: quoteKey, value: response });
        }
      } catch (error) {
        if (live) {
          setQuote(null);
          setQuoteError(getErrorMessage(error));
        }
      } finally {
        if (live) setQuoteBusy(false);
      }
    }, 220);
    return () => { live = false; controller.abort(); window.clearTimeout(timer); };
  // Payload identity is expressed by quoteKey; this avoids re-requesting the same quote on unrelated renders.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, effectiveStoreId, quoteKey]);

  const handleLookup = async () => {
    if (!effectiveStoreId || !loyaltyInput.trim() || identityBusy) return;
    const serial = ++lookupSerial.current;
    setIdentityBusy(true);
    setScanProof(null);
    setPointsUsed(0);
    setLoyalty(null);
    try {
      const found = await counterLoyaltyApi.lookup({
        kind: loyaltyKind, value: loyaltyInput.trim(), storeId: effectiveStoreId,
      });
      if (serial !== lookupSerial.current || !open) return;
      setLoyalty({ loyaltySubject: found.loyaltySubject, maskedAccount: found.maskedAccount || undefined, mode: 'TYPED' });
      message.success(t('business:counterLoyalty.earnOnly'));
    } catch (error) {
      if (serial === lookupSerial.current) message.error(getErrorMessage(error));
    } finally {
      setIdentityBusy(false);
    }
  };

  const handleQrDetected = async (qrValue: string): Promise<boolean> => {
    if (submissionInFlight.current) return false;
    const mode = scanner;
    if (mode === 'VOUCHER') {
      if (!/^CLUCKY:(?:V1:CK1|P1:CKP1)-[A-Z2-7]{12}-[A-Z2-7]{32}$/i.test(qrValue)) {
        message.error(t('business:counterLoyalty.wrongQrType'));
        return false;
      }
      setScanner(null);
      const normalized = normalizeVoucherCode(qrValue);
      setVoucherWarning('');
      setVoucherInput(normalized);
      setVoucherCode(normalized);
      return true;
    }
    if (mode !== 'LOYALTY') return false;
    if (!qrValue.startsWith('CLUCKY:LOYALTY:V1:')) {
      message.error(t('business:counterLoyalty.wrongQrType'));
      return false;
    }
    if (!effectiveStoreId || !orderItems.length) {
      message.error(t('business:counterLoyalty.cartRequired'));
      return false;
    }
    setScanner(null);
    const serial = ++scanSerial.current;
    const binding = draftKeyRef.current;
    setIdentityBusy(true);
    setLoyalty(null);
    setScanProof(null);
    setPointsUsed(0);
    try {
      // Server hashes the exact cart/store/voucher BEFORE minting a scan proof.
      const draftResponse = await ordersApi.counterDraftHash({ ...draft, loyaltySubject: undefined, loyaltyScanProof: undefined, pointsUsed: 0 });
      if (serial !== scanSerial.current || draftKeyRef.current !== binding || !open) return true;
      const result = await counterLoyaltyApi.scan({
        qrToken: qrValue, storeId: effectiveStoreId, draftHash: draftResponse.draftHash,
      });
      if (serial !== scanSerial.current || draftKeyRef.current !== binding || !open) return true;
      const expiresAt = new Date(result.expiresAt).getTime();
      if (!result.redeemAllowed || !Number.isFinite(expiresAt) || expiresAt <= Date.now()) throw new Error(t('business:counterLoyalty.scanExpired'));
      setClock(Date.now());
      setLoyalty({ loyaltySubject: result.loyaltySubject, mode: 'QR' });
      setScanProof({ value: result.scanProof, draftKey: binding, draftHash: draftResponse.draftHash, expiresAt });
      message.success(t('business:counterLoyalty.scanSuccess'));
    } catch (error) {
      if (serial === scanSerial.current) {
        const status = (error as { response?: { status?: number } })?.response?.status;
        message.error(status === 409
          ? t('business:counterLoyalty.scanRejected')
          : getErrorMessage(error));
      }
    } finally {
      setIdentityBusy(false);
    }
    return true;
  };

  const updateVoucherInput = (value: string) => {
    setVoucherInput(value);
    setVoucherWarning('');
    if (normalizeVoucherCode(value) !== voucherCode) {
      // Invalidate the QR proof as soon as the voucher text is changed.
      setScanProof(null);
      setPointsUsed(0);
      if (loyalty?.mode === 'QR') setLoyalty(null);
      if (voucherCode) setVoucherCode('');
    }
  };

  const applyVoucherInput = () => {
    const normalized = normalizeVoucherCode(voucherInput);
    setVoucherWarning('');
    setVoucherInput(normalized);
    if (normalized !== voucherCode) {
      setScanProof(null);
      setPointsUsed(0);
      if (loyalty?.mode === 'QR') setLoyalty(null);
    }
    setVoucherCode(normalized);
  };

  // Submit order handler
  const handleSubmit = async () => {
    if (cartItems.length === 0 || submissionInFlight.current || !storeOpenState.isOpen) return;

    submissionInFlight.current = true;
    setIsSubmitting(true);
    // Any pending lookup/scan belongs to the editable draft, not this locked order.
    lookupSerial.current++;
    scanSerial.current++;
    setScanner(null);
    try {
      let attempt = retryableAttempt;
      if (!attempt) {
        if (!activeQuote || quoteBusy || quoteError) {
          message.error(t('business:counterLoyalty.quoteRequired'));
          return;
        }
        if (pointsUsed > 0 && (!liveProof || liveProof.expiresAt <= Date.now() || !activeQuote.redeemAllowed)) {
          message.error(t('business:counterLoyalty.scanExpired'));
          return;
        }
        const latestQuote = await ordersApi.previewCounterOrder(pricedPayload);
        if (draftKeyRef.current !== draftKey
            || latestQuote.draftHash !== activeQuote.draftHash
            || latestQuote.totalAmount !== activeQuote.totalAmount
            || latestQuote.voucherDiscount !== activeQuote.voucherDiscount
            || latestQuote.pointsAmount !== activeQuote.pointsAmount
            || latestQuote.pointsUsed !== activeQuote.pointsUsed
            || (liveProof && latestQuote.draftHash !== liveProof.draftHash)) {
          message.error(t('business:counterLoyalty.quoteChanged'));
          setScanProof(null);
          setPointsUsed(0);
          setQuote(null);
          return;
        }
        attempt = {
          fingerprint: attemptFingerprint,
          key: typeof crypto !== 'undefined' && crypto.randomUUID
            ? crypto.randomUUID()
            : `counter-${Date.now()}-${Math.random().toString(36).slice(2)}`,
          payload: { ...pricedPayload, items: pricedPayload.items.map(item => ({ ...item })) },
          quote: activeQuote,
        };
        createAttempt.current = attempt;
      }
      const created = await ordersApi.createCounterOrder(attempt.payload, attempt.key);
      // A definitive success is the only point at which the key can be discarded.
      createAttempt.current = null;
      // Never restore a draft for an order that the server has accepted.
      counterTabDrafts.delete(draftStorageKey);
      try { window.sessionStorage.removeItem(draftStorageKey); } catch {}
      setHydratedDraftKey('');

      let cashCollectionNeedsSync = false;
      let cashCollectionReceiptRecorded = false;
      if (paymentMethod === "CASH" && isCashCollected && Number(created.totalAmount) > 0
          && created.paymentStatus === "PENDING" && created.id && created.orderCode) {
        const collectionPayload = {
          orderId: Number(created.id),
          orderCode: created.orderCode,
          queueNumber: created.queueNumber ?? existingOrderCount + 1,
          storeId: Number(created.storeId || effectiveStoreId),
          totalDue: Number(created.totalAmount ?? totalAmount),
          method: "CASH" as const,
          cashAmount: Number(created.totalAmount ?? totalAmount),
          cashTendered: effectiveCashTendered,
          bankAmount: 0,
          manualAcceptance: false,
          staffNotes: t("business:counterPos.autoCollectStaffNote"),
        };
        // Reuse the same key if the response is lost after the service commits.
        // Payment Service replays the original receipt for an identical key.
        const collectionKey = typeof crypto !== 'undefined' && crypto.randomUUID
          ? crypto.randomUUID()
          : `counter-cash-${Date.now()}-${Math.random().toString(36).slice(2)}`;
        try {
          let receipt: Awaited<ReturnType<typeof paymentsApi.collectCounter>> | undefined;
          try {
            receipt = await paymentsApi.collectCounter(collectionPayload, collectionKey);
          } catch (failure) {
            const status = (failure as { response?: { status?: number }; status?: number })?.response?.status
              ?? (failure as { status?: number })?.status;
            // A network timeout, gateway timeout or server error can happen
            // after the collection transaction has committed. Retry once with
            // the same key so this cannot create a second receipt.
            if (status === undefined || status === 408 || status >= 500) {
              try {
                receipt = await paymentsApi.collectCounter(collectionPayload, collectionKey);
              } catch {
                // The result is still ambiguous. Let order polling determine
                // whether the asynchronous collection event was applied.
                cashCollectionNeedsSync = true;
              }
            }
          }
          if (receipt) {
            cashCollectionReceiptRecorded = ['PENDING', 'ACCEPTED'].includes(receipt.collectionStatus);
            cashCollectionNeedsSync = cashCollectionReceiptRecorded;
          }
          if (!cashCollectionReceiptRecorded) message.warning(t('business:counterLoyalty.cashCollectionDeferred'));
        } catch {
          message.warning(t('business:counterLoyalty.cashCollectionDeferred'));
        }
      }

      const qNum = created.queueNumber ?? existingOrderCount + 1;
      const formattedQueue = `#${String(qNum).padStart(3, "0")}`;
      message.success(t("business:staffactions.counterCreated", { queue: formattedQueue }));

      setCartItems([]);
      setOrderNote("");
      onClose();

      if (onSuccess) {
        await onSuccess(created, { cashCollectionNeedsSync });
      } else if (onSubmitOrder) {
        await onSubmitOrder(created as any);
      }
    } catch (err: any) {
      message.error(t("business:staffactions.counterCreateFailed", {
        detail: err?.response?.data?.message || err?.message || t("business:staffactions.unableToCreateOrder"),
      }));
    } finally {
      submissionInFlight.current = false;
      setIsSubmitting(false);
    }
  };

  const submitRef = useRef(handleSubmit);
  submitRef.current = handleSubmit;

  // Hotkey listener: Esc to close, Enter to advance or submit on the final step.
  useEffect(() => {
    if (!open) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        if (submissionInFlight.current) return;
        if (scanner) {
          setScanner(null);
        } else if (customizingProduct) {
          setCustomizingProduct(null);
        } else {
          onClose();
        }
      } else if (e.key === "Enter") {
        // If Enter pressed while not focusing on textarea or inside options modal
        const target = e.target as HTMLElement;
        const isEditing = target.closest('input, textarea, select, button, [contenteditable="true"]');
        if (!e.repeat && !submissionInFlight.current && !isEditing && !scanner && !customizingProduct && cartItems.length > 0 && storeOpenState.isOpen) {
          e.preventDefault();
          if (step === 4) void submitRef.current();
          else setStep((current) => Math.min(4, current + 1) as 1 | 2 | 3 | 4);
        }
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => {
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [open, scanner, customizingProduct, cartItems.length, onClose, storeOpenState.isOpen, step]);

  // Filter products by category and search
  const visibleProducts = useMemo(() => {
    return posProducts.filter((p) => {
      if (activeCategory !== "ALL" && p.category !== activeCategory) {
        return false;
      }
      if (deferredSearch.trim()) {
        const q = deferredSearch.toLowerCase().trim();
        return (
          p.name.toLowerCase().includes(q) ||
          (p.description || "").toLowerCase().includes(q)
        );
      }
      return true;
    });
  }, [posProducts, activeCategory, deferredSearch]);

  if (!open) return null;

  return (
    <div className={styles.modalOverlay} role="dialog" aria-modal="true" aria-label={t('business:counterPos.windowTitle')}>
      <fieldset className={styles.modalWindow} disabled={isSubmitting} aria-busy={isSubmitting}>
        {/* Header */}
        <div className={styles.modalHeader}>
          <div className={styles.headerLeft}>
            <div className={styles.posTitle}>
              <Sparkles size={20} />
              <span>{t('business:counterPos.windowTitle')}</span>
            </div>
            <span className={styles.posSubtitle}>
              {currentStore?.name || user?.storeName || t("business:stafforderspage.branchFallback", { id: user?.storeId || 1 })}
              {currentStore?.address ? ` · ${currentStore.address}` : ''}
            </span>
          </div>

          <div className={styles.headerShortcuts}>
            <div className={styles.shortcutHint}>
              <span className={styles.kbd}>Esc</span> {t('business:counterPos.close')}
            </div>
            <div className={styles.shortcutHint}>
              <span className={styles.kbd}>Enter</span> {t(step === 4 ? 'business:counterPos.create' : 'business:counterPos.continue')}
            </div>
            <button
              type="button"
              className={styles.closeButton}
              onClick={onClose}
              aria-label={t('business:counterPos.close')}
            >
              <X size={20} />
            </button>
          </div>
        </div>

        {!storeOpenState.isOpen && (
          <div
            role="status"
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              gap: 14,
              padding: "12px 24px",
              backgroundColor: "#FEF2F2",
              borderBottom: "1px solid #FECACA",
              color: "#991B1B",
              flexWrap: "wrap",
            }}
          >
            <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <Clock size={18} color="#B42318" />
              <div>
                <strong style={{ fontSize: 13, display: "block" }}>
                  {storeOpenState.bannerTitle}
                </strong>
                <span style={{ fontSize: 12, color: "#7F1D1D" }}>
                  {storeOpenState.bannerDescription}
                </span>
              </div>
            </div>
            <span
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 5,
                padding: "4px 10px",
                borderRadius: 999,
                backgroundColor: "#B42318",
                color: "#FFF",
                fontSize: 11,
                fontWeight: 800,
              }}
            >
              <Lock size={12} /> {t("business:storeStatus.lockedActionBadge")}
            </span>
          </div>
        )}

        <nav className={styles.stepper} aria-label={t('business:counterPos.stepsLabel')}>
          {(['chooseFood', 'orderDetails', 'loyalty', 'payment'] as const).map((label, index) => {
            const target = (index + 1) as 1 | 2 | 3 | 4;
            return <button key={label} type="button" className={`${styles.stepButton} ${step === target ? styles.stepButtonActive : ''}`}
              aria-current={step === target ? 'step' : undefined} disabled={target > step}
              onClick={() => setStep(target)}>
              <span className={styles.stepNumber}>{target}</span><span>{t(`business:counterPos.${label}`)}</span>
            </button>;
          })}
        </nav>

        <div className={`${styles.modalBody} ${step > 1 ? styles.stepBody : ''}`}>
          {/* Cột Trái: Catalog Grid */}
          {step === 1 && <div className={styles.catalogColumn}>
            {/* Toolbar: Search + Category Tabs */}
            <div className={styles.catalogToolbar}>
              <div className={styles.searchBar}>
                <Search size={18} color="#6b6259" />
                <input
                  ref={searchInputRef}
                  type="text"
                  className={styles.searchInput}
                  placeholder={t('business:counterPos.searchPlaceholder')}
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                />
                {searchQuery && (
                  <button
                    type="button"
                    style={{ border: "none", background: "transparent", cursor: "pointer", color: "#6b6259" }}
                    onClick={() => setSearchQuery("")}
                  >
                    <X size={16} />
                  </button>
                )}
              </div>

              <div className={styles.categoryTabs} role="tablist">
                {POS_CATEGORIES.map((cat) => {
                  const isActive = activeCategory === cat.key;
                  return (
                    <button
                      key={cat.key}
                      type="button"
                      role="tab"
                      aria-selected={isActive}
                      className={`${styles.categoryTab} ${
                        isActive ? styles.categoryTabActive : ""
                      }`}
                      onClick={() => setActiveCategory(cat.key)}
                    >
                      {cat.label}
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Product Grid */}
            <div className={styles.productGridScroll}>
              {catalogLoading && <p role="status" className={styles.catalogState}><Loader2 size={18} />{t('business:posUi.loading')}</p>}
              {catalogError && <div role="alert" className={styles.catalogState}><span>{catalogError}</span><button type="button" onClick={() => setCatalogRevision(value => value + 1)}>{t('business:dashboard.retry')}</button></div>}
              {!catalogLoading && !catalogError && !visibleProducts.length && <p className={styles.catalogState}>{t('business:posUi.empty')}</p>}
              <div className={styles.productGrid}>
                {visibleProducts.map(product => <CounterProductCard key={product.id} product={product}
                  storeReason={!storeOpenState.isOpen ? storeOpenState.bannerTitle : undefined} onSelect={handleOpenCustomizer} />)}
              </div>
            </div>
          </div>}

          {/* Cột Phải: Giỏ Hàng Quầy */}
          <div className={`${styles.cartColumn} ${step > 1 ? styles.stepCartColumn : ''}`}>
            {step > 1 && <div className={styles.stepIntro} role="status">
              <h2>{t(step === 2 ? 'business:counterPos.reviewOrder' : step === 3 ? 'business:counterPos.reviewLoyalty' : 'business:counterPos.reviewPayment')}</h2>
              <p>{t(step === 2 ? 'business:counterPos.reviewOrderHint'
                : step === 3 ? 'business:counterPos.reviewLoyaltyHint'
                  : 'business:counterPos.reviewPaymentHint')}</p>
            </div>}
            {/* Toggle ĂN TẠI QUÁN / MANG VỀ */}
            {step === 2 && <div className={styles.diningToggleBar}>
              <button
                type="button"
                className={`${styles.diningBtn} ${
                  diningOption === "DINE_IN" ? styles.diningBtnActive : ""
                }`}
                aria-pressed={diningOption === 'DINE_IN'}
                onClick={() => setDiningOption("DINE_IN")}
              >
                <Utensils size={18} />
                <span>ĂN TẠI QUÁN</span>
              </button>

              <button
                type="button"
                className={`${styles.diningBtn} ${
                  diningOption === "TAKE_AWAY" ? styles.diningBtnActive : ""
                }`}
                aria-pressed={diningOption === 'TAKE_AWAY'}
                onClick={() => setDiningOption("TAKE_AWAY")}
              >
                <ShoppingBag size={18} />
                <span>MANG VỀ</span>
              </button>
            </div>}

            {step <= 2 && <div className={styles.cartSectionHeader}>
              <strong>{t('business:counterPos.cartTitle')}</strong>
              <span>{t('business:counterPos.cartCount', { count: cartItems.reduce((sum, item) => sum + item.quantity, 0) })}</span>
            </div>}
            {/* Danh sách món trong giỏ */}
            {step <= 2 && <div className={`${styles.cartListContainer} ${step === 1 ? styles.compactCartList : ''} ${cartItems.length === 0 ? styles.cartListEmpty : ''}`}>
              {cartItems.length === 0 ? (
                <div className={styles.cartEmptyState}>
                  <Utensils size={36} strokeWidth={1.5} />
                  <p style={{ marginTop: 12, fontWeight: 600 }}>{t('business:counterPos.emptyCart')}</p>
                </div>
              ) : (
                cartItems.map((item) => (
                  <div key={item.id} className={styles.cartItemRow}>
                    <div className={styles.cartItemMain}>
                      <div className={styles.cartItemIdentity}>
                        {step === 2 && <FoodImage src={item.image} alt="" className={styles.cartItemImage} />}
                        <span className={styles.cartItemName}>{item.name}</span>
                      </div>
                      <span className={styles.cartItemTotal}>
                        {formatVnd(item.lineTotal)}
                      </span>
                    </div>

                    {item.optionsSummary && (
                      <div className={styles.cartItemOptions}>
                        {item.optionsSummary}
                      </div>
                    )}
                    {step === 2 && <div className={styles.cartItemOptions}>
                      {item.quantity} × {formatVnd(item.unitPrice)}
                      {item.note && <> · {item.note}</>}
                    </div>}

                    <div className={styles.cartItemActions}>
                      <div className={styles.qtyControls}>
                        <button
                          type="button"
                          className={styles.qtyBtn}
                          onClick={() => handleUpdateQty(item.id, -1)}
                          title="Giảm 1"
                        >
                          <Minus size={14} />
                        </button>
                        <span className={styles.qtyCount}>{item.quantity}</span>
                        <button
                          type="button"
                          className={styles.qtyBtn}
                          onClick={() => handleUpdateQty(item.id, 1)}
                          title="Tăng 1"
                        >
                          <Plus size={14} />
                        </button>
                      </div>

                      <button
                        type="button"
                        className={styles.deleteItemBtn}
                        onClick={() => handleRemoveItem(item.id)}
                        title="Xóa món này khỏi giỏ"
                      >
                        <Trash2 size={16} />
                      </button>
                    </div>
                  </div>
                ))
              )}
            </div>}

            {step === 1 && <div className={styles.stepOneSummary}>
              <span>{t('business:counterPos.itemsInCart', { count: cartItems.reduce((sum, item) => sum + item.quantity, 0) })}</span>
              <strong>{formatVnd(subtotal)}</strong>
            </div>}

            {/* Footer giỏ hàng: Ghi chú, Phương thức, Tổng kết & Nút tạo đơn */}
            {step > 1 && <div className={styles.cartFooter}>
              {/* Chọn hình thức thanh toán ở bước cuối. */}
              {step === 4 && (noPayment ? <p role="status" className={styles.paymentHint}>
                {t('business:counterPayment.noPaymentExplanation')}
              </p> : <div className={styles.paymentMethodRow}>
                <button
                  type="button"
                  className={`${styles.pmBtn} ${
                    paymentMethod === "CASH" ? styles.pmBtnActive : ""
                  }`}
                  aria-pressed={paymentMethod === 'CASH'}
                  onClick={() => setPaymentMethod("CASH")}
                >
                  TIỀN MẶT
                </button>
                <button
                  type="button"
                  className={`${styles.pmBtn} ${
                    paymentMethod === "BANK_TRANSFER" ? styles.pmBtnActive : ""
                  }`}
                  aria-pressed={paymentMethod === 'BANK_TRANSFER'}
                  onClick={() => setPaymentMethod("BANK_TRANSFER")}
                >
                  SEPAY / VIETQR
                </button>
                <button
                  type="button"
                  className={`${styles.pmBtn} ${
                    paymentMethod === "SPLIT" ? styles.pmBtnActive : ""
                  }`}
                  aria-pressed={paymentMethod === 'SPLIT'}
                  onClick={() => setPaymentMethod("SPLIT")}
                >
                  KẾT HỢP
                </button>
              </div>)}

              {/* Thu tiền trực tiếp: chỉ hiển thị nhập tiền khi đã chọn thu tại quầy. */}
              {step === 4 && !noPayment && paymentMethod === "CASH" && (
                <div className={styles.cashPanel}>
                  <label className={styles.cashCheckboxRow}>
                    <input
                      type="checkbox"
                      checked={isCashCollected}
                      onChange={(event) => setIsCashCollected(event.target.checked)}
                    />
                    {t("business:counterPos.confirmCashAtCounter")}
                  </label>
                  {isCashCollected && totalAmount > 0 && (
                    <div className={styles.cashDetails}>
                      <label className={styles.cashAmountRow}>
                        <span>{t("business:counterPos.cashTenderedLabel")}</span>
                        <input type="number" min={totalAmount} step={1000}
                          value={cashTenderedInput || totalAmount}
                          onChange={(event) => setCashTenderedInput(Number(event.target.value) || 0)} />
                      </label>
                      <div className={styles.cashPresetRow}>
                        {[totalAmount, 100000, 200000, 500000]
                          .filter((value, index, values) => value >= totalAmount && values.indexOf(value) === index)
                          .map(preset => (
                            <button key={preset} type="button"
                              className={`${styles.cashPresetBtn} ${effectiveCashTendered === preset ? styles.cashPresetBtnActive : ''}`}
                              onClick={() => setCashTenderedInput(preset)}>
                              {preset === totalAmount ? t("business:counterPos.exactCash") : formatVnd(preset)}
                            </button>
                          ))}
                      </div>
                      {effectiveCashChange > 0 && (
                        <div className={styles.cashChangeRow}>
                          <span>{t("business:counterPos.cashChangeLabel")}</span>
                          <strong>{formatVnd(effectiveCashChange)}</strong>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )}

              {step === 4 && !noPayment && (paymentMethod === "BANK_TRANSFER" || paymentMethod === "SPLIT") && (
                <p role="note" className={styles.paymentHint}>
                  {t("business:counterPos.qrAfterCreation")}
                </p>
              )}

              {step === 3 && <section className={styles.loyaltyPanel} aria-label={t('business:counterLoyalty.title')}>
                <div className={styles.panelHeadingRow}>
                  <div className={styles.loyaltyHeading}><UserRound size={18} />
                    <strong>{t('business:counterLoyalty.title')}</strong>
                  </div>
                  <button type="button" className={styles.scanActionBtn} disabled={identityBusy || !orderItems.length || !effectiveStoreId}
                    onClick={() => setScanner('LOYALTY')}><Camera size={17} /> {t('business:counterLoyalty.scanLoyalty')}</button>
                </div>
                <div className={styles.loyaltyFormRow}>
                  <select className={styles.loyaltySelect} aria-label={t('business:counterLoyalty.lookupType')}
                    value={loyaltyKind} onChange={event => {
                    setLoyaltyKind(event.target.value as CounterLoyaltyLookupKind);
                    lookupSerial.current++;
                    setLoyalty(null); setScanProof(null); setPointsUsed(0);
                  }}>
                    <option value="PHONE">{t('business:counterLoyalty.phone')}</option>
                    <option value="EMAIL">{t('business:counterLoyalty.email')}</option>
                    <option value="LOYALTY_TEXT_CODE">{t('business:counterLoyalty.textCode')}</option>
                  </select>
                  <input
                    type="text"
                    value={loyaltyInput}
                    placeholder={t('business:counterLoyalty.lookupPlaceholder')}
                    onChange={event => {
                      lookupSerial.current++;
                      setLoyaltyInput(event.target.value);
                      if (loyalty?.mode === 'TYPED') setLoyalty(null);
                      setScanProof(null); setPointsUsed(0);
                    }}
                    onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); void handleLookup(); } }}
                  />
                  <button type="button" disabled={identityBusy || !loyaltyInput.trim() || !effectiveStoreId}
                    onClick={() => void handleLookup()}>{t('business:counterLoyalty.lookup')}</button>
                </div>
                {currentLoyalty && <p className={styles.loyaltyStatus} role="status">
                  <Check size={14} /> {currentLoyalty.mode === 'QR'
                    ? t('business:counterLoyalty.qrConfirmed')
                    : t('business:counterLoyalty.earnOnly')}
                  {currentLoyalty.maskedAccount ? ` · ${currentLoyalty.maskedAccount}` : ''}
                </p>}
                {loyalty?.mode === 'QR' && !liveProof && <p role="alert" className={styles.loyaltyWarning}>
                  {t('business:counterLoyalty.scanExpired')}
                </p>}
                {currentLoyalty?.mode === 'QR' && <div className={styles.pointsRow}>
                  <label htmlFor="counter-points-used">{t('business:counterLoyalty.pointsToUse')}</label>
                  <input id="counter-points-used" type="number" min={0}
                    max={maxRedeemablePoints}
                    step={1} value={allowedPointsUsed}
                    disabled={!liveProof || !currentLoyalty || !activeQuote?.redeemAllowed || quoteBusy || maxRedeemablePoints <= 0}
                    onChange={event => setPointsUsed(Math.min(
                      Math.max(0, Math.floor(Number(event.target.value) || 0)),
                      maxRedeemablePoints,
                    ))} />
                  <button type="button" disabled={!liveProof || !activeQuote?.redeemAllowed || quoteBusy || maxRedeemablePoints <= 0}
                    onClick={() => setPointsUsed(maxRedeemablePoints)}>
                    {t('business:counterLoyalty.useMax')}
                  </button>
                </div>}
                <p className={styles.loyaltyHint}>
                  {liveProof && activeQuote?.redeemAllowed
                    ? t('business:counterLoyalty.availablePoints', { count: activeQuote.availablePoints,
                      seconds: Math.max(0, Math.ceil((liveProof.expiresAt - clock) / 1000)) })
                    : t('business:counterLoyalty.optionalHint')}
                </p>
              </section>}

              {step === 3 && <section className={styles.loyaltyPanel} aria-label={t('business:counterLoyalty.voucherTitle')}>
                <div className={styles.panelHeadingRow}>
                  <div className={styles.loyaltyHeading}><Ticket size={18} />
                    <strong>{t('business:counterLoyalty.voucherTitle')}</strong>
                  </div>
                  <button type="button" className={styles.scanActionBtn} onClick={() => setScanner('VOUCHER')}>
                    <Camera size={17} /> {t('business:counterLoyalty.scanVoucher')}
                  </button>
                </div>
                <div className={styles.loyaltyFormRow}>
                  <input type="text" value={voucherInput}
                    placeholder={t('business:counterLoyalty.voucherPlaceholder')}
                    onChange={event => updateVoucherInput(event.target.value)}
                    onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); applyVoucherInput(); } }}
                  />
                  <button type="button" disabled={!voucherInput.trim() || identityBusy}
                    onClick={applyVoucherInput}>
                    {t('business:counterLoyalty.applyVoucher')}
                  </button>
                </div>
                {voucherCode && <div className={styles.appliedVoucher}>
                  <span><Check size={14} /> {t('business:counterLoyalty.voucherAppliedShort')}</span>
                  <button type="button" onClick={() => { setVoucherCode(''); setVoucherInput(''); setVoucherWarning(''); }}>
                    {t('business:counterLoyalty.removeVoucher')}
                  </button>
                </div>}
                {!voucherCode && voucherWarning && (
                  <div role="alert" className={styles.loyaltyWarning} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                    <span>{voucherWarning}</span>
                    <button
                      type="button"
                      style={{ border: 0, background: 'transparent', color: '#8f1216', fontWeight: 700, textDecoration: 'underline', cursor: 'pointer', flexShrink: 0 }}
                      onClick={() => { setVoucherWarning(''); setVoucherInput(''); }}
                    >
                      {t('business:counterLoyalty.removeVoucher')}
                    </button>
                  </div>
                )}
              </section>}

              {step >= 3 && voucherWarning && step === 4 && (
                <p role="alert" className={styles.loyaltyWarning}>
                  {voucherWarning}
                </p>
              )}

              {step >= 3 && (quoteError || quoteBusy) && <p role={quoteError ? 'alert' : 'status'}
                className={quoteError ? styles.loyaltyWarning : styles.loyaltyHint}>
                {quoteError || t('business:counterLoyalty.quoting')}
              </p>}

              {step === 2 && <input
                type="text"
                className={styles.noteInput}
                aria-label={t('business:counterPos.orderNotePlaceholder')}
                placeholder={t('business:counterPos.orderNotePlaceholder')}
                value={orderNote}
                onChange={(e) => setOrderNote(e.target.value)}
              />}

            </div>}
            {step === 4 && <div className={styles.checkoutBar}>
              {/* Giữ phần thanh toán cố định thật gọn; chỉ hiện các khoản giảm thực tế. */}
              <div className={styles.summaryBox}>
                {hasDiscount && (
                  <div className={styles.summaryLine}>
                    <span>{t('business:counterLoyalty.subtotal', { count: cartItems.reduce((a, b) => a + b.quantity, 0) })}</span>
                    <span>{formatVnd(Number(activeQuote?.subtotal ?? subtotal))}</span>
                  </div>
                )}
                {voucherDiscount > 0 && <div className={styles.summaryLine}>
                  <span>{t('business:counterLoyalty.voucherDiscount')}</span>
                  <span>-{formatVnd(voucherDiscount)}</span>
                </div>}
                {tierDiscount > 0 && <div className={styles.summaryLine}>
                  <span>{t('business:counterLoyalty.tierDiscount')}</span>
                  <span>-{formatVnd(tierDiscount)}</span>
                </div>}
                {pointsDiscount > 0 && <div className={styles.summaryLine}>
                  <span>{t('business:counterLoyalty.pointsDiscount')}</span>
                  <span>-{formatVnd(pointsDiscount)}</span>
                </div>}
                {Number(activeQuote?.pointsToEarn ?? 0) > 0 && <div className={styles.summaryLine}>
                  <span>{t('business:counterLoyalty.pointsToEarn')}</span>
                  <span>+{activeQuote?.pointsToEarn}</span>
                </div>}
                <div className={styles.summaryTotal}>
                  <span>{t('business:counterLoyalty.totalDue')}</span>
                  <span className={styles.totalNumber}>
                    {quoteBusy && !activeQuote ? t('business:counterLoyalty.awaitingQuote') : formatVnd(totalAmount)}
                  </span>
                </div>
                {noPayment && <p role="status" className={styles.paymentHint}>{t('business:counterPayment.noPaymentCard')}</p>}
              </div>

            </div>}
          </div>
        </div>

        <div className={styles.stepNavigation}>
          {step > 1 && <button type="button" className={styles.backButton}
            onClick={() => setStep((current) => Math.max(1, current - 1) as 1 | 2 | 3 | 4)}>{t('business:counterPos.back')}</button>}
          <span className={styles.navigationTotal}>
            {t(step < 3 ? 'business:counterPos.subtotal' : 'business:counterPos.total')}{' '}
            <strong>
              {step < 3
                ? formatVnd(subtotal)
                : quoteBusy && !activeQuote
                  ? t('business:counterLoyalty.awaitingQuote')
                  : formatVnd(totalAmount)}
            </strong>
          </span>
          {step < 4 && <button type="button" className={styles.nextButton}
            disabled={!cartItems.length || !storeOpenState.isOpen}
            onClick={() => setStep((current) => Math.min(4, current + 1) as 1 | 2 | 3 | 4)}>
            {t(step === 1 ? 'business:counterPos.nextOrderDetails' : step === 2 ? 'business:counterPos.nextLoyalty' : 'business:counterPos.nextPayment')}
          </button>}
          {step === 4 && <button type="button"
            disabled={cartItems.length === 0 || isSubmitting || !storeOpenState.isOpen
              || (!retryableAttempt && (!activeQuote || quoteBusy || !!quoteError))}
            className={styles.submitOrderBtn} onClick={handleSubmit}>
            {!storeOpenState.isOpen ? <><Lock size={16} />{t('business:storeStatus.closedNowChip')}</>
              : isSubmitting ? <><Loader2 size={18} className="animate-spin" />{t('business:counterPos.creating')}</>
              : t('business:counterPos.createOrder')}
          </button>}
        </div>

        {scanner && (
          <div className={styles.qrModalOverlay} role="dialog" aria-modal="true"
            aria-label={scanner === 'LOYALTY' ? t('business:counterLoyalty.scanLoyalty') : t('business:counterLoyalty.scanVoucher')}>
            <div className={styles.qrModalWindow}>
              <div className={styles.qrModalHeader}>
                <div>
                  <h3>{scanner === 'LOYALTY' ? t('business:counterLoyalty.scanLoyalty') : t('business:counterLoyalty.scanVoucher')}</h3>
                  <p>{t('business:counterLoyalty.scanInstruction')}</p>
                </div>
                <button type="button" className={styles.closeButton} onClick={() => setScanner(null)}
                  aria-label={t('business:counterLoyalty.closeScanner')}><X size={20} /></button>
              </div>
              <CounterQrScanner key={scanner} onDetected={handleQrDetected} onClose={() => setScanner(null)} />
            </div>
          </div>
        )}
        {/* Option Customizer Modal Overlay (Nếu mở chọn món) */}
        {customizingProduct && (
          <div className={styles.optionModalOverlay}>
            <div className={styles.optionModalWindow}>
              <div className={styles.optionHeader}>
                <h3 className={styles.optionTitle}>
                  Tùy chọn: {customizingProduct.name}
                </h3>
                <button
                  type="button"
                  className={styles.closeButton}
                  onClick={() => setCustomizingProduct(null)}
                >
                  <X size={18} />
                </button>
              </div>

              <div className={styles.optionBody}>
                {customizingProduct.optionGroups?.map((group) => {
                  const currentChoiceId = selectedChoices[group.id];
                  return (
                    <div key={group.id} className={styles.optionGroup}>
                      <span className={styles.optionGroupName}>
                        {group.id === "spice" && <Flame size={14} color="#8f1216" />}
                        {group.name}
                      </span>
                      <div className={styles.optionChips}>
                        {group.choices.map((choice) => {
                          const isSelected = currentChoiceId === choice.id;
                          return (
                            <button
                              key={choice.id}
                              type="button"
                              className={`${styles.optionChip} ${
                                isSelected ? styles.optionChipActive : ""
                              }`}
                              onClick={() =>
                                setSelectedChoices((prev) => ({
                                  ...prev,
                                  [group.id]: choice.id,
                                }))
                              }
                            >
                              {isSelected && <Check size={14} />}
                              <span>{choice.name}</span>
                              {choice.extraPrice > 0 && (
                                <span className={styles.optionExtraPrice}>
                                  +{formatVnd(choice.extraPrice)}
                                </span>
                              )}
                            </button>
                          );
                        })}
                      </div>
                    </div>
                  );
                })}

                {/* Chọn số lượng */}
                <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginTop: 8 }}>
                  <span style={{ fontWeight: 700, fontSize: 13, color: "#4b443c" }}>
                    Số lượng:
                  </span>
                  <div className={styles.qtyControls}>
                    <button
                      type="button"
                      className={styles.qtyBtn}
                      onClick={() => setCustomizingQty((q) => Math.max(1, q - 1))}
                    >
                      <Minus size={14} />
                    </button>
                    <span className={styles.qtyCount}>{customizingQty}</span>
                    <button
                      type="button"
                      className={styles.qtyBtn}
                      onClick={() => setCustomizingQty((q) => q + 1)}
                    >
                      <Plus size={14} />
                    </button>
                  </div>
                </div>
              </div>

              <div className={styles.optionFooter}>
                <div className={styles.optionFooterTotal}>
                  <span className={styles.optionFooterLabel}>Thành tiền:</span>
                  <span className={styles.optionFooterAmount}>
                    {formatVnd(
                      (customizingProduct.price +
                        Object.entries(selectedChoices).reduce((acc, [groupId, choiceId]) => {
                          const group = customizingProduct.optionGroups?.find((g) => g.id === groupId);
                          const choice = group?.choices.find((c) => c.id === choiceId);
                          return acc + (choice?.extraPrice || 0);
                        }, 0)) *
                        customizingQty,
                    )}
                  </span>
                </div>

                <button
                  type="button"
                  className={styles.addOptionBtn}
                  onClick={handleConfirmCustomOptions}
                >
                  Thêm vào giỏ hàng
                </button>
              </div>
            </div>
          </div>
        )}
      </fieldset>
    </div>
  );
};
