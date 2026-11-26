"use client";

import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { useLocale } from "next-intl";
import { Hero, type HeroProps } from "@/components/hero";
import { BannerShowcase } from "@/components/promotion/banner-showcase";
import { promotionApi } from "@/lib/api";
import { pickLocalized } from "@/lib/bilingual";
import type { PromoBanner } from "@/lib/types/promotion";

interface LiveHomePromotionsProps {
  initialBanners: PromoBanner[];
  heroProps: Omit<HeroProps, "heroBanners">;
  children?: ReactNode;
}

/**
 * Keeps home page banners synchronized with `/api/promotions/banners` across
 * initial SSR, client navigation, and tab focus returns after admin updates.
 */
export function LiveHomePromotions({
  initialBanners,
  heroProps,
  children,
}: LiveHomePromotionsProps) {
  const locale = useLocale();
  const [banners, setBanners] = useState<PromoBanner[]>(initialBanners);
  const lastFetchRef = useRef<number>(0);

  useEffect(() => {
    setBanners(initialBanners);
  }, [initialBanners]);

  const refreshBanners = useCallback(async (force = false) => {
    const now = Date.now();
    if (!force && now - lastFetchRef.current < 1000) {
      return;
    }
    lastFetchRef.current = now;
    try {
      const latest = await promotionApi.getActiveBanners(undefined, locale);
      if (Array.isArray(latest)) {
        setBanners(latest);
      }
    } catch {
      // Keep current SSR/cached banners if transient network failure occurs.
    }
  }, [locale]);

  useEffect(() => {
    void refreshBanners(true);

    const handleFocus = () => {
      void refreshBanners();
    };
    const handleVisibility = () => {
      if (document.visibilityState === "visible") {
        void refreshBanners();
      }
    };
    const handlePageShow = () => {
      void refreshBanners(true);
    };

    window.addEventListener("focus", handleFocus);
    window.addEventListener("pageshow", handlePageShow);
    document.addEventListener("visibilitychange", handleVisibility);
    return () => {
      window.removeEventListener("focus", handleFocus);
      window.removeEventListener("pageshow", handlePageShow);
      document.removeEventListener("visibilitychange", handleVisibility);
    };
  }, [refreshBanners]);

  const heroSlides = banners
    .filter((item) => item.position === "HERO_HOME")
    .map((item) => {
      const resolvedTitle = pickLocalized(item.titleI18n, item.title, locale);
      return {
        id: item.id,
        image: item.imageUrl,
        title: resolvedTitle,
        redirectUrl: item.linkUrl,
        alt: resolvedTitle,
      };
    });

  return (
    <>
      <Hero {...heroProps} heroBanners={heroSlides} />
      {children}
      <BannerShowcase banners={banners} showHero={false} />
    </>
  );
}
