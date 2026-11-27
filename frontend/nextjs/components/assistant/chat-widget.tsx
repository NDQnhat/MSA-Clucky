"use client";

import { Suspense, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import { MessageCircle, RotateCcw, Send, Sparkles, Store, X, Loader2 } from "lucide-react";
import Link from "@/components/ui-loader/navigation-link";
import { FoodImage } from "@/components/order/food-image";
import { useSession } from "@/components/auth/use-session";
import { useLocaleTools } from "@/lib/i18n/hooks";
import { pickLocalized } from "@/lib/bilingual";
import {
  assistantApi,
  catalogApi,
  type AssistantHistoryMessage,
  type AssistantProductCard,
  type StoreItem,
} from "@/lib/api";
import styles from "./chat-widget.module.css";

const SESSION_STORAGE_KEY = "clucky_assistant_session_id";

function getOrCreateAssistantSessionId(): string {
  if (typeof window === "undefined") return "ssr";
  let id = localStorage.getItem(SESSION_STORAGE_KEY);
  if (!id) {
    id = typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : `chat-${Date.now()}`;
    localStorage.setItem(SESSION_STORAGE_KEY, id);
  }
  return id;
}

function renderInlineMarkdown(text: string): ReactNode[] {
  const tokens = text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g);
  return tokens.map((token, idx) => {
    if (token.startsWith("**") && token.endsWith("**") && token.length > 4) {
      return <strong key={idx}>{token.slice(2, -2)}</strong>;
    }
    if (token.startsWith("`") && token.endsWith("`") && token.length > 2) {
      return (
        <code key={idx} className={styles.inlineCode}>
          {token.slice(1, -1)}
        </code>
      );
    }
    return token;
  });
}

