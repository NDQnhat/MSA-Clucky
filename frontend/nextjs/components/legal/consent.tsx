"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { legalApi, type LegalConsentInput, type PublicLegalDocument } from "@/lib/api";
import { consentForPublished, isValidLegalStatus } from "@/lib/legal-consent";

export function useLegalConsent() {
  const [terms, setTerms] = useState<PublicLegalDocument | null>(null);
  const [privacy, setPrivacy] = useState<PublicLegalDocument | null>(null);
  const [accepted, setAccepted] = useState(false);
  const [required, setRequired] = useState<boolean | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const requestId = useRef(0);
  const refresh = useCallback(async () => {
    const id = ++requestId.current;
    setLoading(true);
    setAccepted(false);
    setRequired(null);
    setTerms(null);
    setPrivacy(null);
    setError(false);
    try {
      const status = await legalApi.status();
      if (!isValidLegalStatus(status)) throw new Error('Invalid public legal status');
      const [termsVersion, privacyVersion] = await Promise.all([
        status.termsPublished ? legalApi.published("TERMS_OF_SERVICE") : Promise.resolve(null),
        status.privacyPublished ? legalApi.published("PRIVACY_POLICY") : Promise.resolve(null),
      ]);
      // If an admin republishes between the status and document calls, the
      // server checks versions again at registration time.
      if (Boolean(termsVersion) !== status.termsPublished
        || Boolean(privacyVersion) !== status.privacyPublished
        || (status.consentRequired && !consentForPublished(termsVersion, privacyVersion, true))) {
        throw new Error('Invalid published legal revision');
      }
      if (id !== requestId.current) return;
      setTerms(termsVersion);
      setPrivacy(privacyVersion);
      setRequired(status.consentRequired);
    } catch {
      if (id === requestId.current) setError(true);
    } finally {
      if (id === requestId.current) setLoading(false);
    }
  }, []);
  useEffect(() => {
    const requests = requestId;
    void refresh();
    return () => { requests.current++; };
  }, [refresh]);
  const consent: LegalConsentInput | null = !loading && !error && required
    ? consentForPublished(terms, privacy, accepted) : null;
  const canProceed = !loading && !error && required !== null && (!required || consent !== null);
  return { terms, privacy, accepted, setAccepted, loading, error, required, consent, canProceed, refresh };
}

type ConsentState = ReturnType<typeof useLegalConsent>;
export function ConsentField({ state, id }: { state: ConsentState; id: string }) {
  const t = useTranslations("legal");
  if (!state.loading && !state.error && !state.required) return null;
  return <div className="my-4 rounded-xl border border-[#E8E2D9] bg-[#FFFAF3] p-4 text-sm leading-relaxed text-[#41352D]">
    {state.loading ? <p role="status">{t("loading")}</p> : state.error ? (
      <div role="alert" className="text-[#8F1216]">
        {t("unavailable")}{' '}
        <button type="button" onClick={() => void state.refresh()} className="underline">{t("reload")}</button>
      </div>
    ) : <div className="flex items-start gap-3">
      <input id={id} type="checkbox" checked={state.accepted} onChange={e => state.setAccepted(e.target.checked)}
        required className="mt-1 h-4 w-4 shrink-0 accent-[#8F1216]" />
      <div>
        <label htmlFor={id} className="cursor-pointer font-semibold">{t("consentPrompt")}</label>{' '}
        {state.terms && <a href="/terms-of-service" target="_blank" rel="noopener noreferrer" className="font-bold text-[#8F1216] underline">{t("terms")}</a>}
        {state.terms && state.privacy && <> {t("and")} </>}
        {state.privacy && <a href="/privacy-policy" target="_blank" rel="noopener noreferrer" className="font-bold text-[#8F1216] underline">{t("privacy")}</a>}
      </div>
    </div>}
  </div>;
}
