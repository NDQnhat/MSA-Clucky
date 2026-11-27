"use client";

import { useEffect, useState, type ReactNode } from "react";
import { NextIntlClientProvider } from "next-intl";
import { Toaster } from "@/components/feedback";
import {
  getBrowserLocale,
  localeChangeEvent,
  normalizeLocale,
  type Locale,
} from "./locale";
import { resources } from "./resources";

export function RuntimeIntlProvider({
  initialLocale,
  children,
}: {
  initialLocale: string;
  children: ReactNode;
}) {
  const [activeLocale, setActiveLocale] = useState<Locale>(() =>
    normalizeLocale(initialLocale),
  );

  useEffect(() => {
    setActiveLocale(getBrowserLocale());
  }, [initialLocale]);

  useEffect(() => {
    const onLocaleChange = (event: Event) => {
      const next = (event as CustomEvent<Locale>).detail;
      setActiveLocale(normalizeLocale(next));
    };
    window.addEventListener(localeChangeEvent, onLocaleChange);
    return () => window.removeEventListener(localeChangeEvent, onLocaleChange);
  }, []);

  return (
    <NextIntlClientProvider
      locale={activeLocale}
      messages={resources[activeLocale]}
      timeZone="Asia/Ho_Chi_Minh"
    >
      <Toaster locale={activeLocale} />
      {children}
    </NextIntlClientProvider>
  );
}
