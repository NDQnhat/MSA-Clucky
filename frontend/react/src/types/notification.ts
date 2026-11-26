import type { LocalizedText } from '../lib/bilingual';

export type NotificationType = 'ORDER' | 'PAYMENT' | 'PROMOTION' | 'SYSTEM';

export interface AdminNotification {
  id: string | number;
  userId?: string | number;
  title: string;
  titleI18n?: LocalizedText | null;
  content: string;
  contentI18n?: LocalizedText | null;
  type: NotificationType;
  refId?: string | number | null;
  link?: string | null;
  isRead: boolean;
  createdAt: string;
}

