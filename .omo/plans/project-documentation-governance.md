# project-documentation-governance - Work Plan

## TL;DR (For humans)
<!-- Fill this LAST, after the detailed plan below is written, so it summarizes the REAL plan. -->
<!-- Plain English for a non-engineer: NO file paths, NO todo numbers, NO wave/agent/tool names. -->

**What you'll get:** 一套清楚区分“当前使用资料”和“历史决策记录”的文档入口，以及与当前 1.0 实现核对过的维护文档。

**Why this approach:** 不删除历史资料，避免丢失决策证据；以源码、配置和数据库定义校验当前资料，避免旧设计误导维护。

**What it will NOT do:** 不改产品代码、配置或数据库；不删除历史文档；不触碰当前未提交的 README 和开发流程文档。

**Effort:** Medium
**Risk:** Low - 仅编辑文档，受保护文件通过哈希和 Git 差异验证保持不变。
**Decisions I made for you:** 历史资料原路径保留并统一说明状态；以新增维护者索引代替批量搬迁；自动检查仅使用无新增依赖的 Python 标准库脚本。

Your next move: 使用 `$start-work` 执行本计划。Full execution detail follows below.

---

> TL;DR (machine): Medium effort, low risk; add a maintainers' index, reconcile current docs from source, classify history, and add reproducible checks.

## Scope
### Must have
- 新增 `docs/README.md`，按用户、维护、架构、测试发布、历史资料建立稳定入口。
- 更新 `docs/architecture/00-index.md`，分为当前架构和历史设计，收录系统全景、导入流水线和存储模型。
- 逐一核验 `docs/api.md`、`docs/database/schema.md`、`docs/user-guide.md`、`docs/testing/release-checklist.md` 与源码/配置；只修正已证实的陈旧内容。
- 为 `docs/issues/`、`docs/release/v0.1.0.md`、`docs/superpowers/`、旧 0.2 架构与 `docs/frontend/` 在维护者索引中给出历史分类和阅读规则，不批量改写原件。
- 新增零依赖的文档检查脚本，覆盖内部链接、索引覆盖和受保护文件不变。
### Must NOT have (guardrails, anti-slop, scope boundaries)
- 不编辑、暂存或提交 `README.md`、`docs/development-guide.md`、`.omo/boulder.json`。
- 不移动、删除或重命名 `docs/superpowers/`、`docs/issues/`、任何发布说明、`DESIGN.md` 或 `frontend/DESIGN.md`。
- 不新增第三方依赖、CI 工作流或产品行为。

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: tests-after；Python 3 标准库检查脚本。
- Evidence: `.omo/evidence/documentation-governance/task-<N>.txt`。

## Execution strategy
### Parallel execution waves
> Target 5-8 todos per wave. Fewer than 3 (except the final) means you under-split.

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1 | 无 | 2,3,4 | 无 |
| 2 | 1 | 5 | 3,4 |
| 3 | 1 | 5 | 2,4 |
| 4 | 1 | 5 | 2,3 |
| 5 | 2,3,4 | F1-F4 | 无 |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->
- [ ] 1. 建立受保护文件基线与文档事实映射
  What to do / Must NOT do: 记录 `README.md`、`docs/development-guide.md` 的 SHA-256 与 Git 状态；建立 `docs/README.md` 目标链接和每份当前文档的源码事实源映射。不得编辑或暂存受保护路径。
  Parallelization: Wave 1 | Blocked by: 无 | Blocks: 2,3,4
  References (executor has NO interview context - be exhaustive): `README.md:1-104`; `docs/development-guide.md:1-105`; `AGENTS.md:1-220`; `api-service/src/main/resources/db/schema.sql`; `api-service/**/controller/*.java`; `docker-compose.yml`; `nginx.conf`.
  Acceptance criteria (agent-executable): 运行 `Get-FileHash README.md,docs/development-guide.md -Algorithm SHA256 | ConvertTo-Json > .omo/evidence/documentation-governance/protected-baseline.json` 后，清单含两路径哈希；`git diff -- README.md docs/development-guide.md` 与 `git diff --cached -- README.md docs/development-guide.md` 均为空。
  QA scenarios (name the exact tool + invocation): happy: 上述 PowerShell 命令及两条 git diff 均退出 0；failure: 复制基线 JSON 后篡改副本中的哈希，`Compare-Object (Get-Content ...)` 输出差异；Evidence `.omo/evidence/documentation-governance/task-1.txt`.
  Commit: N | 基线只作为本次验证证据，不提交。
- [ ] 2. 新增维护者文档索引并修正架构入口
  What to do / Must NOT do: 新增 `docs/README.md`，明确“当前资料”“测试与发布”“历史记录”；更新 `docs/architecture/00-index.md` 为当前架构和历史设计两区，收录 `01-system-overview.md`、`02-import-pipeline.md`、`03-storage.md`。不编辑根 README。
  Parallelization: Wave 2 | Blocked by: 1 | Blocks: 5
  References (executor has NO interview context - be exhaustive): `docs/architecture/00-index.md:1-35`; `docs/architecture/01-system-overview.md:1-35`; `docs/architecture/02-import-pipeline.md:1-32`; `docs/architecture/03-storage.md:1-35`; `docs/release/v1.0.0.md:1-37`; `docs/testing/release-checklist.md`.
  Acceptance criteria (agent-executable): 索引存在且链接覆盖用户指南、开发流程、API、Schema、4 份当前架构资料、测试发布入口和历史目录说明；所有链接存在。
  QA scenarios (name the exact tool + invocation): happy: `python -c "from pathlib import Path; assert Path('docs/README.md').exists()"` 与任务 5 后 `python scripts/check_docs.py --links --index`; failure: 临时副本加入不存在链接后任务 5 的检查非零；Evidence `.omo/evidence/documentation-governance/task-2.txt`.
  Commit: Y | `整理项目文档入口与架构索引`.
