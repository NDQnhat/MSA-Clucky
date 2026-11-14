import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/ui",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI
    ? [["github"], ["html", { open: "never" }]]
    : "list",
  // Production Next.js + admin preview + API fixtures share the CI runner.
  // A single browser worker avoids startup contention and SSR hydration races.
  workers: process.env.CI ? 1 : 2,
  timeout: 60000,
  use: { browserName: "chromium", headless: true, trace: "retain-on-failure" },
  webServer: [
    {
      command:
        "npm --prefix ../react run preview -- --host 127.0.0.1 --port 4173 --strictPort",
      url: "http://127.0.0.1:4173",
      reuseExistingServer: false,
    },
    {
      command: "node tests/fixtures/server.mjs",
      url: "http://127.0.0.1:4175",
      reuseExistingServer: false,
    },
    {
      command: "npm run start -- --hostname 127.0.0.1 --port 4174",
      url: "http://127.0.0.1:4174",
      reuseExistingServer: false,
      env: {
        API_URL: "http://127.0.0.1:4175",
        INTERNAL_API_URL: "http://127.0.0.1:4175",
        NEXT_PUBLIC_API_URL: "http://127.0.0.1:4175",
      },
    },
  ],
});
