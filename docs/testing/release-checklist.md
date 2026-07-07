# ComicAtlas v0.1.0 Release Checklist

> 本清单在 `v0.1.0-beta` 冻结后执行，全部通过方可发布 `v0.1.0` 稳定版。

---

## 一、构建与类型

- [ ] 前端 `npm run build` 成功，无 TypeScript error
- [ ] 后端 `mvn clean package` 成功，无编译错误
- [ ] 后端单元测试通过（`mvn test`）
- [ ] Docker Compose 能完整启动所有服务

---

## 二、代码质量

- [ ] 控制台无未处理异常（运行时）
- [ ] 无未使用的变量 / import（TypeScript `noUnusedLocals` 级别）
- [ ] 无 `console.log` 调试残留
- [ ] 无 TODO/FIXME 阻塞发布

---

## 三、状态覆盖

每个页面/组件至少覆盖以下三种状态：

- [ ] Loading 状态
- [ ] Error 状态（含重试）
- [ ] Empty 状态

涉及页面：

- [ ] Import
- [ ] Task Center
- [ ] Library
- [ ] Comic Detail
- [ ] Reader
- [ ] History

---

## 四、响应式基础

- [ ] Library 在 768px 以下布局正常
- [ ] Comic Detail Hero 在移动端不溢出
- [ ] Reader 工具栏在移动端可点击
- [ ] History Card 在移动端不重叠

---

## 五、Happy Path 全链路

使用真实漫画数据（至少 1 本 ZIP 或 DIRECTORY）完整走通：

- [ ] 创建导入任务
- [ ] Task Center 状态变为 SUCCESS
- [ ] Library 出现新漫画
- [ ] Comic Detail 信息正确
- [ ] Reader 开始阅读并翻页
- [ ] 退出 Reader 后进度保存
- [ ] History 显示正确页码
- [ ] 点击"继续阅读"恢复到正确页码

---

## 六、异常场景

- [ ] ZIP 路径不存在：错误提示清晰
- [ ] 空目录：导入失败原因明确
- [ ] 重复导入：行为符合设计
- [ ] 导入中刷新页面：Task Center 能恢复轮询
- [ ] Reader 中刷新页面：恢复章节和页码
- [ ] 删除漫画后返回 Library
- [ ] 封面不存在：显示占位图

---

## 七、性能基准

> 本地环境（无网络延迟）建议目标：

| 指标 | 目标 | 实测 | 是否通过 |
|------|------|------|----------|
| Library 首屏加载 | < 1s | | |
| Comic Detail 打开 | < 500ms | | |
| Reader 首张图片显示 | < 1s（HQ 已存在） | | |
| 搜索响应 | < 300ms | | |
| 导入任务状态刷新 | 1～2s 内可见 | | |

---

## 八、E2E 自动化

- [ ] Playwright 安装成功
- [ ] `smoke.spec.ts` 通过：首页 → Library → Detail → Reader → 返回
- [ ] `import.spec.ts` 通过：创建导入任务
- [ ] `reader.spec.ts` 通过：翻页并保存进度
- [ ] `history.spec.ts` 通过：History 列表与继续阅读

---

## 九、文档与标签

- [ ] `docs/testing/beta-v0.1-checklist.md` 已更新执行记录
- [ ] `docs/issues/TODO.md` 已更新走查记录
- [ ] 所有阻塞 Bug 已关闭
- [ ] Git tag `v0.1.0` 已创建
- [ ] Release Notes 已编写（可选）

---

## 十、签名

| 角色 | 姓名 | 日期 | 结论 |
|------|------|------|------|
| 测试 | | | |
| 开发 | | | |
| 发布 | | | |

---

> 结论：全部通过 → 打 `v0.1.0` 标签并进入 Phase I：Reader Enhancement。