- [ ] 3. 依据事实源更新当前操作与维护文档
  What to do / Must NOT do: 核验并修订 `docs/api.md` 的版本和接口、`docs/database/schema.md` 的更新时间/字段、`docs/user-guide.md` 的部署与操作、`docs/testing/release-checklist.md` 的 1.0 验收步骤；只更新可由事实源证明的内容。
  Parallelization: Wave 2 | Blocked by: 1 | Blocks: 5
  References (executor has NO interview context - be exhaustive): `docs/api.md:1-318`; `docs/database/schema.md:1-400`; `docs/user-guide.md:1-260`; `docs/testing/release-checklist.md`; `AGENTS.md` 的 URL、MQ、存储与 Git 章节；相关 Controller、`schema.sql`、`docker-compose.yml`、`nginx.conf`。
  Acceptance criteria (agent-executable): API 标题与 v1.0 一致；Schema 字段逐项来自 schema.sql；用户指南命令与 compose/脚本存在；发布清单覆盖前端构建、后端测试和导入—阅读链路。
  QA scenarios (name the exact tool + invocation): happy: `rg -n '^# ComicAtlas API 文档 v1\.0|最后更新' docs/api.md docs/database/schema.md` 与任务 5 后 `python scripts/check_docs.py --facts`; failure: 夹具包含不存在端点或字段时任务 5 的检查非零；Evidence `.omo/evidence/documentation-governance/task-3.txt`.
  Commit: Y | `更新当前项目操作文档`.
- [ ] 4. 在索引中固定历史资料的阅读规则
  What to do / Must NOT do: 仅在 `docs/README.md` 列出并说明历史资料：`docs/release/v0.1.0.md`、`docs/issues/`、`docs/superpowers/specs/`、`docs/superpowers/plans/`、`docs/architecture/01-product.md` 至 `08-migration.md`、`docs/frontend/`、两个 DESIGN 文件；说明其不是当前实现的事实源。不得修改这些历史文件。
  Parallelization: Wave 2 | Blocked by: 1 | Blocks: 5
  References (executor has NO interview context - be exhaustive): `docs/issues/TODO.md:1-79`; `docs/release/v0.1.0.md:1-128`; `docs/architecture/08-migration.md:1-202`; `docs/frontend/09-development-plan.md`; `DESIGN.md`; `frontend/DESIGN.md`.
  Acceptance criteria (agent-executable): 索引包含所有指定历史目录/文件的分类和“仅供追溯，不替代源码与当前资料”的规则。
  QA scenarios (name the exact tool + invocation): happy: `rg -n 'superpowers|issues|v0\.1\.0|历史' docs/README.md` 与任务 5 后 `python scripts/check_docs.py --history-index`; failure: 删除任一规定历史入口后任务 5 的检查非零；Evidence `.omo/evidence/documentation-governance/task-4.txt`.
  Commit: Y | `明确历史文档阅读规则`.
- [ ] 5. 增加并运行可重复的文档检查
  What to do / Must NOT do: 新增 `scripts/check_docs.py`，只使用 Python 标准库，检查相对 Markdown 链接、索引覆盖、受保护路径哈希和最小事实断言；将命令写入 `docs/README.md`。不新增依赖或 CI。
  Parallelization: Wave 3 | Blocked by: 2,3,4 | Blocks: F1-F4
  References (executor has NO interview context - be exhaustive): 本计划任务 1-4；`.gitignore:42-49`; `docs/README.md`（任务 2 产物）。
  Acceptance criteria (agent-executable): `python scripts/check_docs.py` 退出 0；对临时断链、缺少规定索引条目、修改受保护副本分别退出非零并给出路径。
  QA scenarios (name the exact tool + invocation): happy: 完整检查；failure: 三类夹具逐项失败；Evidence `.omo/evidence/documentation-governance/task-5.txt`.
  Commit: Y | `新增文档一致性检查`.

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [ ] F1. Plan compliance audit
  Run `git diff --name-only`, `git diff --cached --name-only`, `git diff -- README.md docs/development-guide.md`, `git diff --cached -- README.md docs/development-guide.md`, and `Get-FileHash README.md,docs/development-guide.md -Algorithm SHA256`; require only task 2-5 paths changed and protected diff empty/hashes match baseline. Evidence `.omo/evidence/documentation-governance/f1.txt`.
- [ ] F2. Code quality review
  Review `scripts/check_docs.py` for path traversal, encoding and Windows path handling; run `python -m py_compile scripts/check_docs.py`. Evidence `.omo/evidence/documentation-governance/f2.txt`.
- [ ] F3. Real manual QA
  Open `docs/README.md`, visit every current/historical link, then run `python scripts/check_docs.py`; create a temporary copied Markdown file containing `[bad](missing.md)` and require `python scripts/check_docs.py --root <temp>` nonzero with the missing path. Evidence `.omo/evidence/documentation-governance/f3.txt`.
- [ ] F4. Scope fidelity
  Compare both `git diff --name-only` and `git diff --cached --name-only` with allowed task paths, then run the protected diff and hash commands from F1; require all protected assertions pass. Evidence `.omo/evidence/documentation-governance/f4.txt`.

## Commit strategy
- 分三组原子提交：入口/架构索引、当前文档事实更新、检查脚本与历史规则；绝不暂存受保护文件。

## Success criteria
- 当前维护者可从 `docs/README.md` 在两跳内找到用户、开发、API、数据库、架构、测试发布资料。
- 所有当前文档通过事实映射核验，历史资料保留原路径并被明确标识。
- `python scripts/check_docs.py` 成功，负向夹具失败，README 与开发流程基线不变。
