import { expect, test } from "@playwright/test";
import { seedApi, testStore } from "../fixtures/api";

const admin = "http://127.0.0.1:4173";

// A selected-store PERSONAL campaign created by Admin has legacy scope=ALL;
// a Store Manager proposal approved by Admin has scope=STORE. Both must retain
// their original metadata when edited, including while orders are reserved.
for (const scenario of [
  { label: "admin-created campaign", scope: "ALL", storeId: null },
  { label: "approved store proposal", scope: "STORE", storeId: testStore.id },
] as const) {
  test(`admin saves ${scenario.label} without changing its reserved store scope`, async ({ page }) => {
    await seedApi(page);
    let voucher = {
      id: 77, campaignKind: "PERSONAL", code: null, title: "Original Branch Offer",
      description: "Eligible in the test branch", type: "PERCENT", value: 10,
      minOrderAmount: 0, maxDiscount: 10000, totalLimit: 100,
      usedCount: 2, reservedCount: 1, version: 3,
      startAt: "2026-01-01T00:00:00Z", endAt: "2099-01-01T00:00:00Z",
      scope: scenario.scope, storeId: scenario.storeId,
      applicability: "SELECTED", storeIds: [testStore.id],
      status: "ACTIVE", effectiveStatus: "ACTIVE",
    };
    let saved = false;
    await page.route(/\/api\/promotions\/vouchers(?:\?.*)?$/, route =>
      route.fulfill({ json: [voucher] }));
    await page.route("**/api/promotions/vouchers/77", async route => {
      expect(route.request().method()).toBe("PUT");
      const payload = route.request().postDataJSON();
      expect(payload).toMatchObject({
        version: 3, code: null, title: "Updated Branch Offer", status: "ACTIVE",
        applicability: "SELECTED", storeIds: [testStore.id],
        scope: scenario.scope, storeId: scenario.storeId,
      });
      voucher = { ...voucher, ...payload, version: 4 };
      saved = true;
      await route.fulfill({ json: voucher });
    });

    await page.goto(admin + "/admin/promotions");
    const original = page.getByRole("row").filter({ hasText: "Original Branch Offer" });
    await expect(original).toBeVisible();
    await original.getByRole("button", { name: "Chỉnh sửa" }).click();
    const modal = page.getByRole("dialog", { name: /Chỉnh sửa chương trình Voucher/ });
    await expect(modal).toBeVisible();
    await modal.getByRole("textbox", { name: "Tên chương trình" }).fill("Updated Branch Offer");
    await modal.getByRole("button", { name: "Lưu thay đổi" }).click();
    await expect.poll(() => saved).toBe(true);
    await expect(modal).toHaveCount(0);
    await page.reload();
    await expect(page.getByRole("row").filter({ hasText: "Updated Branch Offer" })).toBeVisible();
  });
}
