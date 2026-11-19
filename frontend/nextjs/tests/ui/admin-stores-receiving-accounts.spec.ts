import { expect, test } from '@playwright/test';
import { seedApi, testStore } from '../fixtures/api';

const admin = 'http://127.0.0.1:4173';
const receivingAccount = {
  id: 7,
  bankName: 'MB Bank',
  bankCode: 'MB',
  accountNo: '0123456789',
  accountName: 'CLUCKY TEST',
  active: true,
  version: 1,
};

async function openReceivingAccounts(page: import('@playwright/test').Page, assignments: Array<{
  storeId: number;
  bankAccountId: number | null;
  version: number;
}>) {
  await page.route('**/api/**', route => route.fulfill({ json: [] }));
  await seedApi(page);
  await page.route('**/api/notifications/admin/unread-count', route => route.fulfill({ json: { unreadCount: 0 } }));
  await page.route('**/api/payments/bank-config', route => route.fulfill({ json: {
    accounts: [receivingAccount],
    assignments,
    sepayKeyConfigured: true,
    reconciliationAvailable: true,
  } }));
  await page.goto(admin + '/admin/stores');
  await page.getByRole('tab', { name: 'Tài khoản nhận tiền' }).click();
  await expect(page.getByText(receivingAccount.accountNo, { exact: true })).toBeVisible();
}

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

test('assigned receiving account cannot be deleted and names the assigned branch', async ({ page }) => {
  let deleteRequests = 0;
  page.on('request', request => {
    if (request.method() === 'DELETE' && request.url().includes('/api/payments/bank-config/7')) deleteRequests += 1;
  });
  await openReceivingAccounts(page, [{ storeId: testStore.id, bankAccountId: receivingAccount.id, version: 1 }]);

  const accountRow = page.getByRole('row').filter({ hasText: receivingAccount.bankName });
  await accountRow.getByRole('button', { name: 'Xóa tài khoản' }).click();

  await expect(page.getByText(/Không thể xóa tài khoản này vì đang được gán cho chi nhánh: Clucky Test/)).toBeVisible();
  expect(deleteRequests).toBe(0);
});

test('unassigned receiving account is deleted after confirmation', async ({ page }) => {
  let deleted = false;
  let idempotencyKey = '';
  await page.route('**/api/**', route => route.fulfill({ json: [] }));
  await seedApi(page);
  await page.route('**/api/notifications/admin/unread-count', route => route.fulfill({ json: { unreadCount: 0 } }));
  await page.route('**/api/payments/bank-config', route => route.fulfill({ json: {
    accounts: deleted ? [] : [receivingAccount],
    assignments: [],
    sepayKeyConfigured: true,
    reconciliationAvailable: true,
  } }));
  await page.route('**/api/payments/bank-config/7', async route => {
    if (route.request().method() !== 'DELETE') return route.fallback();
    idempotencyKey = route.request().headers()['idempotency-key'] ?? '';
    deleted = true;
    await route.fulfill({ json: receivingAccount });
  });

  await page.goto(admin + '/admin/stores');
  await page.getByRole('tab', { name: 'Tài khoản nhận tiền' }).click();
  const accountRow = page.getByRole('row').filter({ hasText: receivingAccount.bankName });
  await accountRow.getByRole('button', { name: 'Xóa tài khoản' }).click();

  const confirmation = page.getByRole('dialog');
  await expect(confirmation).toContainText('Bạn có chắc muốn xóa tài khoản');
  const deleteRequest = page.waitForRequest(request => request.method() === 'DELETE'
    && request.url().endsWith('/api/payments/bank-config/7'));
  await Promise.all([
    deleteRequest,
    confirmation.getByRole('button', { name: 'Xóa tài khoản', exact: true }).click(),
  ]);

  await expect(page.getByText('Đã xóa tài khoản nhận tiền.')).toBeVisible();
  await expect(page.getByRole('row').filter({ hasText: receivingAccount.bankName })).toHaveCount(0);
  expect(idempotencyKey).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
});

test('server-side assignment conflict refreshes the branch list and explains why deletion failed', async ({ page }) => {
  let deleteAttempted = false;
  await page.route('**/api/**', route => route.fulfill({ json: [] }));
  await seedApi(page);
  await page.route('**/api/notifications/admin/unread-count', route => route.fulfill({ json: { unreadCount: 0 } }));
  await page.route('**/api/payments/bank-config', route => route.fulfill({ json: {
    accounts: [receivingAccount],
    assignments: deleteAttempted ? [{ storeId: testStore.id, bankAccountId: receivingAccount.id, version: 2 }] : [],
    sepayKeyConfigured: true,
    reconciliationAvailable: true,
  } }));
  await page.route('**/api/payments/bank-config/7', async route => {
    if (route.request().method() !== 'DELETE') return route.fallback();
    deleteAttempted = true;
    await route.fulfill({ status: 409, json: { code: 'RESOURCE_CONFLICT', message: 'Account is assigned.' } });
  });

  await page.goto(admin + '/admin/stores');
  await page.getByRole('tab', { name: 'Tài khoản nhận tiền' }).click();
  const accountRow = page.getByRole('row').filter({ hasText: receivingAccount.bankName });
  await accountRow.getByRole('button', { name: 'Xóa tài khoản' }).click();
  const confirmation = page.getByRole('dialog');
  await confirmation.getByRole('button', { name: 'Xóa tài khoản', exact: true }).click();

  await expect(page.getByText(/Không thể xóa tài khoản này vì đang được gán cho chi nhánh: Clucky Test/)).toBeVisible();
  await expect(page.getByRole('region', { name: 'Tài khoản nhận tiền' })
    .getByRole('row').filter({ hasText: receivingAccount.bankName })).toBeVisible();
});
