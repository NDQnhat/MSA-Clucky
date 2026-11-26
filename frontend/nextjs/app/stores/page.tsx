"use client";
import { useFeedback } from "@/components/feedback";
import { useEffect, useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Select } from "@base-ui/react/select";
import { Clock, MapPin, Phone, Search, ArrowUpRight, Check, ChevronDown, LocateFixed } from "lucide-react";
import Link from "@/components/ui-loader/navigation-link";
import { request, type StoreItem } from "@/lib/api";
import { matchesBilingualSearch, pickLocalized } from "@/lib/bilingual";
import { getStoreStatusMeta } from "@/lib/types/store";
import { coordinates, distanceKm } from "@/lib/locations";
import { BranchMap } from "@/components/maps/branch-map";
import { useDistanceOrigin } from "@/components/maps/use-distance-origin";
import { useLocation } from "@/components/maps/location-provider";
import styles from "./stores.module.css";

const ALL_DISTRICTS = "__all-store-districts__";

export default function StoresPage() {
  const t = useTranslations("maps");
  const locale = useLocale();
  const { origin: defaultOrigin, fromHome } = useDistanceOrigin();
  const { point: currentPoint, locate, locating } = useLocation();
  const [useGps, setUseGps] = useState(false);
  const origin = useGps && currentPoint ? currentPoint : defaultOrigin;
  const originLabel = t(useGps && currentPoint ? "currentLocationOrigin"
    : fromHome ? "defaultAddressOrigin" : "currentLocationOrigin");
  const [stores, setStores] = useState<StoreItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  useFeedback(error && t("storesError"));
  const [reload, setReload] = useState(0);
  const [query, setQuery] = useState("");
  const [district, setDistrict] = useState("");
  const [selected, setSelected] = useState("");
  const [focusRequest, setFocusRequest] = useState(0);
  useEffect(() => {
    let active = true;
    if (!window.isSecureContext || !navigator.geolocation || !navigator.permissions?.query) return;
    void navigator.permissions.query({ name: "geolocation" }).then(permission => {
      if (!active || permission.state !== "granted") return;
      // Refresh once when visiting this page. A previous coordinate can be
      // used immediately while the browser resolves a fresh position.
      if (currentPoint) setUseGps(true);
      void locate(false).then(found => { if (active && found) setUseGps(true); });
    }).catch(() => {});
    return () => { active = false; };
    // Only issue one automatic request per visit, even if the point changes.
    // The manual button below remains available for refresh/retry.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [locate]);
  useEffect(() => {
    let active = true;
    setLoading(true); setError(false);
    request<StoreItem[]>(`/api/stores?activeOnly=false&lang=${encodeURIComponent(locale)}`, { lang: locale })
      .then(result => { if (active) setStores(result); })
      .catch(() => { if (active) { setStores([]); setError(true); } })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [reload, locale]);
  const districtOptions = useMemo(() => Array.from(new Set(
    stores.map(store => pickLocalized(store.districtI18n, store.district, locale).trim()).filter((value): value is string => Boolean(value))
  )).sort((a, b) => a.localeCompare(b, locale)), [stores, locale]);
  const districtChoices = [
    { value: ALL_DISTRICTS, label: t("allStoreDistricts") },
    ...districtOptions.map(value => ({ value, label: value })),
  ];
  const ranked = useMemo(() => stores.filter(store => {
    const storeDistrict = pickLocalized(store.districtI18n, store.district, locale).trim();
    return (!district || storeDistrict === district)
      && matchesBilingualSearch(
        query,
        store.name,
        store.nameI18n,
        store.address,
        store.district,
        store.districtI18n,
        store.city,
        store.cityI18n,
        store.phone
      );
  })
    .map(store => { const point = coordinates(store); return { store, point, distance: point && origin ? distanceKm(origin, point) : null }; })
    .sort((first, second) => (first.distance ?? Infinity) - (second.distance ?? Infinity)
      || pickLocalized(first.store.nameI18n, first.store.name, locale).localeCompare(
        pickLocalized(second.store.nameI18n, second.store.name, locale),
        locale
      )), [stores, query, district, origin, locale]);
  const selectedId = ranked.some(item => String(item.store.id) === selected) ? selected : "";
  const missingLocation = selectedId && !coordinates(stores.find(store => String(store.id) === selectedId));
  function select(id: string, fromMap = false) {
    setSelected(id);
    setFocusRequest(value => value + 1);
    if (!fromMap && window.matchMedia("(max-width: 760px)").matches) {
      document.getElementById("store-map")?.scrollIntoView({ behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth", block: "center" });
    }
    if (fromMap) {
      const card = document.getElementById("branch-" + id);
      card?.scrollIntoView({ behavior: "smooth", block: "nearest" });
      card?.querySelector<HTMLButtonElement>("button")?.focus({ preventScroll: true });
    }
  }
  return <main className={styles.page}>
    <header className={styles.header}><div><span className={styles.eyebrow}>CLUCKY • STORE LOCATOR</span><h1>{t("title")}</h1><p>{t("subtitle")}</p></div></header>
    <div className={styles.layout}>
      <section className={styles.listPanel} aria-label={t("title")}>
        <div className={styles.filters}><div className={styles.search}><Search size={18} /><input value={query} onChange={event => setQuery(event.target.value)} aria-label={t("storeSearch")} placeholder={t("storeSearch")} /></div>
          <Select.Root
            value={district || ALL_DISTRICTS}
            onValueChange={value => { if (typeof value === "string") setDistrict(value === ALL_DISTRICTS ? "" : value); }}
            items={districtChoices}
            disabled={loading || !districtOptions.length}
          >
            <Select.Trigger type="button" aria-label={t("district")} className={styles.districtTrigger}>
              <Select.Value className={styles.districtValue} />
              <Select.Icon className={styles.districtChevron}><ChevronDown size={16} /></Select.Icon>
            </Select.Trigger>
            <Select.Portal>
              <Select.Positioner sideOffset={7} align="end" alignItemWithTrigger={false} className={styles.districtPositioner}>
                <Select.Popup className={styles.districtPopup}>
                  <Select.List className={styles.districtList}>
                    {districtChoices.map(choice => (
                      <Select.Item key={choice.value} value={choice.value} label={choice.label} className={styles.districtOption}>
                        <Select.ItemText>{choice.label}</Select.ItemText>
                        <Select.ItemIndicator className={styles.districtCheck}><Check size={16} /></Select.ItemIndicator>
                      </Select.Item>
                    ))}
                  </Select.List>
                </Select.Popup>
              </Select.Positioner>
            </Select.Portal>
          </Select.Root>
          <div className={styles.count}>
            <strong>{ranked.length} {t("branches")}</strong>
            {origin && <span title={t("approximate")}>{originLabel} · {t("nearest")}</span>}
            <button type="button" className={styles.locateButton} disabled={locating}
              onClick={() => { void locate().then(found => { if (found) setUseGps(true); }); }}>
              <LocateFixed size={15} aria-hidden="true" />{t(locating ? "locating" : "locate")}
            </button>
          </div>
        </div>
        <div className={styles.cards} aria-busy={loading}>
          {loading && <p role="status">{t("mapLoading")}</p>}
          {error && <div role="alert"><p>{t("storesError")}</p><button className="btn-secondary" onClick={() => setReload(value => value + 1)}>{t("retry")}</button></div>}
          {!loading && !error && !ranked.length && <p>{t("noStores")}</p>}
          {ranked.map(({ store, point, distance }, index) => {
            const available = getStoreStatusMeta(store.status).canOrder;
            const storeName = pickLocalized(store.nameI18n, store.name, locale);
            return <article id={"branch-" + store.id} key={store.id} className={styles.card + (selectedId === String(store.id) ? " " + styles.selected : "")}>
              <button type="button" className={styles.cardHeading} onClick={() => select(String(store.id))} aria-pressed={selectedId === String(store.id)} aria-label={storeName + " — " + t("selectStore")}>
                <div className={styles.logo}><img src="/logo_primary.png" alt="" width={100} height={100} /></div>
                <div><span className={available ? styles.open : styles.closed}>{t(available ? "open" : "closed")}</span><h2>{storeName}</h2><span className={styles.distance} title={distance !== null ? originLabel + " · " + t("approximate") : undefined}>{distance !== null ? t("distanceAway", { distance: new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(distance) }) : t(!point ? "unknownDistance" : "distanceUnavailable")}</span>{index === 0 && distance !== null && <small className={styles.nearest}>{t("nearYou")}</small>}</div>
              </button>
              <div className={styles.details}><p><MapPin size={16} />{store.address}</p><p><Clock size={16} />{store.openTime} – {store.closeTime}<a href={"tel:" + store.phone}><Phone size={14} />{store.phone}</a></p></div>
              <div className={styles.actions}>{available ? <Link href={"/menu?storeId=" + store.id} className="btn-primary">{t("order")}<ArrowUpRight size={17} /></Link> : <span>{t("closed")}</span>}<a href={"https://www.google.com/maps/dir/?api=1&destination=" + encodeURIComponent(point ? point.latitude + "," + point.longitude : store.address)} target="_blank" rel="noreferrer">{t("directions")}<ArrowUpRight size={16} /></a></div>
            </article>;
          })}
        </div>
      </section>
      <section id="store-map" className={styles.mapPanel} aria-label={t("mapLabel")}><BranchMap points={ranked.flatMap(({ store, point }) => point ? [{ ...point, id: String(store.id), label: pickLocalized(store.nameI18n, store.name, locale) }] : [])} selectedId={selectedId} focusRequest={focusRequest} onSelect={id => select(id, true)} origin={origin} originLabel={originLabel} />{missingLocation && <p role="status" className={styles.mapNotice}>{t("storeLocationUnavailable")}</p>}</section>
    </div>
  </main>;
}
