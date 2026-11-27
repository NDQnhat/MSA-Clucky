"use client";

import { useLocale, useTranslations } from "next-intl";
import Link from "@/components/ui-loader/navigation-link";
import { LiveHomePromotions } from "@/components/promotion/live-home-promotions";
import { HomeReorderNudge } from "@/components/home/home-reorder-nudge";
import { ProductCard } from "@/components/product-card";
import { CategoryIcon } from "@/components/category-icon";
import { pickLocalized, type LocalizedText } from "@/lib/bilingual";
import type { ProductItem } from "@/lib/catalog-and-stores.api";
import type { PromoBanner } from "@/lib/types/promotion";
import { Flame, Award, Truck, TicketPercent, MapPin, ArrowRight } from "lucide-react";

export interface HomeCategorySummary {
  id: number;
  name: string;
  nameI18n?: LocalizedText | null;
}

interface HomePageContentProps {
  initialBanners: PromoBanner[];
  categories: HomeCategorySummary[];
  featuredCombos: ProductItem[];
  featuredProducts: ProductItem[];
  comboCategoryId: number;
  availableVoucherCount: number;
  openStoreCount: number;
}

function SectionHeading({
  eyebrow,
  title,
  href,
  linkLabel,
}: {
  eyebrow: string;
  title: string;
  href: string;
  linkLabel: string;
}) {
  return (
    <div className="home-section-header">
      <div>
        <span className="home-section-eyebrow">{eyebrow}</span>
        <h2 className="home-section-title">{title}</h2>
      </div>
      <Link href={href} className="home-section-link">
        {linkLabel}
      </Link>
    </div>
  );
}

export function HomePageContent({
  initialBanners,
  categories,
  featuredCombos,
  featuredProducts,
  comboCategoryId,
  availableVoucherCount,
  openStoreCount,
}: HomePageContentProps) {
  const t = useTranslations();
  const locale = useLocale();

  return (
    <main>
      <LiveHomePromotions
        initialBanners={initialBanners}
        heroProps={{
          eyebrow: t("home.hero.eyebrow"),
          titleTop: t("home.hero.titleTop"),
          titleBottom: t("home.hero.titleBottom"),
          description: t("home.hero.description"),
          actions: [
            { href: "/menu", label: t("home.hero.order"), variant: "primary" },
            { href: "/menu", label: t("home.hero.menu"), variant: "secondary" },
          ],
          imageSrc: "/images/hero-img.avif",
          imageAlt: t("home.hero.imageAlt"),
        }}
      >
        <section className="category-row-section" aria-label={t("menu.categories.label")}>
          <div className="category-dock">
            <Link href="/menu" className="category-pill active">
              <CategoryIcon id={0} size={18} active />
              <span>{t("menu.categories.all")}</span>
            </Link>
            {categories.map((category) => {
              const categoryName = pickLocalized(category.nameI18n, category.name, locale);
              return (
                <Link key={category.id} href={`/menu?cat=${category.id}`} className="category-pill">
                  <CategoryIcon id={category.id} name={categoryName} size={18} />
                  <span>{categoryName}</span>
                </Link>
              );
            })}
          </div>
        </section>
      </LiveHomePromotions>

      <HomeReorderNudge />

      {featuredCombos.length > 0 && (
        <section className="home-section" aria-label={t("home.combosTitle")}>
          <SectionHeading
            eyebrow={t("home.combosEyebrow")}
            title={t("home.combosTitle")}
            href={`/menu?cat=${comboCategoryId}`}
            linkLabel={t("home.allCombos")}
          />
          <div className="home-featured-grid">
            {featuredCombos.map((combo) => (
              <ProductCard key={combo.slug} product={combo} />
            ))}
          </div>
        </section>
      )}

      <section className="home-section" aria-label={t("home.favourites")}>
        <SectionHeading
          eyebrow={t("menu.title")}
          title={t("home.favourites")}
          href="/menu"
          linkLabel={t("home.fullMenu")}
        />
        <div className="home-featured-grid">
          {featuredProducts.map((product) => (
            <ProductCard key={product.slug} product={product} />
          ))}
        </div>
      </section>

      <section className="home-cta-strip" aria-label={t("home.strip.vouchersTitle")}>
        <Link href="/vouchers" className="home-cta-card">
          <div className="home-cta-main">
            <div className="home-cta-icon" style={{ color: "#8F1216" }}>
              <TicketPercent size={22} />
            </div>
            <div>
              <h3>{t("home.strip.vouchersTitle")}</h3>
              <p>
                {availableVoucherCount > 0
                  ? t("home.strip.vouchersDescAvailable", { count: availableVoucherCount })
                  : t("home.strip.vouchersDescDefault")}
              </p>
            </div>
          </div>
          <span className="home-cta-action">
            {t("home.strip.vouchersCta")}
            <ArrowRight size={16} aria-hidden="true" />
          </span>
        </Link>

        <Link href="/stores" className="home-cta-card">
          <div className="home-cta-main">
            <div className="home-cta-icon" style={{ color: "#1E7A46" }}>
              <MapPin size={22} />
            </div>
            <div>
              <h3>{t("home.strip.storesTitle")}</h3>
              <p>
                {openStoreCount > 0
                  ? t("home.strip.storesDescOpen", { count: openStoreCount })
                  : t("home.strip.storesDescDefault")}
              </p>
            </div>
          </div>
          <span className="home-cta-action">
            {t("home.strip.storesCta")}
            <ArrowRight size={16} aria-hidden="true" />
          </span>
        </Link>
      </section>

      <section className="brand-promise" aria-label={t("home.promise.label")}>
        <div className="promise-card">
          <div className="promise-icon" style={{ color: "#8F1216" }}>
            <Flame size={28} />
          </div>
          <h3>{t("home.promise.crispyTitle")}</h3>
          <p>{t("home.promise.crispyDescription")}</p>
        </div>

        <div className="promise-card">
          <div className="promise-icon" style={{ color: "#C9A227" }}>
            <Award size={28} />
          </div>
          <h3>{t("home.promise.recipeTitle")}</h3>
          <p>{t("home.promise.recipeDescription")}</p>
        </div>

        <div className="promise-card">
          <div className="promise-icon" style={{ color: "#1E7A46" }}>
            <Truck size={28} />
          </div>
          <h3>{t("home.promise.deliveryTitle")}</h3>
          <p>{t("home.promise.deliveryDescription")}</p>
        </div>
      </section>
    </main>
  );
}
