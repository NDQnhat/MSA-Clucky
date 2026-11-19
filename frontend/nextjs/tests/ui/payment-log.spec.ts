import { expect, test } from '@playwright/test';
import { seedApi } from '../fixtures/api';

const admin = 'http://127.0.0.1:4173';

test('admin uses the transfer ledger as the canonical incoming-payment workspace', async ({ page }) => {
  const receivedAt = new Date().toISOString();
  await seedApi(page);
  await page.route('**/api/notifications/admin/unread-count', route => route.fulfill({ json: { unreadCount: 0 } }));
  await page.route('**/api/payments/transactions?*', route => route.fulfill({ json: {
    page: { content: [{ id: '7', transactionId: 'SEPAY:LOG-7', maskedAccount: '****1234',
      amount: 50000, content: 'Customer transfer', receivedAt, matchedOrderCode: null }], totalPages: 1 },
  } }));
  await page.route('**/api/payments/receipts*', route => route.fulfill({ json: [] }));
  await page.route('**/api/payments/shift-summary*', route => route.fulfill({ json: {
    netCashInDrawer: 0, totalCashCollected: 0, totalCashChangeGiven: 0,
    totalBankReconciled: 0, totalBankPending: 0,
  } }));

  await page.goto(admin + '/admin/transfers');
  await expect(page.getByRole('heading', { name: /Sổ Quỹ Dòng Tiền/ })).toBeVisible();
  await page.getByRole('button', { name: /Giao dịch ngân hàng/ }).click();
  await page.getByRole('button', { name: 'Chưa gán đơn', exact: true }).click();
  await expect(page.getByText('Customer transfer', { exact: true })).toBeVisible();
  await expect(page.getByText('SEPAY:LOG-7', { exact: true })).toBeVisible();
});
