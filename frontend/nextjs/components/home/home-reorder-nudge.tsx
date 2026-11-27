"use client";

import { useMemo, useState } from "react";
import { useTranslations } from "next-intl";
import { RotateCcw, ArrowRight } from "lucide-react";
import { useSession } from "@/components/auth/use-session";
import { useOrder } from "@/components/order/order-provider";
import { useNavigationRouter as useRouter } from "@/components/ui-loader/use-navigation-router";
import { notify } from "@/components/feedback";
import { getErrorMessage } from "@/lib/api";

export function HomeReorderNudge() {
  const t = useTranslations("home.reorder");
  const { user } = useSession();
  const { orders, reorder } = useOrder();
  const router = useRouter();
  const [busy, setBusy] = useState(false);

  const latestCompleted = useMemo(
    () => orders.find((order) => order.status === "COMPLETED") ?? null,
    [orders]
  );

  if (!user || !latestCompleted) {
    return null;
  }

  const handleReorder = async () => {
    if (busy) return;
    setBusy(true);
    try {
      await reorder(latestCompleted);
      notify.success(t("addedToCart", { code: latestCompleted.orderCode }));
      router.push("/cart");
    } catch (error) {
      notify.error(getErrorMessage(error));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="home-reorder-nudge" aria-label={t("ariaLabel")}>
      <div className="home-reorder-inner">
        <div className="home-reorder-copy">
          <RotateCcw size={16} className="text-[#8F1216]" aria-hidden="true" />
          <span>
            {t("prompt", {
              code: latestCompleted.orderCode,
              store: latestCompleted.storeName,
            })}
          </span>
        </div>
        <button
          type="button"
          className="home-reorder-btn"
          onClick={() => void handleReorder()}
          disabled={busy}
        >
          <span>{busy ? t("reordering") : t("action")}</span>
          <ArrowRight size={15} aria-hidden="true" />
        </button>
      </div>
    </section>
  );
}
