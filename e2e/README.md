# 测试目录导航

浏览器测试按依赖与运行方式分为两套，分别保留自己的配置和依赖锁文件。

| 目录 | 运行方式与范围 |
| --- | --- |
| `e2e/tests/` | 跨服务测试；连接已运行的应用，默认 `http://localhost:80`，可通过 `BASE_URL` 覆盖 |
| `frontend/e2e/` | 前端页面测试；配置通过 `pnpm run dev` 启动或复用 5173 开发服务 |
| `frontend/e2e/_disabled/` | 被前端 Playwright 配置明确排除的场景 |
| [frontend/e2e-legacy](../frontend/e2e-legacy/README.md) | 历史测试，保留参考，不作为运行目标 |
| `frontend/src/**/*.test.ts` | Vitest 单元测试，随对应业务代码维护 |
| [scripts/qa](../scripts/qa/README.md) | 管理链路验收编排与证据门禁 |

在仓库根目录运行跨服务测试：

```powershell
npm --prefix e2e ci
npm --prefix e2e test
```

运行前端测试：

```powershell
pnpm --dir frontend test:unit
pnpm --dir frontend test:e2e
```

跨服务测试可能执行导入、存储维护等操作，应使用准备好的测试环境与夹具。前端测试的额外依赖以各场景及 [Playwright 配置](../frontend/playwright.config.ts) 为准。

报告、追踪和截图属于本地产物；跨服务 HTML 报告写入 `e2e/report/`。新增用例放入对应套件，避免另建无配置的测试目录。
