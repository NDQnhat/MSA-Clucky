"use client";

import { useEffect, useState } from "react";
import { Gift, RefreshCw, ShieldCheck } from "lucide-react";
import { useTranslations } from "next-intl";
import Link from "@/components/ui-loader/navigation-link";
import { guestPointsApi, type GuestPointsClaim } from "@/lib/api";
import { Modal } from "@/components/ui/modal";

export function GuestPointsClaimPanel({ refreshKey = 0 }: { refreshKey?: number }) {
  const t = useTranslations("auth.security");
  const [preview, setPreview] = useState<GuestPointsClaim | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [showConfirm, setShowConfirm] = useState(false);
  const [error, setError] = useState(false);
  const [claimed, setClaimed] = useState<number | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(false);
    setPreview(null);
    setClaimed(null);
    void guestPointsApi.preview().then(result => {
      if (!active) return;
      setPreview(result);
    }).catch(() => {
      if (active) setError(true);
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [refreshKey, reload]);

  async function confirmClaim() {
    if (!preview?.pending || busy) return;
    setBusy(true);
    setError(false);
    try {
      const result = await guestPointsApi.confirm();
      setPreview(result);
      setClaimed(result.pointsTransferred);
      setShowConfirm(false);
    } catch {
      setError(true);
    } finally {
      setBusy(false);
    }
  }

  const points = (value: number) => Math.max(0, value).toLocaleString();

  return (
    <section id="account-guest-points" aria-label={t("guestPointsTitle")}
      className="my-6 rounded-2xl border border-[#E6D9C7] bg-[#FFF5E8] p-5 sm:p-6">
      <div className="mb-3 flex items-start gap-3">
        <span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-[#F4DFB7] text-[#8F1216]">
          <Gift size={22} aria-hidden="true" />
        </span>
        <div>
          <h3 className="font-['Fredoka',sans-serif] text-lg font-semibold text-[#241510]">{t("guestPointsTitle")}</h3>
          <p className="mt-1 text-sm leading-6 text-[#625247]">{t("guestPointsDescription")}</p>
        </div>
      </div>

      {loading && <p role="status" className="py-2 text-sm text-[#75675E]">{t("guestPointsLoading")}</p>}
      {!loading && claimed !== null && (
        <div role="status" className="my-3 rounded-xl bg-[#EDF5EA] p-4 text-sm font-semibold text-[#27663F]">
          {claimed > 0 ? t("guestPointsClaimed", { points: points(claimed) }) : t("guestPointsAlreadyClaimed")}
          <Link href="/loyalty" className="ml-2 underline">{t("guestPointsWallet")}</Link>
        </div>
      )}
      {!loading && preview && (
        <>
          <dl className="grid gap-2 rounded-xl border border-[#E8D6BC] bg-white p-4 text-sm text-[#625247]">
            <div className="flex items-center justify-between gap-3"><dt>{t("guestPointsCurrent")}</dt><dd className="font-bold text-[#241510]">{points(preview.currentPoints)}</dd></div>
            <div className="flex items-center justify-between gap-3"><dt>{t("guestPointsPhone")}</dt><dd className="font-bold text-[#241510]">+{points(preview.phonePoints)}</dd></div>
            <div className="flex items-center justify-between gap-3"><dt>{t("guestPointsEmail")}</dt><dd className="font-bold text-[#241510]">+{points(preview.emailPoints)}</dd></div>
            <div className="flex items-center justify-between gap-3 border-t border-[#E8D6BC] pt-3 font-bold text-[#241510]"><dt>{t("guestPointsTotal")}</dt><dd>{points(preview.totalPoints)}</dd></div>
          </dl>
          {preview.pending ? (
            <button type="button" onClick={() => setShowConfirm(true)}
              className="mt-4 w-full rounded-xl bg-[#8F1216] px-5 py-3 text-sm font-bold text-white hover:bg-[#6D0E11] disabled:opacity-50"
              disabled={busy}>
              {t("guestPointsReview")}
            </button>
          ) : claimed === null ? (
            <p role="status" className="mt-3 text-sm text-[#75675E]">{t("guestPointsNone")}</p>
          ) : null}
        </>
      )}

      {error && <div role="alert" className="mt-3 text-sm text-[#A42624]">
        {t("guestPointsError")}
      </div>}
      {!loading && <button type="button" onClick={() => setReload(value => value + 1)}
        disabled={busy} className="mt-3 inline-flex items-center gap-2 text-sm font-semibold text-[#8F1216] underline disabled:opacity-50">
        <RefreshCw size={14} aria-hidden="true" />{t("guestPointsRefresh")}
      </button>}

      <Modal open={showConfirm} onOpenChange={open => { if (!busy) setShowConfirm(open); }}
        title={t("guestPointsConfirmTitle")} description={t("guestPointsConfirmDescription")}>
        {preview && (
          <>
            <p className="mb-4 flex items-center gap-2 rounded-xl bg-[#EDF5EA] p-3 text-sm text-[#27663F]">
              <ShieldCheck size={18} aria-hidden="true" />{t("guestPointsVerifiedOnly")}
            </p>
            <dl className="grid gap-3 rounded-xl border border-[#E8D6BC] p-4 text-sm text-[#625247]">
              <div className="flex justify-between gap-3"><dt>{t("guestPointsCurrent")}</dt><dd className="font-bold">{points(preview.currentPoints)}</dd></div>
              <div className="flex justify-between gap-3"><dt>{t("guestPointsIncoming")}</dt><dd className="font-bold">+{points(preview.phonePoints + preview.emailPoints)}</dd></div>
              <div className="flex justify-between gap-3 border-t border-[#E8D6BC] pt-3 font-bold"><dt>{t("guestPointsTotal")}</dt><dd>{points(preview.totalPoints)}</dd></div>
            </dl>
            {error && <p className="mt-3 text-sm text-[#A42624]" role="alert">{t("guestPointsError")}</p>}
            <div className="mt-5 flex justify-end gap-3">
              <button type="button" disabled={busy} onClick={() => setShowConfirm(false)}
                className="rounded-xl border border-[#D9C7B1] px-4 py-3 text-sm font-bold text-[#625247] disabled:opacity-50">
                {t("guestPointsLater")}
              </button>
              <button type="button" disabled={busy} onClick={() => void confirmClaim()}
                className="rounded-xl bg-[#8F1216] px-4 py-3 text-sm font-bold text-white disabled:opacity-50">
                {busy ? t("guestPointsWorking") : t("guestPointsConfirm")}
              </button>
            </div>
          </>
        )}
      </Modal>
    </section>
  );
}
