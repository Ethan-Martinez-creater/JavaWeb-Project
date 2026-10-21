import { mkdir } from 'node:fs/promises';
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 10_000 },
  // The custom reporter keeps failure call logs out of artifacts because those can include form input.
  reporter: [['./safe-json-reporter.ts']],
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.BASE_URL ?? 'http://127.0.0.1:8088',
    locale: 'zh-CN',
    timezoneId: 'UTC',
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
});
