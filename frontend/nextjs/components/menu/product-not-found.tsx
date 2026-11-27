"use client";

import { useTranslations } from "next-intl";
import Link from "@/components/ui-loader/navigation-link";

export function ProductNotFound() {
  const t = useTranslations();
  return (
    <main style={{ maxWidth: 1240, margin: "60px auto", padding: "0 24px", textAlign: "center" }}>
      <h1 style={{ fontFamily: "Baloo 2, sans-serif", fontSize: 28, color: "#16100D", marginBottom: 12 }}>
        {t("menu.empty.title")}
      </h1>
      <p style={{ fontFamily: "Quicksand, sans-serif", color: "#6B6259", marginBottom: 24 }}>
        {t("menu.empty.description")}
      </p>
      <Link href="/menu" className="btn-primary" style={{ display: "inline-flex", padding: "10px 24px" }}>
        {t("menu.empty.reset")}
      </Link>
    </main>
  );
}