function FormattedChatText({ content }: { content: string }) {
  const lines = content.replace(/\r\n/g, "\n").split("\n");
  const blocks: ReactNode[] = [];
  let listItems: string[] = [];
  let listOrdered = false;

  const flushList = (keyPrefix: number) => {
    if (!listItems.length) return;
    const Tag = listOrdered ? "ol" : "ul";
    blocks.push(
      <Tag key={`list-${keyPrefix}`} className={styles.mdList}>
        {listItems.map((item, idx) => (
          <li key={idx}>{renderInlineMarkdown(item)}</li>
        ))}
      </Tag>,
    );
    listItems = [];
  };

  lines.forEach((rawLine, idx) => {
    const line = rawLine.trim();
    if (!line) {
      flushList(idx);
      return;
    }
    const bulletMatch = line.match(/^[-*•]\s+(.+)$/);
    if (bulletMatch) {
      if (listItems.length && listOrdered) flushList(idx);
      listOrdered = false;
      listItems.push(bulletMatch[1]);
      return;
    }
    const orderedMatch = line.match(/^\d+\.\s+(.+)$/);
    if (orderedMatch) {
      if (listItems.length && !listOrdered) flushList(idx);
      listOrdered = true;
      listItems.push(orderedMatch[1]);
      return;
    }
    flushList(idx);
    const headingMatch = line.match(/^#{1,3}\s+(.+)$/);
    if (headingMatch) {
      blocks.push(
        <p key={`h-${idx}`} className={styles.mdHeading}>
          {renderInlineMarkdown(headingMatch[1])}
        </p>,
      );
      return;
    }
    blocks.push(
      <p key={`p-${idx}`} className={styles.mdParagraph}>
        {renderInlineMarkdown(line)}
      </p>,
    );
  });
  flushList(lines.length);

  return <div className={styles.mdRoot}>{blocks}</div>;
}

function ChatWidgetInner() {
  const t = useTranslations("common.assistant");
  const { locale, money } = useLocaleTools();
  const session = useSession();
  const searchParams = useSearchParams();

  const urlStoreId = useMemo(() => {
    const raw = searchParams.get("storeId");
    const parsed = raw ? Number(raw) : NaN;
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined;
  }, [searchParams]);

  const [open, setOpen] = useState(false);
  const [selectedStoreId, setSelectedStoreId] = useState<number | undefined>(urlStoreId);
  const [stores, setStores] = useState<StoreItem[]>([]);
  const [sessionId, setSessionId] = useState<string>("");
  const [messages, setMessages] = useState<AssistantHistoryMessage[]>([]);
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const [historyLoadedFor, setHistoryLoadedFor] = useState<string>("");
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (urlStoreId !== undefined) {
      setSelectedStoreId(urlStoreId);
    }
  }, [urlStoreId]);

  useEffect(() => {
    setSessionId(getOrCreateAssistantSessionId());
  }, []);

  useEffect(() => {
    if (!open) return;
    let active = true;
    catalogApi.getStores(true, locale).then((list) => {
      if (active && Array.isArray(list)) {
        setStores(list.filter((s) => s.status === "OPEN"));
      }
    }).catch(() => {});
    return () => { active = false; };
  }, [open, locale]);

  useEffect(() => {
    if (!open || !sessionId) return;
    const cacheKey = `${sessionId}:${session.user?.userId ?? "guest"}`;
    if (historyLoadedFor === cacheKey) return;
    let active = true;
    assistantApi.getHistory(sessionId, locale)
      .then((res) => {
        if (!active) return;
        setMessages(Array.isArray(res.messages) ? res.messages : []);
        setHistoryLoadedFor(cacheKey);
      })
      .catch(() => {
        if (active) setHistoryLoadedFor(cacheKey);
      });
    return () => { active = false; };
  }, [open, sessionId, session.user?.userId, locale, historyLoadedFor]);

  useEffect(() => {
    if (open && listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight;
    }
  }, [open, messages, sending]);

  const quickChips = useMemo(() => {
    const chips = [
      t("chipBestSellers"),
      t("chipCheapest"),
      t("chipSummer"),
      t("chipStores"),
      t("chipPromos"),
    ];
    if (session.user?.role === "CUSTOMER") {
      chips.push(t("chipMyOrders"));
    }
    return chips;
  }, [t, session.user?.role]);

  const sendMessage = async (text: string) => {
    const trimmed = text.trim();
    if (!trimmed || sending) return;
    const activeSessionId = sessionId || getOrCreateAssistantSessionId();
    if (!sessionId) setSessionId(activeSessionId);

    const userMsg: AssistantHistoryMessage = {
      role: "user",
      content: trimmed,
      recommendations: [],
      timestamp: new Date().toISOString(),
    };
    setMessages((prev) => [...prev, userMsg]);
    setInput("");
    setSending(true);

    try {
      const res = await assistantApi.chat({
        message: trimmed,
        sessionId: activeSessionId,
        storeId: selectedStoreId,
        locale,
      });
      if (res.sessionId && res.sessionId !== activeSessionId) {
        setSessionId(res.sessionId);
        localStorage.setItem(SESSION_STORAGE_KEY, res.sessionId);
      }
      setMessages((prev) => [
        ...prev,
        {
          role: "assistant",
          content: res.reply,
          recommendations: res.recommendations ?? [],
          timestamp: new Date().toISOString(),
        },
      ]);
    } catch {
      setMessages((prev) => [
        ...prev,
        {
          role: "assistant",
          content: t("errorFallback"),
          recommendations: [],
          timestamp: new Date().toISOString(),
        },
      ]);
    } finally {
      setSending(false);
    }
  };

  const resetChat = async () => {
    if (sessionId) {
      await assistantApi.clearHistory(sessionId, locale).catch(() => {});
    }
    const nextId = typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : `chat-${Date.now()}`;
    localStorage.setItem(SESSION_STORAGE_KEY, nextId);
    setSessionId(nextId);
    setMessages([]);
    setHistoryLoadedFor(`${nextId}:${session.user?.userId ?? "guest"}`);
  };

  const renderCard = (card: AssistantProductCard) => {
    const storeParam = card.storeId ?? selectedStoreId;
    const href = `/menu/${card.slug}${storeParam ? `?storeId=${storeParam}` : ""}`;
    return (
      <div className={styles.productCard} key={`${card.itemType}-${card.id}`}>
        <FoodImage
          src={card.imageUrl || "/images/food-placeholder.svg"}
          alt={card.name}
          width={56}
          height={56}
          className={styles.productThumb}
        />
        <div className={styles.productMeta}>
          <strong title={card.name}>{card.name}</strong>
          <div className={styles.productPriceRow}>
            <span>{money(card.price)}</span>
            {card.isSoldOut && <span className={styles.soldOutBadge}>{t("soldOut")}</span>}
          </div>
        </div>
        <Link href={href} className={styles.viewBtn} onClick={() => setOpen(false)}>
          {t("viewDish")}
        </Link>
      </div>
    );
  };

  return (
    <>
      {!open && (
        <div className={styles.assistantDock}>
          <button
            type="button"
            className={styles.fab}
            aria-label={t("openLabel")}
            onClick={() => setOpen(true)}
          >
            <MessageCircle size={24} />
            <span className={styles.fabPulse} aria-hidden="true" />
          </button>
        </div>
      )}

      {open && (
        <section className={styles.panel} aria-label={t("title")}>
          <header className={styles.header}>
            <div className={styles.headerTitle}>
              <span className={styles.headerBadge} aria-hidden="true">
                <Sparkles size={18} />
              </span>
              <div>
                <h2>{t("title")}</h2>
                <p>{t("subtitle")}</p>
              </div>
            </div>
            <div className={styles.headerActions}>
              <button
                type="button"
                className={styles.iconBtn}
                onClick={() => void resetChat()}
                aria-label={t("resetLabel")}
                title={t("resetLabel")}
              >
                <RotateCcw size={15} />
              </button>
              <button
                type="button"
                className={styles.iconBtn}
                onClick={() => setOpen(false)}
                aria-label={t("closeLabel")}
              >
                <X size={16} />
              </button>
            </div>
          </header>

          <div className={styles.storeBar}>
            <label htmlFor="clucky-assistant-store">
              <Store size={13} />
              {t("storePrefix")}
            </label>
            <select
              id="clucky-assistant-store"
              className={styles.storeSelect}
              value={selectedStoreId ?? ""}
              onChange={(e) => {
                const val = Number(e.target.value);
                setSelectedStoreId(Number.isSafeInteger(val) && val > 0 ? val : undefined);
              }}
            >
              <option value="">{t("storeAll")}</option>
              {stores.map((s) => (
                <option key={s.id} value={s.id}>
                  {pickLocalized(s.nameI18n, s.name, locale)}
                </option>
              ))}
            </select>
          </div>

          <div className={styles.messages} ref={listRef}>
            <div className={styles.bubbleBot}>{t("welcome")}</div>
            {messages.map((msg, idx) => (
              <div className={styles.bubbleRow} key={`${msg.timestamp}-${idx}`}>
                <div className={msg.role === "user" ? styles.bubbleUser : styles.bubbleBot}>
                  {msg.role === "assistant" ? (
                    <FormattedChatText content={msg.content} />
                  ) : (
                    msg.content
                  )}
                </div>
                {msg.role === "assistant" && msg.recommendations && msg.recommendations.length > 0 && (
                  <div className={styles.cardsGrid}>
                    {msg.recommendations.map(renderCard)}
                  </div>
                )}
              </div>
            ))}
            {sending && (
              <div className={styles.thinking}>
                <Loader2 size={14} className="animate-spin" />
                <span>{t("thinking")}</span>
              </div>
            )}
          </div>

          <div className={styles.chips}>
            {quickChips.map((chip) => (
              <button
                key={chip}
                type="button"
                className={styles.chip}
                disabled={sending}
                onClick={() => void sendMessage(chip)}
              >
                {chip}
              </button>
            ))}
          </div>

          <form
            className={styles.composer}
            onSubmit={(e) => {
              e.preventDefault();
              void sendMessage(input);
            }}
          >
            <input
              type="text"
              className={styles.input}
              value={input}
              maxLength={800}
              onChange={(e) => setInput(e.target.value)}
              placeholder={t("placeholder")}
              aria-label={t("placeholder")}
            />
            <button
              type="submit"
              className={styles.sendBtn}
              disabled={sending || !input.trim()}
              aria-label={t("sendLabel")}
            >
              <Send size={16} />
            </button>
          </form>
        </section>
      )}
    </>
  );
}

export function ChatWidget() {
  return (
    <Suspense fallback={null}>
      <ChatWidgetInner />
    </Suspense>
  );
}
