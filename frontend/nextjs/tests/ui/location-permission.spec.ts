import { expect, test } from "@playwright/test";

declare global {
  interface Window {
    locationTest: {
      available: boolean;
      permission: PermissionState;
      requests: number;
      status: EventTarget;
      fail: () => void;
      accuracy?: boolean[];
    };
  }
}

for (const failure of ["insecure", 1, 2, 3] as const) {
  test(`current-location failure ${failure} settles and remains retryable`, async ({ page }) => {
    await page.clock.install();
    await page.addInitScript(code => {
      sessionStorage.setItem("clucky_location_prompt", "seen");
      const status = new EventTarget();
      const control = window.locationTest = { available: false, permission: "prompt" as PermissionState,
        requests: 0, status, fail: () => {}, accuracy: [] as boolean[] };
      Object.defineProperty(status, "state", { get: () => control.permission });
      Object.defineProperty(navigator, "permissions", { configurable: true, value: { query: async () => status } });
      if (code === "insecure") Object.defineProperty(window, "isSecureContext", { configurable: true, value: false });
      Object.defineProperty(navigator, "geolocation", { configurable: true, value: {
        getCurrentPosition(success: PositionCallback, error: PositionErrorCallback, options: PositionOptions) {
          control.requests += 1;
          control.accuracy.push(Boolean(options.enableHighAccuracy));
          if (control.available) success({ coords: { latitude: 10.8, longitude: 106.7 } } as GeolocationPosition);
          else error({ code: code === "insecure" ? 1 : code, message: "Location unavailable", PERMISSION_DENIED: 1,
            POSITION_UNAVAILABLE: 2, TIMEOUT: 3 });
        },
      } });
    }, failure);
    let reverseRequests = 0;
    await page.route("**/api/locations/reverse?*", route => {
      reverseRequests += 1;
      expect(new URL(route.request().url()).searchParams.get("latitude")).toBe("10.8");
      return route.fulfill({ json: [{ latitude: 10.8, longitude: 106.7, label: "GPS address", detail: "GPS address",
        province: "Test city", district: "Test district", ward: "Test ward" }] });
    });
    await page.goto("http://127.0.0.1:4175/?location");
    await page.getByRole("button", { name: "Dùng vị trí hiện tại", exact: true }).click();
    const alert = page.locator('section[aria-label="Bản đồ vị trí"] > [role="alert"]');
    if (failure === 1) await page.clock.runFor(1200);
    if (failure === 2 || failure === 3) await page.clock.runFor(3400);
    await expect(alert).toBeVisible();
    await expect(alert).toContainText(failure === "insecure" ? "ngữ cảnh an toàn" : failure === 1 ? "chặn quyền vị trí" : failure === 2 ? "chưa cung cấp được tọa độ" : "hết thời gian chờ");
    const requests = failure === "insecure" ? 0 : failure === 1 ? 1 : 3;
    await expect.poll(() => page.evaluate(() => window.locationTest.requests)).toBe(requests);
    await page.evaluate(() => {
      window.dispatchEvent(new Event("focus"));
      document.dispatchEvent(new Event("visibilitychange"));
    });
    await page.clock.fastForward(6000);
    await expect(alert).toBeVisible();
    await expect(page.locator('[data-sonner-toast][data-type="warning"]')).toBeVisible();
    expect(await page.evaluate(() => window.locationTest.requests)).toBe(requests);
    expect(reverseRequests).toBe(0);
    await expect(page.getByLabel("Số nhà, đường, căn hộ", { exact: true })).toHaveValue("Old address");
    if (failure === "insecure") return;
    if (failure !== 1) expect(await page.evaluate(() => window.locationTest.accuracy)).toEqual([false, true, true]);
    await page.evaluate(() => {
      window.locationTest.available = true;
      window.locationTest.permission = "granted";
      window.locationTest.status.dispatchEvent(new Event("change"));
    });
    await page.getByRole("button", { name: "Dùng vị trí hiện tại", exact: true }).click();
    await expect(alert).toHaveCount(0);
    await expect(page.getByLabel("Số nhà, đường, căn hộ", { exact: true })).toHaveValue("GPS address");
    expect(reverseRequests).toBe(1);
  });
}

