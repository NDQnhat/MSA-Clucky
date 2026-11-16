import { getTranslations } from "next-intl/server";
import Link from "@/components/ui-loader/navigation-link";
import { LiveHomePromotions } from "@/components/promotion/live-home-promotions";
import { ProductCard } from "@/components/product-card";
import { CategoryIcon } from "@/components/category-icon";
import { catalogApi, type CategoryItem } from "@/lib/catalog-and-stores.api";
import { promotionApi } from "@/lib/api";
import type { PromoBanner } from "@/lib/types/promotion";
import { Flame, Award, Truck } from "lucide-react";

export const dynamic = 'force-dynamic';

export default async function Home() {
  const t = await getTranslations();
  let displayProducts: any[] = [];
  let activeBanners: PromoBanner[] = [];
  let categories: CategoryItem[] = [];

  const [prods, banners, liveCategories] = await Promise.all([
    catalogApi.getProducts({ activeOnly: true, page: 0, size: 9 }).catch(() => []),
    promotionApi.getActiveBanners().catch(() => [] as PromoBanner[]),
    catalogApi.getCategories(true).catch(() => [] as CategoryItem[]),
  ]);
  displayProducts = prods;
  activeBanners = banners;
  categories = liveCategories
    .filter((category) => category.isActive !== false)
    .sort((a, b) => a.sortOrder - b.sortOrder);

  const featuredProducts = displayProducts
    .filter((p) => p.isActive !== false)
    .slice(0, 9);

  return (
    <>
      <main>
        {/* Hero Section + Live Synchronized Promotional Banners */}
        <LiveHomePromotions
          initialBanners={activeBanners}
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
          {/* Browse categories from the same catalog API used by the menu. */}
          <section className="category-row-section" aria-label={t("menu.categories.label")}>
            <div className="category-dock">
              <Link href="/menu" className="category-pill active">
                <CategoryIcon id={0} size={18} active />
                <span>{t("menu.categories.all")}</span>
              </Link>
              {categories.map((category) => (
                <Link key={category.id} href={`/menu?cat=${category.id}`} className="category-pill">
                  <CategoryIcon id={category.id} name={category.name} size={18} />
                  <span>{category.name}</span>
                </Link>
              ))}
            </div>
          </section>
        </LiveHomePromotions>

        {/* Intro */}
        <section
          style={{
            width: "min(1240px, calc(100% - 64px))",
            margin: "0 auto 40px",
            display: "flex",
            justifyContent: "space-between",
            alignItems: "flex-end",
            flexWrap: "wrap",
            gap: 24,
          }}
        >
          <div>
            <span
              style={{
                color: "#8F1216",
                fontFamily: "Baloo 2, sans-serif",
                fontWeight: 600,
                fontSize: 14,
                letterSpacing: "0.12em",
                textTransform: "uppercase",
              }}
            >
              {t("menu.title")}{" "}</span>
            <h2
              style={{
                fontFamily: "Baloo 2, sans-serif",
                fontWeight: 700,
                fontSize: "clamp(32px, 4vw, 46px)",
                color: "#16100D",
                margin: "6px 0 0",
                letterSpacing: "-0.03em",
              }}
            >
              {t("home.favourites")}{" "}</h2>
          </div>
          <Link
            href="/menu"
            style={{
              color: "#8F1216",
              fontFamily: "Quicksand, sans-serif",
              fontWeight: 700,
              fontSize: 15,
              display: "inline-flex",
              alignItems: "center",
              gap: 6,
            }}
          >
            {t("home.fullMenu")}{" "}</Link>
        </section>

        {/* Featured Product Grid */}
        <section
          style={{
            width: "min(1240px, calc(100% - 64px))",
            margin: "0 auto 80px",
          }}
        >
          <div className="product-grid">
            {featuredProducts.map((product) => (
              <ProductCard key={product.slug} product={product} />
            ))}
          </div>
        </section>

        {/* Brand Promise Section */}
        <section
          className="brand-promise"
          aria-label={t("home.promise.label")}
        >
          <div className="promise-card">
            <div className="promise-icon" style={{ color: "#8F1216" }}>
              <Flame size={28} />
            </div>
            <h3>{t("home.promise.crispyTitle")}</h3>
            <p>
              {t("home.promise.crispyDescription")}{" "}</p>
          </div>

          <div className="promise-card">
            <div className="promise-icon" style={{ color: "#C9A227" }}>
              <Award size={28} />
            </div>
            <h3>{t("home.promise.recipeTitle")}</h3>
            <p>
              {t("home.promise.recipeDescription")}{" "}</p>
          </div>

          <div className="promise-card">
            <div className="promise-icon" style={{ color: "#1E7A46" }}>
              <Truck size={28} />
            </div>
            <h3>{t("home.promise.deliveryTitle")}</h3>
            <p>
              {t("home.promise.deliveryDescription")}{" "}</p>
          </div>
        </section>
      </main>
    </>
  );
}
