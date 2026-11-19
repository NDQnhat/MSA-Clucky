import { expect, test } from '@playwright/test';
import { seedApi } from '../fixtures/api';

const admin = 'http://127.0.0.1:4173';
const key = 'ORDER_CONFIRMED';
const layout = '<!doctype html><html lang="vi"><head><title>{{subject}}</title><style>body{color:#123456}</style></head><body><table><tr><td>{{content}}</td></tr><tr><td>{{details}}</td></tr>{{action}}<tr><td>{{notice}}</td></tr><tr><td>{{footer}}</td></tr></table></body></html>';

test('admin edits the complete HTML source, not a flattened rich-text fragment', async ({ page }) => {
  await seedApi(page);
  await page.route('**/api/notifications/admin/unread-count', route => route.fulfill({ json: { unreadCount: 0 } }));
  const summary = { key, subject: 'Đơn {{orderCode}}', active: true,
    updatedAt: '2026-11-19T08:00:00Z', publishedRevisionId: 11, latestRevision: 1 };
  const published = { id: 11, revision: 1, subject: summary.subject,
    text: 'Chào {{customerName}}', html: '<strong>Xin chào {{customerName}}</strong>',
    actorId: 1, createdAt: summary.updatedAt, publishedAt: summary.updatedAt };
  const saved: unknown[] = [];
  await page.route('**/api/notifications/email-templates', route => route.fulfill({ json: [summary] }));
  await page.route('**/api/notifications/email-templates/layout', route => route.fulfill({ json: { html: layout } }));
  await page.route(`**/api/notifications/email-templates/${key}`, route => route.fulfill({ json: {
    template: summary, published, draft: null, history: [published],
  } }));
  await page.route(`**/api/notifications/email-templates/${key}/drafts`, route => {
    const input = route.request().postDataJSON();
    saved.push(input);
    return route.fulfill({ json: {
      template: { ...summary, latestRevision: 2 }, published,
      draft: { ...published, ...input, id: 12, revision: 2, publishedAt: null },
      history: [published],
    } });
  });

  await page.goto(admin + '/admin/email-templates');
  const code = page.locator('#clucky-mail-html');
  await expect(code).toBeVisible();
  await expect(code).toHaveValue(/<!doctype html>/i);
  await expect(code).toHaveValue(/<style>body\{color:#123456\}<\/style>/);
  await expect(code).toHaveValue(/<strong>Xin chào \{\{customerName\}\}<\/strong>/);
  await expect(page.locator('#clucky-mail-text')).toHaveCount(0);
  await expect(page.getByText(/Phiên bản văn bản thuần|Plain-text fallback/i)).toHaveCount(0);

  const updated = (await code.inputValue()).replace('color:#123456', 'color:#8F1216');
  await code.fill(updated);
  await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
  await expect.poll(() => saved.length).toBe(1);
  expect(saved[0]).toMatchObject({ expectedRevision: 1, text: 'Chào {{customerName}}', html: updated });
});
