import { expect, test, type Page, type Route } from '@playwright/test'

const credentials = Buffer.from('user:secret').toString('base64')

test.setTimeout(60_000)

const queues = [
  {
    name: 'import.task.dlq',
    exchange: 'comic.import',
    routingKey: 'task.created',
    originalQueue: 'import.task.queue',
    messages: 3,
    consumers: 0,
  },
  {
    name: 'export.failed.result.dlq',
    exchange: 'comic.export',
    routingKey: 'task.failed',
    originalQueue: 'export.failed.result.queue',
    messages: 1,
    consumers: 0,
  },
]

test.beforeEach(async ({ page }) => {
  await page.route('**/api/admin/dlq/**', handleDlqRequest)
})

test('需要凭据，并提供只读预览与受确认保护的批量重放', async ({ page }) => {
  await page.goto('/manage/dlq?force-desktop=1')

  await expect(page.getByRole('heading', { name: '验证管理凭据' })).toBeVisible()
  await page.getByPlaceholder('输入服务启动时生成或配置的密码').fill('wrong')
  await page.getByRole('button', { name: '连接管理接口' }).click()
  await expect(page.getByRole('alert')).toContainText('凭据无效')

  await page.getByPlaceholder('输入服务启动时生成或配置的密码').fill('secret')
  await page.getByRole('button', { name: '连接管理接口' }).click()

  await expect(page.getByRole('heading', { name: '队列账册' })).toBeVisible()
  await expect(page.getByText('4', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: '预览' }).first().click()
  await expect(page.getByText('预览不会确认或删除消息')).toBeVisible()
  await expect(page.getByText('"taskId": 42')).toBeVisible()
  await page.getByRole('button', { name: 'Close this dialog' }).click()

  await page.getByRole('button', { name: '重放' }).first().click()
  await page.getByRole('button', { name: '确认', exact: true }).click()
  await expect(page.getByText('重放完成：3 条')).toBeVisible()
})

async function handleDlqRequest(route: Route) {
  const request = route.request()
  if (request.headers()['authorization'] !== `Basic ${credentials}`) {
    await route.fulfill({ status: 401, body: '' })
    return
  }

  const url = new URL(request.url())
  if (request.method() === 'GET' && url.pathname.endsWith('/queues')) {
    await json(route, queues)
    return
  }
  if (request.method() === 'GET' && url.pathname.endsWith('/messages')) {
    await json(route, [
      {
        payload: '{"taskId":42,"reason":"metadata invalid"}',
        payloadEncoding: 'string',
        properties: {
          contentType: 'application/json',
          messageId: 'message-42',
        },
        messagesRemaining: 2,
      },
    ])
    return
  }
  if (request.method() === 'POST' && url.pathname.endsWith('/replay')) {
    await json(route, {
      queue: 'import.task.dlq',
      attempted: 3,
      replayed: 3,
      remaining: 0,
      completed: true,
      error: null,
    })
    return
  }
  await route.fulfill({ status: 404, body: '' })
}

async function json(route: Route, data: unknown) {
  await route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, message: 'success', data }),
  })
}
