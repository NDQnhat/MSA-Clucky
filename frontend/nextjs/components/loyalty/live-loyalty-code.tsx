"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { QRCodeSVG } from "qrcode.react";
import { useTranslations } from "next-intl";
import { Copy, RefreshCw, ShieldCheck, Timer } from "lucide-react";
import { loyaltyApi, type LoyaltyLiveCode } from "@/lib/loyalty";
import { onBeforeLogout } from "@/lib/session";
import styles from "./live-loyalty-code.module.css";

/** One page-mounted Customer session. Codes are never cached or persisted. */
export function LiveLoyaltyCode({ customerId }: { customerId: number }) {
  const t = useTranslations("loyalty");
  const [current, setCurrent] = useState<LoyaltyLiveCode | null>(null);
  const [remaining, setRemaining] = useState(0);
  const [visible, setVisible] = useState(false);
  const [error, setError] = useState(false);
  const codeRef = useRef<LoyaltyLiveCode | null>(null);
  const timeOffsetRef = useRef(0);
  const generationRef = useRef(0);
  const requestRef = useRef(false);
  const pendingOpenRef = useRef<Promise<LoyaltyLiveCode> | null>(null);
  const rotatedRef = useRef<string | null>(null);

  const setCode = useCallback((value: LoyaltyLiveCode | null) => {
    codeRef.current = value;
    if (value) {
      // Align expiration with the server clock even when the device clock differs.
      timeOffsetRef.current = Date.parse(value.serverTime) - Date.now();
      rotatedRef.current = null;
    }
    setCurrent(value);
    setRemaining(value ? Math.max(0, Math.ceil((Date.parse(value.expiresAt) - (Date.now() + timeOffsetRef.current)) / 1000)) : 0);
  }, []);

  useEffect(() => {
    let active = true;
    const close = (sessionId: string) => { void loyaltyApi.close(sessionId).catch(() => {}); };
    const clear = () => {
      generationRef.current += 1;
      const existing = codeRef.current;
      setCode(null);
      setError(false);
      if (existing) close(existing.sessionId);
    };
    const open = async () => {
      if (requestRef.current || !active) return;
      requestRef.current = true;
      const generation = ++generationRef.current;
      setError(false);
      const pending = loyaltyApi.open();
      pendingOpenRef.current = pending;
      try {
        const result = await pending;
        if (active && generation === generationRef.current) {
          setCode(result);
        } else {
          close(result.sessionId);
        }
      } catch {
        if (active && generation === generationRef.current) setError(true);
      } finally {
        if (pendingOpenRef.current === pending) pendingOpenRef.current = null;
        requestRef.current = false;
        // A tab can be hidden and shown while an earlier open is still pending.
        if (active && generation !== generationRef.current
            && !codeRef.current) queueMicrotask(() => { void open(); });
      }
    };
    const onVisibility = () => {
      setVisible(!document.hidden);
      // Switching to another browser tab (such as Staff POS) must not revoke
      // a QR the cashier is about to scan. Only leaving this page/logout closes it.
      if (!document.hidden && !codeRef.current) void open();
    };
    const onPageHide = () => clear();
    document.addEventListener("visibilitychange", onVisibility);
    window.addEventListener("pagehide", onPageHide);
    window.addEventListener("pageshow", onVisibility);
    const unsubscribeLogout = onBeforeLogout(async () => {
      let existing = codeRef.current;
      generationRef.current += 1;
      setCode(null);
      // A logout can race with the first open response. Wait briefly for its
      // server-issued session ID so that it can be revoked before logout.
      if (!existing && pendingOpenRef.current) {
        try {
          existing = await Promise.race<LoyaltyLiveCode | null>([
            pendingOpenRef.current,
            new Promise<null>(resolve => window.setTimeout(() => resolve(null), 1500)),
          ]);
        } catch {}
      }
      if (!existing) return;
      // Use a direct cookie-authenticated request; logout holds the session
      // lock while awaiting cleanup, so refresh-on-401 must not be involved.
      await fetch(`${process.env.NEXT_PUBLIC_API_URL ?? ""}/api/loyalty/live-session/close`, {
        method: "POST",
        credentials: "include",
        cache: "no-store",
        keepalive: true,
        headers: { "Content-Type": "application/json", "X-Clucky-Client": "customer" },
        signal: AbortSignal.timeout(2000),
        body: JSON.stringify({ sessionId: existing.sessionId }),
      });
    });
    setVisible(!document.hidden);
    if (!document.hidden) void open();
    const timer = window.setInterval(() => {
      if (!active) return;
      const value = codeRef.current;
      if (!value) return;
      const now = Date.now() + timeOffsetRef.current;
      const seconds = Math.max(0, Math.ceil((Date.parse(value.expiresAt) - now) / 1000));
      setRemaining(seconds);
      if (seconds === 0 && rotatedRef.current !== value.issuedAt && !requestRef.current) {
        rotatedRef.current = value.issuedAt;
        requestRef.current = true;
        const generation = generationRef.current;
        // Immediately hide the expired QR; never show a code past its validity.
        setCurrent(null);
        loyaltyApi.rotate(value.sessionId).then(result => {
          if (active && generation === generationRef.current) setCode(result);
          else close(result.sessionId);
        }).catch(() => {
          if (active && generation === generationRef.current) {
            setCode(null);
            close(value.sessionId);
            // Background tabs may throttle timers and let the server lease
            // expire; automatically obtain a fresh session on the next tick.
          }
        }).finally(() => {
          requestRef.current = false;
          if (active && generation === generationRef.current && !codeRef.current) void open();
        });
      }
    }, 250);
    const heartbeat = window.setInterval(() => {
      const sessionId = codeRef.current?.sessionId;
      if (!active || !sessionId) return;
      void loyaltyApi.heartbeat(sessionId).catch(() => {
        if (codeRef.current?.sessionId !== sessionId || !active) return;
        clear();
        setError(true);
      });
    }, 3000);
    return () => {
      active = false;
      window.clearInterval(timer);
      window.clearInterval(heartbeat);
      document.removeEventListener("visibilitychange", onVisibility);
      window.removeEventListener("pagehide", onPageHide);
      window.removeEventListener("pageshow", onVisibility);
      unsubscribeLogout();
      clear();
    };
  }, [customerId, setCode]);

  const validitySeconds = current ? (Date.parse(current.expiresAt) - Date.parse(current.issuedAt)) / 1000 : 0;
  const timeProgress = validitySeconds > 0 ? Math.min(100, remaining / validitySeconds * 100) : 0;

  return <section className={styles.panel} aria-labelledby="live-loyalty-title">
    <div className={styles.heading}>
      <span className={styles.icon}><ShieldCheck size={22} aria-hidden="true" /></span>
      <div><h2 id="live-loyalty-title">{t("liveTitle")}</h2><p>{t("liveDescription")}</p></div>
    </div>
    {!visible ? <p className={styles.notice} role="status">{t("liveHidden")}</p> :
      current && remaining > 0 ? <div className={styles.content}>
        <div className={styles.qr}><QRCodeSVG value={current.loyaltyQrToken} size={208} level="M" marginSize={2} title={t("liveQrLabel")} /></div>
        <div className={styles.details}>
          <span className={styles.label}>{t("liveTextLabel")}</span>
          <div className={styles.code}>{current.loyaltyTextCode}</div>
          <p className={styles.hint}>{t("liveTextHint")}</p>
          <button type="button" className={styles.copy} onClick={() => { void navigator.clipboard.writeText(current.loyaltyTextCode); }}>{t("liveCopy")} <Copy size={15} aria-hidden="true" /></button>
          <p className={styles.timer} role="timer"><Timer size={18} aria-hidden="true" />{t("liveExpiry", { seconds: remaining })}</p>
          <div className={styles.bar}><div style={{ width: `${timeProgress}%` }} /></div>
          <p className={styles.hint}>{t("liveQrHint")}</p>
        </div>
      </div> : <div className={styles.notice} role="status">
        <p>{error ? t("liveError") : t("liveOpening")}</p>
        {error && <button type="button" onClick={() => window.location.reload()}><RefreshCw size={16} />{t("retry")}</button>}
      </div>}
  </section>;
}
