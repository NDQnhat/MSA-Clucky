import { test, expect } from '@playwright/test';

test('saved address stays selected and a failed quote explains the blocked submit', async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-11-12T12:00:00+07:00'));
  await page.addInitScript(() => sessionStorage.setItem('clucky_location_prompt', 'seen'));
  await page.route('**/api/auth/session', route => route.fulfill({ json: { userId: 7, role: 'CUSTOMER', fullName: 'Customer', email: 'customer@example.com' } }));
  await page.route('**/api/orders/my?*', route => route.fulfill({ json: { content: [] } }));
  await page.route('**/api/cart', route => route.fulfill({ json: {
    items: [{ itemKey: 'item-901', productId: 901, comboId: null, itemName: 'Regression Chicken', quantity: 1, unitPrice: 105000, optionIds: [], toppingIds: [], storeId: 1 }],
    itemCount: 1, subtotal: 105000,
  } }));
  await page.route('**/api/stores?*', route => route.fulfill({ json: [{ id: 1, name: 'Clucky Test', address: 'Test street', status: 'OPEN', phone: '0900000000', openTime: '08:00', closeTime: '22:00' }] }));
  await page.route('**/api/users/me/addresses', route => route.fulfill({ json: [{ id: 11, receiverName: 'Saved Customer', phone: '0901234567', province: 'Hà Nội', district: 'Ba Đình', ward: 'Ngọc Hà', detail: '123 Test Street', isDefault: true, latitude: 21.03, longitude: 105.82 }] }));
  await page.route('**/api/loyalty/me?*', route => route.fulfill({ json: { points: { availablePoints: 0 } } }));
  let failQuote = true;
  await page.route('**/api/orders/preview', route => route.fulfill(failQuote
    ? { status: 503, json: { code: 'DEPENDENCY_UNAVAILABLE' } }
    : { json: { orderValid: true, subtotal: 105000, discountAmount: 0, pointsAmount: 0, pointsUsed: 0, shippingFee: 15000, totalAmount: 120000, tierDiscount: 0, voucherValid: false,
        shipping: { available: true, fee: 15000, reason: 'OK', distanceKm: 2, radiusKm: 5, policyVersion: 1 } } }));
  await page.goto('http://127.0.0.1:4174/checkout');
  const address = page.getByRole('combobox', { name: 'Chọn từ sổ địa chỉ' });
  await address.click();
  await page.getByRole('option', { name: /Saved Customer/ }).click();
  await expect(address).toContainText('Saved Customer');
  await expect(page.locator('#deliveryAddress')).toHaveValue('123 Test Street, Ngọc Hà, Ba Đình, Hà Nội');
  const blocked = page.locator('#checkout-submit-status');
  await expect(blocked).toContainText('Chưa thể đặt hàng');
  await expect(page.getByRole('button', { name: /Đặt hàng ngay/ })).toBeDisabled();
  await expect(page.getByText('Chờ xác nhận', { exact: true })).toBeVisible();
  failQuote = false;
  await blocked.getByRole('button', { name: 'Thử lại' }).click();
  await expect(page.getByRole('button', { name: /Đặt hàng ngay/ })).toBeEnabled();
  await expect(page.locator('#checkout-submit-status')).toHaveCount(0);
});

for (const hasSavedAddress of [false, true]) {
  test(`checkout saves and immediately selects a new address (${hasSavedAddress ? 'existing addresses' : 'first address'})`, async ({ page }) => {
    await page.clock.setFixedTime(new Date('2026-11-12T12:00:00+07:00'));
    await page.addInitScript(() => sessionStorage.setItem('clucky_location_prompt', 'seen'));
    await page.route('**/api/auth/session', route => route.fulfill({ json: { userId: 7, role: 'CUSTOMER', fullName: 'Customer', email: 'customer@example.com' } }));
    await page.route('**/api/orders/my?*', route => route.fulfill({ json: { content: [] } }));
    await page.route('**/api/cart', route => route.fulfill({ json: {
      items: [{ itemKey: 'item-901', productId: 901, comboId: null, itemName: 'Regression Chicken', quantity: 1, unitPrice: 105000, optionIds: [], toppingIds: [], storeId: 1 }],
      itemCount: 1, subtotal: 105000,
    } }));
    await page.route('**/api/stores?*', route => route.fulfill({ json: [{ id: 1, name: 'Clucky Test', address: 'Test street', status: 'OPEN', phone: '0900000000', openTime: '08:00', closeTime: '22:00' }] }));
    await page.route('**/api/loyalty/me?*', route => route.fulfill({ json: { points: { availablePoints: 0 } } }));
    const addresses = hasSavedAddress ? [{ id: 11, receiverName: 'Saved Customer', phone: '0901234567', province: 'Hà Nội', district: 'Ba Đình', ward: 'Ngọc Hà', detail: '123 Test Street', isDefault: true, latitude: 21.03, longitude: 105.82 }] : [];
    const saves: Record<string, unknown>[] = [];
    await page.route('**/api/users/me/addresses', route => {
      if (route.request().method() === 'POST') {
        const body = route.request().postDataJSON();
        saves.push(body);
        const saved = { ...body, id: 12 };
        addresses.push(saved);
        return route.fulfill({ json: saved });
      }
      return route.fulfill({ json: addresses });
    });

    await page.goto('http://127.0.0.1:4174/checkout');
    const add = page.getByRole('button', { name: 'Thêm địa chỉ', exact: true });
    await expect(add).toBeEnabled();
    if (!hasSavedAddress) await expect(page.getByText('Bạn chưa có địa chỉ giao hàng.')).toBeVisible();
    await add.click();
    const dialog = page.getByRole('dialog', { name: 'Thêm địa chỉ giao hàng' });
    await dialog.getByLabel('Người nhận').fill('New Customer');
    await dialog.getByLabel('Số điện thoại').fill('0901111222');
    await dialog.getByLabel('Tỉnh / thành phố').fill('Hà Nội');
    await dialog.getByLabel('Quận / huyện (nếu có)').fill('Đống Đa');
    await dialog.getByLabel('Phường / xã').fill('Láng Hạ');
    await dialog.getByLabel('Số nhà, đường, căn hộ').fill('45 New Street');
    await dialog.getByRole('button', { name: 'Lưu và dùng địa chỉ này' }).click();

    await expect(dialog).toHaveCount(0);
    await expect(page.locator('#deliveryAddress')).toHaveValue('45 New Street, Láng Hạ, Đống Đa, Hà Nội');
    await expect(page.getByRole('combobox', { name: 'Chọn từ sổ địa chỉ' })).toContainText('New Customer');
    expect(saves).toHaveLength(1);
    expect(saves[0]).toMatchObject({ receiverName: 'New Customer', detail: '45 New Street', isDefault: !hasSavedAddress });
    expect(saves[0]).not.toHaveProperty('id', 11);
    if (hasSavedAddress) {
      await page.getByRole('combobox', { name: 'Chọn từ sổ địa chỉ' }).click();
      await page.getByRole('option', { name: /Saved Customer/ }).click();
      await expect(page.locator('#deliveryAddress')).toHaveValue('123 Test Street, Ngọc Hà, Ba Đình, Hà Nội');
    }
  });
}
