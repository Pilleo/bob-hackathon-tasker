import { test, expect } from '@playwright/test'

test('shows the real backlog with four selected tasks and specific skip reasons', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: /Let agents work in parallel/i })).toBeVisible()
  await expect(page.getByText('11 tasks', { exact: true })).toBeVisible()
  await expect(page.getByTestId('selected-task')).toHaveCount(4)
  await expect(page.getByText('Already selected: Deduplicate normalized names in a batch')).toBeVisible()
  await expect(page.getByText('Waiting for Accept names from command-line arguments')).toBeVisible()
  await page.screenshot({ path: '/tmp/bob-scheduler-demo-desktop.png', fullPage: true })
})

test('completing a prerequisite unlocks its dependent only after recomputation', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: 'Dependency chain' }).click()
  await expect(page.getByText('Waiting for Fix name validation')).toBeVisible()
  await page.getByRole('button', { name: 'Complete Fix name validation' }).click()
  await expect(page.getByText('Changes pending — recompute to see the new proposal.')).toBeVisible()
  await page.getByRole('button', { name: 'Propose batch' }).click()
  await expect(page.getByTestId('selected-task').getByText('Integrate validation in batch')).toBeVisible()
  await page.getByRole('button', { name: 'Reset scenario' }).click()
  await expect(page.getByText('Waiting for Fix name validation')).toBeVisible()
})

test('highlights the actual overlapping file in a conflict scenario', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: 'File collisions' }).click()
  await expect(page.getByText('Running conflict', { exact: true })).toBeVisible()
  await expect(page.getByText('Selected conflict', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Inspect Alternative normalization' }).click()
  await expect(page.getByText('Shared file: src/NameNormalizer.kt')).toBeVisible()
  await expect(page.locator('[data-conflict-highlight="true"]')).toHaveCount(2)
})

test('affinity changes the last slot without claiming measured savings', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: 'Cache affinity' }).click()
  await expect(page.getByTestId('selected-task').getByText('Add validator tests')).toBeVisible()
  await page.getByRole('switch', { name: 'Prefer shared context' }).click()
  await page.getByRole('button', { name: 'Propose batch' }).click()
  await expect(page.getByTestId('selected-task').getByText('Build an unrelated feature')).toBeVisible()
  await expect(page.getByTestId('selected-task').getByText('Add validator tests')).toHaveCount(0)
})

test('mobile view fits without horizontal scrolling', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await expect(page.getByRole('button', { name: 'Propose batch' })).toBeVisible()
  const width = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, viewport: innerWidth }))
  expect(width.scroll).toBeLessThanOrEqual(width.viewport)
  const results = await page.locator('.results-panel').boundingBox()
  const queue = await page.locator('.tasks-panel').boundingBox()
  expect(results!.y).toBeLessThan(queue!.y)
  await page.screenshot({ path: '/tmp/bob-scheduler-demo-mobile.png', fullPage: true })
})
