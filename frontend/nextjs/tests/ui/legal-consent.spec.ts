import { expect, test, type Page } from "@playwright/test";
import { seedApi } from "../fixtures/api";

const customer = "http://127.0.0.1:4174";
const terms = "TERMS_OF_SERVICE";
const privacy = "PRIVACY_POLICY";

async function fillRegistration(page: Page) {
  await page.getByRole("textbox", { name: "Họ và tên" }).fill("Nguyễn Văn An");
  await page.getByRole("textbox", { name: "Số điện thoại nhận OTP" }).fill("0901 234 567");
  await page.locator('input[name="password"]:visible').fill("Password1");
}

test.beforeEach(async ({ page }) => {
  await seedApi(page);
});

test("registration without published legal documents hides checkbox and omits consent", async ({ page }) => {
  const submitted: unknown[] = [];
  let documentRequests = 0;
  await page.route("**/api/legal/public/status", route => route.fulfill({ json: {
    consentRequired: false, termsPublished: false, privacyPublished: false, documentsPublished: false,
  } }));
  await page.route(/\/api\/legal\/public\/(?:TERMS_OF_SERVICE|PRIVACY_POLICY)$/, route => {
    documentRequests++;
    return route.fulfill({ status: 404 });
  });
  await page.route("**/api/auth/register", route => {
    submitted.push(route.request().postDataJSON());
    return route.fulfill({ status: 201, json: { challengeId: "none-published" } });
  });
  await page.goto(customer + "/register");
  await fillRegistration(page);
  const register = page.getByRole("button", { name: "Đăng ký", exact: true });
  await expect(register).toBeEnabled();
  await expect(page.locator("#clucky-phone-consent")).toHaveCount(0);
  await register.click();
  await expect(page).toHaveURL(/\/verify-otp\?phone=0901234567&challengeId=none-published/);
  expect(documentRequests).toBe(0);
  expect(submitted).toEqual([{ fullName: "Nguyễn Văn An", phone: "0901234567", password: "Password1" }]);
});

for (const publishedType of [terms, privacy]) {
  test(`registration with only ${publishedType} requires only that document and version`, async ({ page }) => {
    const submitted: unknown[] = [];
    const fetched: string[] = [];
    await page.route("**/api/legal/public/status", route => route.fulfill({ json: {
      consentRequired: true, termsPublished: publishedType === terms, privacyPublished: publishedType === privacy,
      documentsPublished: false,
    } }));
    await page.route(/\/api\/legal\/public\/(?:TERMS_OF_SERVICE|PRIVACY_POLICY)$/, route => {
      const type = route.request().url().split("/").at(-1)!;
      fetched.push(type);
      return route.fulfill({ json: {
        type, version: type === terms ? 7 : 4,
        titleVi: "Tài liệu được xuất bản", titleEn: "Published document",
        htmlVi: "<p>Bản đang xuất bản.</p>", htmlEn: "<p>Published revision.</p>",
        publishedAt: "2026-11-19T00:00:00Z",
      } });
    });
    await page.route("**/api/auth/register", route => {
      submitted.push(route.request().postDataJSON());
      return route.fulfill({ status: 201, json: { challengeId: "one-published" } });
    });
    await page.goto(customer + "/register");
    await fillRegistration(page);
    const consent = page.locator("#clucky-phone-consent");
    await expect(consent).toBeVisible();
    const message = page.locator('label[for="clucky-phone-consent"]').locator("..");
    await expect(message.locator('a[href="/terms-of-service"]')).toHaveCount(publishedType === terms ? 1 : 0);
    await expect(message.locator('a[href="/privacy-policy"]')).toHaveCount(publishedType === privacy ? 1 : 0);
    await page.getByRole("button", { name: "Đăng ký", exact: true }).click();
    expect(submitted).toHaveLength(0);
    await consent.check();
    await page.getByRole("button", { name: "Đăng ký", exact: true }).click();
    await expect(page).toHaveURL(/\/verify-otp\?phone=0901234567&challengeId=one-published/);
    expect(fetched).toEqual([publishedType]);
    expect(submitted).toEqual([{
      fullName: "Nguyễn Văn An", phone: "0901234567", password: "Password1",
      consent: publishedType === terms
        ? { accepted: true, termsVersion: 7 }
        : { accepted: true, privacyVersion: 4 },
    }]);
  });
}

test("missing or inconsistent status blocks registration until successful retry", async ({ page }) => {
  let valid = false;
  let submitted = 0;
  await page.route("**/api/legal/public/status", route => route.fulfill({ json: valid
    ? { consentRequired: false, termsPublished: false, privacyPublished: false }
    : { consentRequired: false, documentsPublished: false },
  }));
  await page.route("**/api/auth/register", route => {
    submitted++;
    return route.fulfill({ status: 201, json: { challengeId: "retry" } });
  });
  await page.goto(customer + "/register");
  await fillRegistration(page);
  const register = page.getByRole("button", { name: "Đăng ký", exact: true });
  await expect(page.getByRole("alert").filter({ hasText: "Không thể kiểm tra trạng thái tài liệu pháp lý" }))
    .toBeVisible();
  await expect(register).toBeDisabled();
  expect(submitted).toBe(0);
  valid = true;
  await page.getByRole("button", { name: "Tải lại tài liệu" }).click();
  await expect(page.locator("#clucky-phone-consent")).toHaveCount(0);
  await expect(register).toBeEnabled();
  await register.click();
  await expect(page).toHaveURL(/\/verify-otp\?phone=0901234567&challengeId=retry/);
  expect(submitted).toBe(1);
});