test("one click recovers when geolocation briefly reports denied before an allowed location is ready", async ({ page }) => {
  await page.addInitScript(() => {
    sessionStorage.setItem("clucky_location_prompt", "seen");
    const status = new EventTarget();
    const control = window.locationTest = { available: false, permission: "prompt" as PermissionState,
      requests: 0, status, fail: () => {} };
    Object.defineProperty(status, "state", { get: () => control.permission });
    Object.defineProperty(navigator, "permissions", { configurable: true, value: { query: async () => status } });
    Object.defineProperty(navigator, "geolocation", { configurable: true, value: {
      getCurrentPosition(success: PositionCallback, failure: PositionErrorCallback) {
        control.requests += 1;
        if (control.requests === 1) {
          control.permission = "granted";
          status.dispatchEvent(new Event("change"));
          failure({ code: 1, message: "Permission temporarily unavailable", PERMISSION_DENIED: 1,
            POSITION_UNAVAILABLE: 2, TIMEOUT: 3 });
        } else {
          success({ coords: { latitude: 10.8, longitude: 106.7, accuracy: 10, altitude: null,
            altitudeAccuracy: null, heading: null, speed: null }, timestamp: Date.now() } as GeolocationPosition);
        }
      },
    } });
  });
  let reverseRequests = 0;
  await page.route("**/api/locations/reverse?*", route => {
    reverseRequests += 1;
    return route.fulfill({ json: [{ latitude: 10.8, longitude: 106.7, label: "GPS address", detail: "GPS address",
      province: "Test city", district: "Test district", ward: "Test ward" }] });
  });
  await page.goto("http://127.0.0.1:4175/?location");
  await page.getByRole("button", { name: "Dùng vị trí hiện tại", exact: true }).click();
  await expect(page.locator('[data-sonner-toast][data-type="warning"]')).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => window.locationTest.requests)).toBe(2);
  await expect(page.getByLabel("Số nhà, đường, căn hộ", { exact: true })).toHaveValue("GPS address");
  expect(reverseRequests).toBe(1);
});

test("an already granted permission does not start a background request on page load", async ({ page }) => {
  await page.addInitScript(() => {
    sessionStorage.setItem("clucky_location_prompt", "seen");
    const control = window.locationTest = { available: true, permission: "granted" as PermissionState,
      requests: 0, status: new EventTarget(), fail: () => {} };
    Object.defineProperty(control.status, "state", { get: () => control.permission });
    Object.defineProperty(navigator, "permissions", { configurable: true, value: { query: async () => control.status } });
    Object.defineProperty(navigator, "geolocation", { configurable: true, value: {
      getCurrentPosition() { control.requests += 1; },
    } });
  });
  await page.goto("http://127.0.0.1:4175/?location");
  await expect.poll(() => page.evaluate(() => window.locationTest.requests)).toBe(0);
  await page.evaluate(() => { window.dispatchEvent(new Event("focus")); });
  await expect.poll(() => page.evaluate(() => window.locationTest.requests)).toBe(0);
  await expect(page.locator('[data-sonner-toast][data-type="warning"]')).toHaveCount(0);
});

test("an unresponsive geolocation request expires and permits another click", async ({ page }) => {
  await page.clock.install();
  await page.addInitScript(() => {
    sessionStorage.setItem("clucky_location_prompt", "seen");
    const control = window.locationTest = { available: false, permission: "granted" as PermissionState,
      requests: 0, status: new EventTarget(), fail: () => {} };
    Object.defineProperty(control.status, "state", { get: () => control.permission });
    Object.defineProperty(navigator, "permissions", { configurable: true, value: { query: async () => control.status } });
    Object.defineProperty(navigator, "geolocation", { configurable: true, value: {
      getCurrentPosition(success: PositionCallback) {
        control.requests += 1;
        if (control.available) {
          success({ coords: { latitude: 10.8, longitude: 106.7, accuracy: 10, altitude: null,
            altitudeAccuracy: null, heading: null, speed: null }, timestamp: Date.now() } as GeolocationPosition);
        }
      },
    } });
  });
  await page.route("**/api/locations/reverse?*", route => route.fulfill({ json: [
    { latitude: 10.8, longitude: 106.7, label: "GPS address", detail: "GPS address",
      province: "Test city", district: "Test district", ward: "Test ward" },
  ] }));
  await page.goto("http://127.0.0.1:4175/?location");
  await page.getByRole("button", { name: "Dùng vị trí hiện tại", exact: true }).click();
  await expect(page.getByRole("button", { name: "Đang xác định vị trí…" })).toBeDisabled();
  await page.clock.runFor(21000);
  await expect(page.locator('section[aria-label="Bản đồ vị trí"] > [role="alert"]')).toContainText("hết thời gian chờ");
  await page.evaluate(() => { window.locationTest.available = true; });
  await page.getByRole("button", { name: "Dùng vị trí hiện tại", exact: true }).click();
  await expect(page.getByLabel("Số nhà, đường, căn hộ", { exact: true })).toHaveValue("GPS address");
});
