import { expect, test } from "@playwright/test";
import { seedApi, testStore } from "../fixtures/api";

const admin = "http://127.0.0.1:4173";
const challengeId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";

test("branch activation confirms the code and returns to login", async ({ page }) => {
  await page.route("**/api/auth/session", route => route.fulfill({ status: 401, json: { code: "UNAUTHENTICATED" } }));
  await page.route("**/api/auth/refresh", route => route.fulfill({ status: 401, json: { code: "UNAUTHENTICATED" } }));
  await page.route("**/api/auth/logout", route => route.fulfill({ status: 204, body: "" }));
  let requestBody: unknown;
  await page.route("**/api/auth/branch/finish", route => {
    requestBody = route.request().postDataJSON();
    return route.fulfill({ status: 204, body: "" });
  });

  await page.goto(admin + "/reset-password?login=staff.6&purpose=ACTIVATE");
  await page.getByRole("textbox", { name: "Mã challenge" }).fill(challengeId);
  await page.getByRole("textbox", { name: "OTP 8 số" }).fill("12345678");
  await page.getByLabel("Mật khẩu mới", { exact: true }).fill("CluckyPassword123");
  await page.getByLabel("Nhập lại mật khẩu mới").fill("CluckyPassword123");
  await page.getByRole("button", { name: "Xác minh và đặt mật khẩu" }).click();

  await expect(page).toHaveURL(admin + "/login");
  await expect(page.getByText("Đã cập nhật mật khẩu thành công. Vui lòng đăng nhập bằng mật khẩu mới.")).toBeVisible();
  expect(requestBody).toEqual({ challengeId, otp: "12345678", password: "CluckyPassword123" });
});

test("manager changes only own password through a session-bound OTP flow", async ({ page }) => {
  await seedApi(page);
  let signedIn = true;
  const requests: unknown[] = [];
  await page.route("**/api/auth/session", route => signedIn
    ? route.fulfill({ json: { userId: 9, role: "STORE_MANAGER", storeId: 1, email: "recovery@example.com", fullName: "manager.1" } })
    : route.fulfill({ status: 401, json: { code: "UNAUTHENTICATED" } }));
  await page.route("**/api/auth/refresh", route => route.fulfill({ status: 401, json: { code: "UNAUTHENTICATED" } }));
  await page.route("**/api/auth/logout", route => route.fulfill({ status: 204, body: "" }));
  await page.route("**/api/auth/branch/change-password/request", route => {
    requests.push(route.request().postDataJSON());
    return route.fulfill({ json: { challengeId } });
  });
  await page.route("**/api/auth/branch/change-password/confirm", route => {
    requests.push(route.request().postDataJSON());
    signedIn = false;
    return route.fulfill({ status: 204, body: "" });
  });

  await page.goto(admin + "/admin/change-password");
  await expect(page.getByRole("heading", { name: "Đổi mật khẩu tài khoản" })).toBeVisible();
  await page.getByLabel("Mật khẩu hiện tại").fill("OldPassword123");
  await page.getByRole("button", { name: "Gửi mã xác minh" }).click();
  await page.getByLabel("OTP 8 số").fill("12345678");
  await page.getByLabel("Mật khẩu mới", { exact: true }).fill("NewPassword123");
  await page.getByLabel("Nhập lại mật khẩu mới").fill("NewPassword123");
  await page.getByRole("button", { name: "Xác minh và đổi mật khẩu" }).click();

  await expect(page).toHaveURL(admin + "/login");
  await expect(page.getByText("Đã cập nhật mật khẩu thành công. Vui lòng đăng nhập bằng mật khẩu mới.")).toBeVisible();
  expect(requests).toEqual([
    { currentPassword: "OldPassword123" },
    { challengeId, otp: "12345678", newPassword: "NewPassword123" },
  ]);
});

test("branch account page has one shared recovery-email control", async ({ page }) => {
  await seedApi(page);
  await page.route("**/api/auth/session", route => route.fulfill({ json: {
    userId: 9, role: "STORE_MANAGER", storeId: testStore.id, email: "recovery@example.com", fullName: "manager.1",
  } }));
  await page.route("**/api/stores/1/accounts-status", route => route.fulfill({ json: { readiness: "READY", error: "" } }));
  await page.route("**/api/auth/branch/stores/1", route => route.fulfill({ json: {
    storeId: 1, readiness: "READY", recoveryEmail: "recovery@example.com", verified: true,
    accounts: [
      { id: 9, role: "STORE_MANAGER", status: "ACTIVE", login: "manager.1", activated: true, current: true },
      { id: 10, role: "STAFF", status: "ACTIVE", login: "staff.1", activated: true, current: true },
    ],
  } }));

  await page.goto(admin + "/admin/users");
  await expect(page.getByText("manager.1").first()).toBeVisible();
  await expect(page.getByText("staff.1")).toBeVisible();
  await expect(page.getByRole("button", { name: "Đổi email khôi phục" })).toHaveCount(1);
  await expect(page.getByRole("link", { name: "Đổi mật khẩu" })).toHaveCount(1);
  await page.getByRole("button", { name: "Đổi email khôi phục" }).click();
  await expect(page.getByRole("dialog")).toContainText("Email mới sẽ là email khôi phục chung của cả Manager và Staff");
});
