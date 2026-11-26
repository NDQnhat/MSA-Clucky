"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { QRCodeSVG } from "qrcode.react";
import { useTranslations } from "next-intl";
import { ArrowRight, CalendarDays, Check, CheckCheck, Copy, Gift, LockKeyhole, MapPin, RefreshCw, ShieldCheck, ShoppingBag, Sparkles, Tag, TicketPercent, X } from "lucide-react";
import { useSession } from "@/components/auth/use-session";
import { getSessionState } from "@/lib/session";
import { useLocaleTools } from "@/lib/i18n/hooks";
import { catalogApi, getErrorMessage, type StoreItem } from "@/lib/api";
import { pickLocalized } from "@/lib/bilingual";
import { personalVoucherApi, type PersonalVoucher, type VoucherCampaign } from "@/lib/personal-vouchers";
import Link from "@/components/ui-loader/navigation-link";
import styles from "./personal-vouchers.module.css";

type Tab = "discover" | "mine";

export default function VouchersPage() {
  const t = useTranslations("personalVouchers");
  const { money, date, locale } = useLocaleTools();
  const { ready, user } = useSession();
  const userId = user?.userId;
  const [tab, setTab] = useState<Tab>("discover");
  const [campaigns, setCampaigns] = useState<VoucherCampaign[]>([]);
  const [stores, setStores] = useState<StoreItem[]>([]);
  const [owned, setOwned] = useState<PersonalVoucher[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [busyId, setBusyId] = useState<number | null>(null);
  const [detail, setDetail] = useState<PersonalVoucher | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState("");
  const [copyState, setCopyState] = useState<"idle" | "copied" | "failed">("idle");
  const [legacyCopyState, setLegacyCopyState] = useState<{ campaignId: number; status: "copied" | "failed" } | null>(null);
  const detailEpoch = useRef(0);
  const closeRef = useRef<HTMLButtonElement>(null);
  const [version, setVersion] = useState(0);

  const closeDetail = useCallback(() => {
    detailEpoch.current += 1;
    setDetail(null);
    setDetailError("");
    setDetailLoading(false);
    setCopyState("idle");
  }, []);
  useEffect(() => {
    closeDetail();
    setOwned([]);
  }, [userId, closeDetail]);
  const detailOpen = Boolean(detail || detailLoading || detailError);
  useEffect(() => {
    if (!detailOpen) return;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    closeRef.current?.focus();
    const keydown = (event: KeyboardEvent) => { if (event.key === "Escape") closeDetail(); };
    document.addEventListener("keydown", keydown);
    return () => {
      document.body.style.overflow = previousOverflow;
      document.removeEventListener("keydown", keydown);
    };
  }, [detailOpen, closeDetail]);

  useEffect(() => {
    if (!ready) return;
    let active = true;
    setLoading(true);
    setError("");
    void Promise.allSettled([
      personalVoucherApi.campaigns(),
      userId ? personalVoucherApi.mine() : Promise.resolve([] as PersonalVoucher[]),
      catalogApi.getStores(false, locale).catch(() => [] as StoreItem[]),
    ]).then(([available, mine, locations]) => {
      if (!active) return;
      if (available.status === 'fulfilled') setCampaigns(available.value);
      else setError(getErrorMessage(available.reason));
      if (mine.status === 'fulfilled') setOwned(mine.value);
      else setError(getErrorMessage(mine.reason));
      if (locations.status === 'fulfilled') setStores(locations.value);
    })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [ready, userId, version, locale]);

  const mineByCampaign = useMemo(() => new Map(owned.map(item => [item.campaignId, item])), [owned]);
  const list = tab === "mine" ? owned.map(v => ({ campaign: v.campaign, voucher: v }))
    : campaigns.map(c => ({ campaign: c, voucher: mineByCampaign.get(c.id) }));

  const loadDetail = useCallback(async (instanceId: string) => {
    const ownerId = getSessionState().user?.userId;
    if (!ownerId) return;
    const epoch = ++detailEpoch.current;
    setDetail(null); setDetailError(""); setDetailLoading(true); setCopyState("idle");
    try {
      const result = await personalVoucherApi.detail(instanceId);
      if (epoch === detailEpoch.current && getSessionState().user?.userId === ownerId) setDetail(result);
    } catch (failure) {
      if (epoch === detailEpoch.current && getSessionState().user?.userId === ownerId) setDetailError(getErrorMessage(failure));
    } finally {
      if (epoch === detailEpoch.current) setDetailLoading(false);
    }
  }, []);

  async function claim(campaignId: number) {
    if (!user || !campaigns.some(campaign => campaign.id === campaignId && campaign.campaignKind === "PERSONAL")) return;
    const ownerId = user.userId;
    setBusyId(campaignId); setError("");
    try {
      const result = await personalVoucherApi.claim(campaignId);
      if (getSessionState().user?.userId !== ownerId) return;
      setOwned(previous => [result, ...previous.filter(item => item.instanceId !== result.instanceId)]);
      setTab("mine");
      await loadDetail(result.instanceId);
      // A temporary list failure must not hide a successfully claimed voucher.
      void personalVoucherApi.mine().then(mine => {
        if (getSessionState().user?.userId === ownerId) setOwned(mine);
      }).catch(() => {});
    } catch (failure) { setError(getErrorMessage(failure)); }
    finally { setBusyId(null); }
  }

  function discount(c: VoucherCampaign) {
    return c.type === "PERCENT" ? `${c.value}%` : money(c.value);
  }

  function progress(c: VoucherCampaign) {
    if (c.maxUsage == null || c.maxUsage <= 0) return null;
    return Math.min(100, Math.round(100 * (c.usage ?? 0) / c.maxUsage));
  }

  const canView = (v: PersonalVoucher) => v.state === "ISSUED";

  const storeScope = (campaign: VoucherCampaign) => (campaign.campaignKind === 'PERSONAL'
    ? campaign.applicability === 'ALL' : campaign.scope === 'ALL')
    ? t("allStores")
    : (campaign.campaignKind === 'PERSONAL' ? campaign.storeIds ?? [] : [campaign.storeId].filter((id): id is number => id != null))
      .map(id => {
        const matched = stores.find(store => Number(store.id) === Number(id));
        return matched ? pickLocalized(matched.nameI18n, matched.name, locale) : `#${id}`;
      }).join(", ");

  async function copyCode(code: string) {
    try {
      await navigator.clipboard.writeText(code);
      setCopyState("copied");
    } catch {
      setCopyState("failed");
    }
  }

  async function copyLegacyCode(campaignId: number, code: string) {
    try {
      await navigator.clipboard.writeText(code);
      setLegacyCopyState({ campaignId, status: "copied" });
    } catch {
      setLegacyCopyState({ campaignId, status: "failed" });
    }
  }

  return (
    <main className={styles.page}>
      <header className={styles.hero}>
        <div className={styles.heroContent}>
          <span className={styles.eyebrow}><Sparkles size={16} aria-hidden="true" /> Clucky Club <span className={styles.eyebrowDivider} /> {t("heroBadge")}</span>
          <h1>{t("title")}</h1>
          <p>{t("intro")}</p>
          <div className={styles.heroStats}>
            <div><strong>{campaigns.length}</strong><span>{t("availableOffers")}</span></div>
            <span className={styles.statDivider} aria-hidden="true" />
            <div><strong>{owned.filter(voucher => voucher.state === "ISSUED").length}</strong><span>{t("readyToUse")}</span></div>
          </div>
        </div>
        <div className={styles.heroVisual} aria-hidden="true">
          <div className={styles.heroTicket}>
            <div className={styles.heroTicketHeader}><span>CLUCKY CLUB</span><TicketPercent size={24} /></div>
            <div className={styles.heroTicketMain}><Gift size={37} strokeWidth={1.6} /><strong>{campaigns.length.toString().padStart(2, "0")}</strong><span>{t("heroTicketCaption")}</span></div>
            <div className={styles.heroTicketFoot}><span>CRISPY OUTSIDE.</span><span>HAPPY INSIDE.</span></div>
          </div>
          <div className={styles.heroSparkleOne}>✦</div><div className={styles.heroSparkleTwo}>✧</div>
        </div>
      </header>

      <div className={styles.contentGrid}>
        <section className={styles.listColumn} aria-label={t("tabs")}>
          <div className={styles.sectionHead}>
            <div>
              <span className={styles.sectionKicker}>{t("sectionKicker")}</span>
              <h2>{t("sectionTitle")}</h2>
            </div>
            <button type="button" className={styles.refreshButton} disabled={loading} aria-label={t("refresh")} onClick={() => setVersion(value => value + 1)}>
              <RefreshCw size={17} aria-hidden="true" /> <span>{t("refreshShort")}</span>
            </button>
          </div>
          <div className={styles.tabs} role="tablist" aria-label={t("tabs")}>
            <button type="button" role="tab" aria-selected={tab === "discover"} className={tab === "discover" ? styles.activeTab : ""}
              onClick={() => setTab("discover")}><Gift size={17} aria-hidden="true" />{t("discover")} <span className={styles.tabCount}>{campaigns.length}</span></button>
            <button type="button" role="tab" aria-selected={tab === "mine"} className={tab === "mine" ? styles.activeTab : ""}
              onClick={() => setTab("mine")}><TicketPercent size={17} aria-hidden="true" />{t("mine")} <span className={styles.tabCount}>{owned.length}</span></button>
          </div>

          {error && <div className={styles.error} role="alert"><span>{error}</span><button type="button" onClick={() => setVersion(value => value + 1)}>{t("retry")}</button></div>}
          {(loading || !ready) && <div role="status" className={styles.emptyState}><RefreshCw size={26} className={styles.spin} aria-hidden="true" /><h3>{t("loading")}</h3></div>}
          {!loading && ready && !user && <div className={styles.signInBanner}>
            <span className={styles.bannerIcon}><LockKeyhole size={21} aria-hidden="true" /></span>
            <div><strong>{t("signInTitle")}</strong><p>{t("signInDescription")}</p></div>
            <Link href="/login?redirect=/vouchers" className={styles.signInButton}>{t("signIn")} <ArrowRight size={16} aria-hidden="true" /></Link>
          </div>}
          {!loading && ready && list.length === 0 && <div className={styles.emptyState}>
            <span className={styles.emptyIcon}><TicketPercent size={32} strokeWidth={1.7} aria-hidden="true" /></span>
            <h3>{t(tab === "mine" ? "mineEmpty" : "discoverEmpty")}</h3>
            <p>{t(tab === "mine" ? "mineEmptyHint" : "discoverEmptyHint")}</p>
            {tab === "mine" && <button type="button" className={styles.secondaryButton} onClick={() => setTab("discover")}>{t("discover")} <ArrowRight size={16} aria-hidden="true" /></button>}
          </div>}

          {!loading && ready && list.length > 0 && <div className={styles.offerList}>
            {list.map(({ campaign, voucher }) => {
              const percent = progress(campaign);
              const inactive = Boolean(campaign.effectiveStatus && campaign.effectiveStatus !== "ACTIVE");
              const spent = voucher && voucher.state !== "ISSUED";
              return <article key={voucher?.instanceId ?? campaign.id} className={styles.card + (spent ? " " + styles.cardUnavailable : "")}>
                <div className={styles.ticketFace}>
                  <span className={styles.ticketFaceIcon}><TicketPercent size={23} aria-hidden="true" /></span>
                  <strong className={styles.discount}>{discount(campaign)}</strong>
                  <span className={styles.discountCaption}>{t("discountLabel")}</span>
                  <span className={styles.ticketSerial}>CLUCKY CLUB</span>
                </div>
                <div className={styles.cardContent}>
                  <div className={styles.cardTop}>
                    <span className={styles.cardCategory}><Sparkles size={14} aria-hidden="true" /> {t(campaign.campaignKind === 'PERSONAL' ? "cardCategory" : "publicCategory")}</span>
                    {voucher
                      ? <span className={styles.stateBadge + (voucher.state === "ISSUED" ? " " + styles.stateAvailable : "")}>{t("state." + voucher.state)}</span>
                      : inactive && <span className={styles.stateBadge}>{t("unavailable")}</span>}
                  </div>
                  <h3>{pickLocalized(campaign.titleI18n, campaign.title, locale)}</h3>
                  {pickLocalized(campaign.descriptionI18n, campaign.description, locale) && (
                    <p className={styles.description}>{pickLocalized(campaign.descriptionI18n, campaign.description, locale)}</p>
                  )}
                  <div className={styles.cardDetails}>
                    <span><ShoppingBag size={15} aria-hidden="true" />{t("minimum", { amount: money(campaign.minOrderAmount) })}</span>
                    <span><CalendarDays size={15} aria-hidden="true" />{t("validUntil", { date: date(voucher?.expiresAt ?? campaign.endAt, { dateStyle: "medium" }) })}</span>
                    <span title={storeScope(campaign)}><MapPin size={15} aria-hidden="true" />{storeScope(campaign)}</span>
                    {campaign.type === "PERCENT" && Number(campaign.maxDiscount) > 0 && <span><Tag size={15} aria-hidden="true" />{t("maxDiscount", { amount: money(campaign.maxDiscount!) })}</span>}
                  </div>
                  <div className={styles.cardBottom}>
                    <div className={styles.usage}>
                      <div className={styles.usageText}>
                        <span>{t("globalUsage")}</span>
                        <strong>{percent === null ? t("unlimited", { used: campaign.usage ?? 0 }) : t("usageProgress", { used: campaign.usage ?? 0, limit: campaign.maxUsage!, percent })}</strong>
                      </div>
                      {percent !== null && <div className={styles.track} role="progressbar" aria-label={t("globalUsage")}
                        aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100}><div style={{ width: percent + "%" }} /></div>}
                    </div>
                    <div className={styles.actions}>
                      {voucher
                        ? canView(voucher) && <button type="button" className={styles.primary} onClick={() => void loadDetail(voucher.instanceId)}>{t("viewCode")}<ArrowRight size={17} aria-hidden="true" /></button>
                        : campaign.campaignKind === "PERSONAL"
                          ? <button type="button" className={styles.primary} disabled={!user || inactive || busyId !== null}
                              onClick={() => void claim(campaign.id)}>{busyId === campaign.id ? t("claiming") : user ? t("claim") : t("signIn")}<ArrowRight size={17} aria-hidden="true" /></button>
                          : <div className={styles.legacyOffer}>
                              {campaign.code && <code className={styles.legacyCode}>{campaign.code}</code>}
                              {campaign.code && <button type="button" className={styles.secondaryButton} disabled={inactive}
                                onClick={() => void copyLegacyCode(campaign.id, campaign.code!)}>
                                {legacyCopyState?.campaignId === campaign.id && legacyCopyState.status === "copied"
                                  ? <CheckCheck size={16} aria-hidden="true" /> : <Copy size={16} aria-hidden="true" />}
                                {t(legacyCopyState?.campaignId === campaign.id && legacyCopyState.status === "copied" ? "copied" : "copy")}
                              </button>}
                              <span className={styles.legacyHint} role={legacyCopyState?.campaignId === campaign.id && legacyCopyState.status === "failed" ? "alert" : undefined}>
                                {legacyCopyState?.campaignId === campaign.id && legacyCopyState.status === "failed" ? t("copyFailed") : t("legacyAtCounter")}
                              </span>
                            </div>}
                    </div>
                  </div>
                </div>
              </article>;
            })}
          </div>}
        </section>

        <aside className={styles.sideColumn} aria-label={t("howTitle")}>
          <div className={styles.guideCard}>
            <div className={styles.guideIcon}><Gift size={25} aria-hidden="true" /></div>
            <span className={styles.guideKicker}>CLUCKY CLUB</span>
            <h2>{t("howTitle")}</h2>
            <p className={styles.guideIntro}>{t("howIntro")}</p>
            <ol className={styles.guideSteps}>
              <li><span>01</span><div><strong>{t("step1Title")}</strong><p>{t("step1Text")}</p></div></li>
              <li><span>02</span><div><strong>{t("step2Title")}</strong><p>{t("step2Text")}</p></div></li>
              <li><span>03</span><div><strong>{t("step3Title")}</strong><p>{t("step3Text")}</p></div></li>
            </ol>
          </div>
          <div className={styles.securityCard}><ShieldCheck size={20} aria-hidden="true" /><div><strong>{t("securityTitle")}</strong><p>{t("securityText")}</p></div></div>
        </aside>
      </div>

      {detailOpen && <div className={styles.overlay} role="presentation" onMouseDown={event => {
        if (event.target === event.currentTarget) closeDetail();
      }}>
        <section className={styles.modal} role="dialog" aria-modal="true" aria-labelledby="voucher-detail-heading">
          <button type="button" ref={closeRef} className={styles.close} aria-label={t("close")} onClick={closeDetail}><X size={21} aria-hidden="true" /></button>
          {detailLoading && <div className={styles.detailStatus} role="status"><RefreshCw className={styles.spin} size={28} aria-hidden="true" /><h2 id="voucher-detail-heading">{t("loading")}</h2></div>}
          {detailError && <div className={styles.detailStatus} role="alert"><h2 id="voucher-detail-heading">{t("detailTitle")}</h2><p className={styles.error}>{detailError}</p><button type="button" className={styles.secondaryButton} onClick={closeDetail}>{t("close")}</button></div>}
          {detail && canView(detail) && detail.code && <div className={styles.detailLayout}>
            <div className={styles.detailInfo}>
              <div className={styles.detailBrand}><span className={styles.detailMark}><Gift size={21} aria-hidden="true" /></span> Clucky Club</div>
              <span className={styles.detailEyebrow}>{t("detailTitle")}</span>
              <strong className={styles.detailDiscount}>{discount(detail.campaign)}</strong>
              <h2 id="voucher-detail-heading">{pickLocalized(detail.campaign.titleI18n, detail.campaign.title, locale)}</h2>
              {pickLocalized(detail.campaign.descriptionI18n, detail.campaign.description, locale) && (
                <p className={styles.detailDescription}>{pickLocalized(detail.campaign.descriptionI18n, detail.campaign.description, locale)}</p>
              )}
              <div className={styles.detailFacts}>
                <div><ShoppingBag size={18} aria-hidden="true" /><span>{t("minimum", { amount: money(detail.campaign.minOrderAmount) })}</span></div>
                <div><CalendarDays size={18} aria-hidden="true" /><span>{t("validUntil", { date: date(detail.expiresAt ?? detail.campaign.endAt, { dateStyle: "medium" }) })}</span></div>
                <div><MapPin size={18} aria-hidden="true" /><span>{storeScope(detail.campaign)}</span></div>
                {detail.campaign.type === "PERCENT" && Number(detail.campaign.maxDiscount) > 0 && <div><Tag size={18} aria-hidden="true" /><span>{t("maxDiscount", { amount: money(detail.campaign.maxDiscount!) })}</span></div>}
              </div>
              <div className={styles.detailNote}><Check size={18} aria-hidden="true" /><span>{t("state.ISSUED")}</span></div>
            </div>
            <div className={styles.detailQrColumn}>
              <span className={styles.qrEyebrow}><span className={styles.liveDot} />{t("readyToScan")}</span>
              <h3>{t("presentCode")}</h3>
              <div className={styles.qr}><QRCodeSVG size={228} level="M" marginSize={2} title={t("qrTitle")}
                value={detail.qrPayload ?? ("CLUCKY:V1:" + detail.code)} fgColor="#16100D" /></div>
              <span className={styles.codeLabel}>{t("yourCode")}</span>
              <strong className={styles.bearerCode}>{detail.code}</strong>
              <button type="button" className={styles.copyButton} onClick={() => void copyCode(detail.code!)}>
                {copyState === "copied" ? <CheckCheck size={18} aria-hidden="true" /> : <Copy size={18} aria-hidden="true" />}
                {t(copyState === "copied" ? "copied" : "copy")}
              </button>
              {copyState === "failed" && <p role="alert" className={styles.copyError}>{t("copyFailed")}</p>}
              <p className={styles.warning}><LockKeyhole size={15} aria-hidden="true" />{t("bearerNotice")}</p>
            </div>
          </div>}
          {detail && (!canView(detail) || !detail.code) && <div className={styles.detailStatus}>
            <TicketPercent size={38} aria-hidden="true" />
            <h2 id="voucher-detail-heading">{pickLocalized(detail.campaign.titleI18n, detail.campaign.title, locale)}</h2>
            <p>{canView(detail) ? t("codeUnavailable") : t("state." + detail.state)}</p>
          </div>}
        </section>
      </div>}
    </main>
  );
}
