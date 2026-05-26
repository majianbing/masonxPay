import { expect, test } from '@playwright/test';

test.describe('routing policies dashboard', () => {
  test('refreshes the policy list page without client crash', async ({ page }) => {
    await page.goto('/routing/policies');
    await expect(page.getByRole('heading', { name: 'Routing Policies' })).toBeVisible();
    await expect(page.getByRole('link', { name: /New Policy/i })).toBeVisible();

    await page.reload();
    await expect(page.getByRole('heading', { name: 'Routing Policies' })).toBeVisible();
    await expect(page.getByText(/policies/i).first()).toBeVisible();
  });

  test('shows one routing navigation entry and hides legacy rules from normal navigation', async ({ page }) => {
    await page.goto('/routing/policies');

    const nav = page.getByRole('navigation');
    await expect(nav.getByRole('link', { name: /^Routing$/ })).toBeVisible();
    await expect(nav.getByRole('link', { name: /Routing Rules/i })).toHaveCount(0);
    await expect(nav.getByRole('link', { name: /Routing Policies/i })).toHaveCount(0);
  });

  test('opens dedicated create page from policy list', async ({ page }) => {
    await page.goto('/routing/policies');
    await page.getByRole('link', { name: /New Policy/i }).click();

    await expect(page).toHaveURL(/\/routing\/policies\/new$/);
    await expect(page.getByRole('heading', { name: 'New Routing Policy' })).toBeVisible();
    await expect(page.locator('input').first()).toHaveValue('Test checkout routing');
    await expect(page.getByText('Dry-run Simulation')).toBeVisible();
  });
});
