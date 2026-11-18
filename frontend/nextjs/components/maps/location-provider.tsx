"use client";
import { notify } from "@/components/feedback";
import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { useTranslations } from "next-intl";
import { LocateFixed, X } from "lucide-react";
import { coordinates, type Coordinates } from "@/lib/geo";
import styles from "./maps.module.css";

type LocationError = "locationInsecure" | "locationUnsupported" | "locationDenied" | "locationUnavailable" | "locationTimeout" | "locationError";
type LocationContextValue = {
  point: Coordinates | null;
  locate: (showErrors?: boolean) => Promise<Coordinates | null>;
  locating: boolean;
  error: LocationError | null;
};

const LocationContext = createContext<LocationContextValue>({
  point: null, locate: async () => null, locating: false, error: null,
});

const LOCATION_DEADLINE_MS = 20000;
const RETRY_DELAYS_MS = [1200, 2000];

async function readPermission(): Promise<PermissionState | null> {
  try {
    return navigator.permissions?.query
      ? (await navigator.permissions.query({ name: "geolocation" })).state
      : null;
  } catch {
    return null;
  }
}

export function LocationProvider({ children }: { children: ReactNode }) {
  const t = useTranslations("maps");
  const [point, setPoint] = useState<Coordinates | null>(null);
  const [prompt, setPrompt] = useState(false);
  const [locating, setLocating] = useState(false);
  const [error, setError] = useState<LocationError | null>(null);
  const pending = useRef<Promise<Coordinates | null> | null>(null);
  const reportErrors = useRef(false);

  useEffect(() => {
    if (!error) return;
    const notification = notify.warning({ content: t(error), key: "current-location-error", duration: 0 });
    return () => { notify.destroy(notification); };
  }, [error, t]);

  useEffect(() => {
    try { setPrompt(sessionStorage.getItem("clucky_location_prompt") !== "seen"); }
    catch { setPrompt(true); }
  }, []);

  const dismiss = () => {
    setPrompt(false);
    try { sessionStorage.setItem("clucky_location_prompt", "seen"); } catch {}
  };

  const locate = useCallback((showErrors = true): Promise<Coordinates | null> => {
    if (pending.current) {
      if (showErrors) { reportErrors.current = true; setError(null); }
      return pending.current;
    }
    if (!window.isSecureContext) {
      if (showErrors) setError("locationInsecure");
      return Promise.resolve(null);
    }
    if (!navigator.geolocation) {
      if (showErrors) setError("locationUnsupported");
      return Promise.resolve(null);
    }

    reportErrors.current = showErrors;
    setError(null);
    setLocating(true);
    pending.current = new Promise<Coordinates | null>(resolve => {
      let finished = false;
      let attemptNumber = 0;
      let retryTimer: number | null = null;
      const deadlineTimer = window.setTimeout(() => finish(null, "locationTimeout"), LOCATION_DEADLINE_MS);

      function finish(next: Coordinates | null, failure?: LocationError) {
        if (finished) return;
        finished = true;
        window.clearTimeout(deadlineTimer);
        if (retryTimer !== null) window.clearTimeout(retryTimer);
        if (next) {
          setPoint(next);
          setPrompt(false);
          setError(null);
        } else if (failure && reportErrors.current) {
          setError(failure);
        }
        resolve(next);
      }

      function retry() {
        const delay = RETRY_DELAYS_MS[attemptNumber - 1];
        if (delay === undefined) return false;
        retryTimer = window.setTimeout(() => {
          retryTimer = null;
          requestPosition();
        }, delay);
        return true;
      }

      function requestPosition() {
        if (finished) return;
        const currentAttempt = attemptNumber++;
        try {
          navigator.geolocation.getCurrentPosition(
            position => {
              if (finished) return;
              const next = coordinates(position.coords);
              finish(next, next ? undefined : "locationUnavailable");
            },
            async failure => {
              if (finished) return;
              if (failure.code === 1) {
                let state = await readPermission();
                if (finished) return;
                if (state === "denied" || state === "prompt") {
                  // Chromium may briefly report a stale permission state while
                  // the OS is applying the browser grant.
                  await new Promise<void>(done => window.setTimeout(done, 1000));
                  if (finished) return;
                  state = await readPermission();
                }
                if (state === "denied" || state === "prompt") {
                  finish(null, "locationDenied");
                  return;
                }
                if (retry()) return;
                finish(null, state === "granted" ? "locationUnavailable" : "locationDenied");
              } else if ((failure.code === 2 || failure.code === 3) && retry()) {
                // Retry an unavailable/slow fix with high accuracy.
                return;
              } else {
                finish(null, failure.code === 2 ? "locationUnavailable"
                  : failure.code === 3 ? "locationTimeout" : "locationError");
              }
            },
            {
              enableHighAccuracy: currentAttempt > 0,
              maximumAge: currentAttempt === 0 ? 60000 : 0,
              timeout: currentAttempt === 0 ? 8000 : 9000,
            },
          );
        } catch (failure) {
          finish(null, failure instanceof DOMException && failure.name === "SecurityError"
            ? "locationDenied" : "locationError");
        }
      }
      requestPosition();
    }).finally(() => {
      setLocating(false);
      pending.current = null;
      reportErrors.current = false;
    });
    return pending.current;
  }, []);

  useEffect(() => {
    let active = true;
    let permission: PermissionStatus | undefined;
    const syncPermission = () => {
      if (!active || !permission) return;
      if (permission.state === "granted") {
        setPrompt(false);
        setError(previous => previous === "locationDenied" ? null : previous);
      }
      if (permission.state === "denied") setPrompt(false);
    };
    if (window.isSecureContext && navigator.permissions?.query) {
      void navigator.permissions.query({ name: "geolocation" }).then(status => {
        if (!active) return;
        permission = status;
        permission.addEventListener("change", syncPermission);
        syncPermission();
      }).catch(() => {});
    }
    return () => {
      active = false;
      permission?.removeEventListener("change", syncPermission);
    };
  }, []);

  return <LocationContext.Provider value={{ point, locate, locating, error }}>
    {children}
    {prompt && <aside className={styles.locationPrompt} aria-label={t("locationPrompt")}>
      <button className={styles.dismiss} onClick={dismiss} aria-label={t("later")}><X size={18} /></button>
      <LocateFixed size={26} /><strong>{t("locationPrompt")}</strong><p>{t("locationWhy")}</p>
      <small>{t("locationPrivacy")}</small>
      {error && <p role="alert">{t(error)}</p>}
      <div className={styles.actions}><button className="btn-primary" disabled={locating} onClick={async () => { if (await locate()) dismiss(); }}>{t(locating ? "locating" : "allow")}</button><button type="button" onClick={dismiss}>{t("later")}</button></div>
    </aside>}
  </LocationContext.Provider>;
}

export function useLocation() { return useContext(LocationContext); }
