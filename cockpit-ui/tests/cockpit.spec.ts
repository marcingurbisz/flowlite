import { expect, test } from '@playwright/test';

test('loads cockpit and lists registered flows', async ({ page }) => {
  await page.goto('/index.html');

  await expect(page.getByRole('heading', { name: 'FlowLite Cockpit' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Flow Definitions' })).toBeVisible();
  await expect(page.getByText('order-confirmation').first()).toBeVisible();
  await expect(page.getByText('employee-onboarding').first()).toBeVisible();
});

test('opens instance details from Instances view', async ({ page }) => {
  await page.goto('/index.html');

  await page.getByRole('button', { name: 'Instances' }).click();
  await expect(page.getByPlaceholder('Search by instance ID or flow ID...')).toBeVisible();

  const firstRow = page.locator('tbody tr').first();
  await expect(firstRow).toBeVisible({ timeout: 30_000 });
  await firstRow.click();

  await expect(page.getByRole('heading', { name: 'Instance Details' })).toBeVisible();
  await expect(page.getByText('Event History')).toBeVisible();
});
