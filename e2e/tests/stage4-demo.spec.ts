import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { expect, test, type Page, type TestInfo } from '@playwright/test';

const outputDirectory = process.env.PLAYWRIGHT_OUTPUT_DIR ?? 'artifacts';
const baseURL = process.env.BASE_URL ?? 'http://127.0.0.1:8088';
const organizerEmail = process.env.E2E_ORGANIZER_EMAIL;
const organizerPassword = process.env.E2E_ORGANIZER_PASSWORD;
const completedSteps: string[] = [];
const browserErrorNames: string[] = [];
let publicDetailStatus: number | null = null;

function requireOrganizerCredentials(): { email: string; password: string } {
  if (!organizerEmail || !organizerPassword) {
    throw new Error('Set the dev organizer credentials in the isolated E2E environment.');
  }
  return { email: organizerEmail, password: organizerPassword };
}

function utcDateTimeInput(value: Date): string {
  const pad = (part: number) => part.toString().padStart(2, '0');
  return `${value.getUTCFullYear()}-${pad(value.getUTCMonth() + 1)}-${pad(value.getUTCDate())}T${pad(value.getUTCHours())}:${pad(value.getUTCMinutes())}`;
}

function fieldInput(page: Page, testId: string) {
  // ElInput forwards attrs to the native input; ElInputNumber keeps them on its wrapper.
  const selector = `[data-testid="${testId}"]`;
  return page.locator(`${selector}:is(input, textarea), ${selector} input, ${selector} textarea`).first();
}

async function login(page: Page, email: string, password: string): Promise<string> {
  const responsePromise = page.waitForResponse(
    (response) => response.request().method() === 'POST' && response.url().endsWith('/api/v1/auth/login'),
  );
  await page.goto('/login');
  await fieldInput(page, 'auth-email').fill(email);
  await fieldInput(page, 'auth-password').fill(password);
  await page.getByTestId('auth-submit').click();

  const response = await responsePromise;
  expect(response.status()).toBe(200);
  const tokenResponse = (await response.json()) as { accessToken: string };
  await expect(page.getByTestId('logout')).toBeVisible();
  return tokenResponse.accessToken;
}

async function writeEvidence(testInfo: TestInfo): Promise<void> {
  await mkdir(outputDirectory, { recursive: true });
  const evidence = {
    schemaVersion: 1,
    scenario: 'organizer-publishes-user-pays-ticket-checkin-notification',
    testStatus: testInfo.status,
    retry: testInfo.retry,
    durationMs: testInfo.duration,
    completedSteps: [...completedSteps],
    browserErrorNames: [...browserErrorNames],
    publicDetailStatus,
    sensitiveValuesRecorded: false,
  };
  await writeFile(
    path.join(outputDirectory, 'stage-4-e2e-evidence.json'),
    `${JSON.stringify(evidence, null, 2)}\n`,
    'utf8',
  );
}

test.afterEach(async ({}, testInfo) => {
  await writeEvidence(testInfo);
});

