import { expect, test } from '@playwright/test';
import { seedApi, testStore } from '../fixtures/api';

const admin = 'http://127.0.0.1:4173';

test('receiving accounts are managed from Stores and the old Payments page is gone', async ({ page }) => {
  await page.route('**/api/**', route => route.fulfill({ json: [] }));
  await seedApi(page);
  await page.route('**/api/notifications/admin/unread-count', route => route.fulfill({ json: { unreadCount: 0 } }));
  await page.route('**/api/payments/bank-config', route => route.fulfill({ json: {
    accounts: [{ id: 7, bankName: 'MB Bank', bankCode: 'MB', accountNo: '0123456789',
      accountName: 'CLUCKY TEST', active: true, version: 1 }],
    assignments: [{ storeId: testStore.id, bankAccountId: 7, version: 1 }],
    sepayKeyConfigured: true,
    reconciliationAvailable: true,
  } }));

  await page.goto(admin + '/admin/stores');
  await expect(page.getByRole('tab', { name: 'Tài khoản nhận tiền' })).toBeVisible();
  await expect(page.getByRole('menuitem', { name: /Thanh toán & Đối soát|Payments & reconciliation/ })).toHaveCount(0);

  await page.getByRole('tab', { name: 'Tài khoản nhận tiền' }).click();
  await expect(page.getByText('MB Bank', { exact: true })).toBeVisible();
  await expect(page.getByText('0123456789', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Đổi tài khoản' })).toBeVisible();

  await page.goto(admin + '/admin/payments');
  await expect(page).toHaveURL(/\/admin\/transfers$/);
});
