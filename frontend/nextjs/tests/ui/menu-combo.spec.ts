import { expect, test } from "@playwright/test";

const customer = "http://127.0.0.1:4174";

test("menu links use typed combo URLs and add with comboId", async ({ page }) => {
  const additions: Record<string, unknown>[] = [];
  await page.route("**/api/auth/session", route => route.fulfill({ status: 401, json: { code: "UNAUTHENTICATED" } }));
  await page.route("**/api/auth/refresh", route => route.fulfill({ status: 401, json: { code: "UNAUTHENTICATED" } }));
  await page.route(/\/api\/catalog\/products(?:\?.*)?$/, route => route.fulfill({ json: [] }));
  await page.route(/\/api\/catalog\/categories(?:\?.*)?$/, route => route.fulfill({ json: [] }));
  await page.route("**/api/catalog/combos?**", route => route.fulfill({ json: [{
    id: 901, slug: "regression-combo-901", name: "Regression Combo", description: "Test combo",
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
  await page.getByRole("link", { name: /Regression Combo/ }).first().click();
  await expect(page).toHaveURL(/\/menu\/combo-901(?:\?|$)/);
  await expect(page.getByText("Regression Combo").first()).toBeVisible();
  await page.getByRole("button", { name: /Thêm vào giỏ/ }).click();

  await expect.poll(() => additions.length).toBe(1);
  expect(additions[0]).toMatchObject({ comboId: 901, optionIds: [], toppingIds: [] });
  expect(additions[0].productId).toBeUndefined();
});

test("legacy named combo URL resolves a combo despite an overlapping product ID", async ({ page }) => {
  const additions: Record<string, unknown>[] = [];
  await page.route("**/api/auth/session", route => route.fulfill({ status: 401, json: { code: "UNAUTHENTICATED" } }));
  await page.route("**/api/auth/refresh", route => route.fulfill({ status: 401, json: { code: "UNAUTHENTICATED" } }));
  await page.route(/\/api\/cart(?:\/|$)/, route => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/cart/items" && route.request().method() === "POST") {
      const payload = route.request().postDataJSON() as Record<string, unknown>;
      additions.push(payload);
      return route.fulfill({ json: {
        items: [{ ...payload, itemKey: "server-combo-1", productId: null, comboId: 1 }],
        subtotal: 120000, itemCount: 1,
      } });
    }
    return route.fulfill({ json: { items: [], subtotal: 0, itemCount: 0 } });
  });

  await page.goto(`${customer}/menu/combo-clucky-classic-1`);
  await expect(page.getByRole("heading", { name: "Combo Clucky Classic" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Sản phẩm trong combo" })).toBeVisible();
  const comboItem = page.getByRole("button", { name: /Crispy Chicken/ });
  await expect(comboItem).toHaveAttribute("aria-expanded", "true");
  await comboItem.click();
  await expect(comboItem).toHaveAttribute("aria-expanded", "false");
  await expect(page.getByText("Hot", { exact: true })).toBeHidden();
  await comboItem.click();
  await expect(comboItem).toHaveAttribute("aria-expanded", "true");
  await page.getByText("Hot", { exact: true }).click();
  await page.getByText("Cheese", { exact: true }).click();
  await expect(page.getByRole("button", { name: /Thêm vào giỏ hàng · 150\.000/ })).toBeEnabled();
  await page.getByRole("button", { name: /Thêm vào giỏ/ }).click();

  await expect.poll(() => additions.length).toBe(1);
  expect(additions[0]).toMatchObject({ comboId: 1, optionIds: [], toppingIds: [] });
  expect(additions[0]).toMatchObject({ comboSelections: [{ comboItemId: 101, optionIds: [12], toppingIds: [22] }] });
  expect(additions[0].unitPrice).toBe(150000);
  expect(additions[0].productId).toBeUndefined();
});