test('real UI publishes, reserves, pays, rotates, checks in, and notifies', async ({ page }) => {
  completedSteps.length = 0;
  browserErrorNames.length = 0;
  publicDetailStatus = null;
  // Keep only error types: arbitrary browser messages can contain private form values.
  page.on('pageerror', (error) => browserErrorNames.push(error.name));
  page.on('console', (message) => {
    if (message.type() !== 'error') return;
    // Vue catches render/setup exceptions; they reach console.error instead of pageerror.
    const errorType = /^(TypeError|ReferenceError|RangeError|SyntaxError|Error)\b/.exec(message.text())?.[1];
    if (errorType) browserErrorNames.push(errorType);
  });
  const credentials = requireOrganizerCredentials();
  const eventTitle = `E2E event ${randomUUID().slice(0, 8)}`;
  const userEmail = `stage4-${randomUUID()}@example.test`;
  const userPassword = randomUUID().replaceAll('-', '');

  const anonymousOrganizerResponse = await page.request.get(new URL('/api/v1/organizer/events', baseURL).toString());
  expect(anonymousOrganizerResponse.status()).toBe(401);
  const anonymousError = (await anonymousOrganizerResponse.json()) as Record<string, unknown>;
  expect(anonymousError).toMatchObject({ code: 'UNAUTHORIZED' });
  for (const field of ['message', 'requestId', 'timestamp']) {
    expect(typeof anonymousError[field]).toBe('string');
  }
  completedSteps.push('anonymous request returns structured 401');

  await login(page, credentials.email, credentials.password);
  await page.getByTestId('nav-organizer-events').click();
  await page.getByTestId('create-event').click();
  await fieldInput(page, 'event-title-input').fill(eventTitle);
  await fieldInput(page, 'event-description-input').fill('真实 MySQL 与 RabbitMQ 全栈浏览器验收。');
  const createEventResponsePromise = page.waitForResponse(
    (response) => response.request().method() === 'POST' && response.url().endsWith('/api/v1/organizer/events'),
  );
  await page.getByTestId('save-event').click();
  const createEventResponse = await createEventResponsePromise;
  expect(createEventResponse.ok()).toBe(true);
  const createdEvent = (await createEventResponse.json()) as { id: number };
  const eventId = createdEvent.id;
  expect(Number.isSafeInteger(eventId)).toBe(true);
  await expect(page).toHaveURL(new RegExp(`/organizer/events/${eventId}/edit$`));
  await expect(fieldInput(page, 'event-title-input')).toHaveValue(eventTitle);

  await fieldInput(page, 'event-description-input').fill('已编辑的真实 MySQL 与 RabbitMQ 全栈浏览器验收。');
  const editEventResponsePromise = page.waitForResponse(
    (response) => response.request().method() === 'PATCH' && response.url().endsWith(`/api/v1/organizer/events/${eventId}`),
  );
  await page.getByTestId('save-event').click();
  const editEventResponse = await editEventResponsePromise;
  expect(editEventResponse.ok()).toBe(true);
  completedSteps.push('organizer edited the DRAFT event through the UI');

  const startsAt = new Date(Date.now() + 35 * 60 * 1000);
  startsAt.setUTCSeconds(0, 0);
  const endsAt = new Date(startsAt.getTime() + 90 * 60 * 1000);
  await fieldInput(page, 'session-title-input').fill('晚场');
  await fieldInput(page, 'session-starts-at').fill(utcDateTimeInput(startsAt));
  await fieldInput(page, 'session-ends-at').fill(utcDateTimeInput(endsAt));
  await fieldInput(page, 'session-location').fill('全栈测试厅');
  const addSessionResponsePromise = page.waitForResponse(
    (response) => response.request().method() === 'POST' && response.url().endsWith(`/api/v1/organizer/events/${eventId}/sessions`),
  );
  await page.getByTestId('add-session').click();
  const addSessionResponse = await addSessionResponsePromise;
  expect(addSessionResponse.ok()).toBe(true);
  const createdSession = (await addSessionResponse.json()) as { id: number };
  await expect(page.getByTestId('sku-name-input')).toBeVisible();
  await fieldInput(page, 'sku-name-input').fill('标准票');
  await fieldInput(page, 'sku-price-cents').fill('2500');
  await fieldInput(page, 'sku-stock').fill('1');
  const addSkuResponsePromise = page.waitForResponse(
    (response) => response.request().method() === 'POST' && response.url().endsWith(`/api/v1/organizer/sessions/${createdSession.id}/ticket-skus`),
  );
  await page.getByTestId('add-sku').click();
  const addSkuResponse = await addSkuResponsePromise;
  expect(addSkuResponse.ok()).toBe(true);
  const publishResponsePromise = page.waitForResponse(
    (response) => response.request().method() === 'POST' && response.url().endsWith(`/api/v1/organizer/events/${eventId}/publish`),
  );
  await page.getByTestId('publish-event').click();
  const publishResponse = await publishResponsePromise;
  expect(publishResponse.ok()).toBe(true);
  await expect(page.getByText('已发布', { exact: true })).toBeVisible();
  completedSteps.push('dev organizer created a real event, session, SKU, then published it');

  // Capture only the anonymous public listing; account details and one-time codes stay out of screenshots.
  await page.getByTestId('logout').click();
  await expect(page.getByRole('link', { name: '注册' })).toBeVisible();
  await fieldInput(page, 'event-search').fill(eventTitle);
  await fieldInput(page, 'event-search').press('Enter');
  await expect(page.getByTestId(`event-card-${eventId}`)).toBeVisible();
  await mkdir(outputDirectory, { recursive: true });
  await page.screenshot({ path: path.join(outputDirectory, 'events-listing-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true);
  await page.screenshot({ path: path.join(outputDirectory, 'events-listing-mobile.png'), fullPage: true });
  await page.setViewportSize({ width: 1440, height: 960 });
  completedSteps.push('real public Vue listing rendered on desktop and mobile without horizontal overflow');

  await page.goto('/register');
  await fieldInput(page, 'auth-email').fill(userEmail);
  await fieldInput(page, 'auth-password').fill(userPassword);
  const registerResponsePromise = page.waitForResponse(
    (response) => response.request().method() === 'POST' && response.url().endsWith('/api/v1/auth/register'),
  );
  await page.getByTestId('auth-submit').click();
  const registerResponse = await registerResponsePromise;
  expect(registerResponse.ok()).toBe(true);
  const registeredUser = (await registerResponse.json()) as { roles: string[] };
  expect(registeredUser.roles).toEqual(['USER']);
  await expect(page).toHaveURL(/\/login/);
  const userToken = await login(page, userEmail, userPassword);
  await expect(page.getByTestId('nav-organizer-events')).toBeHidden();
  const organizerAccess = await page.request.get(new URL('/api/v1/organizer/events', baseURL).toString(), {
    headers: { Authorization: `Bearer ${userToken}` },
  });
  expect(organizerAccess.status()).toBe(403);
  completedSteps.push('public registration created USER only and backend returned 403 for organizer API');

  await expect(page.getByTestId('event-search')).toBeVisible();
  await fieldInput(page, 'event-search').fill(eventTitle);
  await fieldInput(page, 'event-search').press('Enter');
  await expect(page.getByTestId(`event-card-${eventId}`)).toBeVisible();
  const detailResponsePromise = page.waitForResponse(
    (response) => response.request().method() === 'GET' && response.url().endsWith(`/api/v1/events/${eventId}`),
  );
  await page.getByTestId(`event-link-${eventId}`).click();
  const detailResponse = await detailResponsePromise;
  publicDetailStatus = detailResponse.status();
  expect(publicDetailStatus).toBe(200);
  const publicDetail = await detailResponse.json();
  expect(publicDetail).toMatchObject({ id: eventId, title: eventTitle, status: 'PUBLISHED' });
  expect(publicDetail.sessions).toHaveLength(1);
  await expect(page.getByTestId('event-title')).toContainText(eventTitle);
  await page.getByTestId('refresh-event').click();
  await page.getByTestId('sku-select').selectOption({ index: 0 });
  const reserveResponsePromise = page.waitForResponse(
    (response) => response.request().method() === 'POST' && response.url().endsWith('/api/v1/orders'),
  );
  await page.getByTestId('reserve-order').click();
  const reserveResponse = await reserveResponsePromise;
  expect(reserveResponse.ok()).toBe(true);
  await expect(page).toHaveURL(/\/me\/orders\/\d+$/);
  await expect(page.getByTestId('pay-order')).toBeVisible();
  completedSteps.push('USER browsed the published event and reserved one unit with the UI idempotency key');

  const payResponsePromise = page.waitForResponse(
    (response) => response.request().method() === 'POST' && /\/api\/v1\/orders\/\d+\/pay$/.test(response.url()),
  );
  await page.getByTestId('pay-order').click();
  const payResponse = await payResponsePromise;
  expect(payResponse.ok()).toBe(true);
  await expect(page.getByText('支付已完成，电子票已签发。')).toBeVisible();
  await page.getByTestId('nav-tickets').click();
  await expect(page.getByTestId('rotate-checkin-code')).toBeVisible();
  const rotateResponse = () =>
    page.waitForResponse(
      (response) => response.request().method() === 'POST' && /\/api\/v1\/me\/tickets\/\d+\/checkin-code$/.test(response.url()),
    );
  const firstRotateResponsePromise = rotateResponse();
  await page.getByTestId('rotate-checkin-code').click();
  const firstRotateResponse = await firstRotateResponsePromise;
  expect(firstRotateResponse.ok()).toBe(true);
  const firstCode = await page.getByTestId('checkin-code').innerText();
  expect(firstCode).toMatch(/^[A-Za-z0-9_-]{43}$/);
  const secondRotateResponsePromise = rotateResponse();
  await page.getByTestId('rotate-checkin-code').click();
  const secondRotateResponse = await secondRotateResponsePromise;
  expect(secondRotateResponse.ok()).toBe(true);
  const currentCode = await page.getByTestId('checkin-code').innerText();
  expect(currentCode).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(currentCode).not.toBe(firstCode);
  completedSteps.push('mock payment issued a ticket; rotating the QR code invalidated the prior code');

  await page.getByTestId('logout').click();
  await login(page, credentials.email, credentials.password);
  await page.getByTestId('nav-checkin').click();
  await fieldInput(page, 'checkin-code-input').fill(firstCode);
  const rejectedCheckinResponsePromise = page.waitForResponse(
    (response) => response.request().method() === 'POST' && response.url().endsWith('/api/v1/organizer/tickets/check-in'),
  );
  await page.getByTestId('checkin-submit').click();
  const rejectedCheckinResponse = await rejectedCheckinResponsePromise;
  expect(rejectedCheckinResponse.status()).toBe(404);
  // Ignore transient login toasts; assert the persistent API failure notice.
  const checkinFailureAlert = page.locator('.error-notice').getByRole('alert');
  await expect(checkinFailureAlert).toHaveCount(1);
  await expect(checkinFailureAlert).toBeVisible();
  await expect(page.getByTestId('checkin-result-status')).toBeHidden();
  await fieldInput(page, 'checkin-code-input').fill(currentCode);
  const successfulCheckinResponsePromise = page.waitForResponse(
    (response) => response.request().method() === 'POST' && response.url().endsWith('/api/v1/organizer/tickets/check-in'),
  );
  await page.getByTestId('checkin-submit').click();
  const successfulCheckinResponse = await successfulCheckinResponsePromise;
  expect(successfulCheckinResponse.ok()).toBe(true);
  await expect(page.getByTestId('checkin-result-status')).toContainText('USED');
  completedSteps.push('organizer rejected the rotated-away code and successfully checked in with the current code');

  await page.getByTestId('logout').click();
  await login(page, userEmail, userPassword);
  await page.getByTestId('nav-tickets').click();
  await expect(page.getByTestId('ticket-status')).toContainText('已核销');
  completedSteps.push('USER can see the same ticket in USED state');

  await page.getByTestId('nav-notifications').click();
  await expect(page.getByTestId('notification-list')).toBeVisible();
  await expect
    .poll(
      async () => {
        await page.getByTestId('refresh-notifications').click();
        return page.getByTestId('notification-list').innerText();
      },
      { timeout: 30_000, intervals: [250, 500, 1000, 2000] },
    )
    .toContain('was paid.');
  completedSteps.push('RabbitMQ consumer persisted and UI displayed the ORDER_PAID notification');
  expect(browserErrorNames).toEqual([]);
});
