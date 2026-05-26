import { expect, test } from '@playwright/test';
import path from 'path';

const authFile = path.join(__dirname, '.auth/user.json');

test('save dashboard authenticated browser state', async ({ page }) => {
  test.setTimeout(180_000);

  await page.goto('/routing/policies');

  if (page.url().includes('/login')) {
    console.log('\nLog in with your dashboard test account in the opened browser window.');
    console.log('After login redirects to Routing Policies, this setup saves e2e/.auth/user.json.\n');
  }

  await expect(page.getByRole('heading', { name: 'Routing Policies' })).toBeVisible({ timeout: 120_000 });
  await page.context().storageState({ path: authFile });
});
