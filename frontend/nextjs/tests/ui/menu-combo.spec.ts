import { expect, test } from "@playwright/test";

const customer = "http://127.0.0.1:4174";

test("a combo with a name-derived catalog slug opens its detail and adds as a combo", async ({ page }) => {
  const additions: Record<string, unknown>[] = [];
  await page.route("**/api/auth/session", route => route.fulfill({ status: 401, json: { code: "UNAUTHENTICATED" } }));
  await page.route("**/api/auth/refresh", route => route.fulfill({ status: 401, json: { code: "UNAUTHENTICATED" } }));
  await page.route("**/api/catalog/combos?**", route => route.fulfill({ json: [{
    id: 901, slug: "family-feast-901", name: "Family Feast 901", description: "Test combo",
    comboPrice: 120000, isActive: true, isAvailable: true, items: [],
  }] }));
  await page.route(/\/api\/cart(?:\/|$)/, route => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/cart/items" && route.request().method() === "POST") {
      const payload = route.request().postDataJSON() as Record<string, unknown>;
      additions.push(payload);
      return route.fulfill({ json: {
        items: [{ ...payload, itemKey: "server-combo-901", productId: null, comboId: 901, lineTotal: 120000 }],
        subtotal: 120000, itemCount: 1,
      } });
    }
    return route.fulfill({ json: { items: [], subtotal: 0, itemCount: 0 } });
  });

  await page.goto(`${customer}/menu`);
  await page.getByRole("link", { name: "Family Feast 901", exact: true }).click();
  await expect(page).toHaveURL(/\/menu\/combo-901(?:\?|$)/);
  await expect(page.getByText("Regression Combo").first()).toBeVisible();
  await page.getByRole("button", { name: /Thêm vào giỏ/ }).click();

  await expect.poll(() => additions.length).toBe(1);
  expect(additions[0]).toMatchObject({ comboId: 901, optionIds: [], toppingIds: [] });
  expect(additions[0].productId).toBeUndefined();
});
