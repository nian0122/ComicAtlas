# 开发流程

本文面向 ComicAtlas 的开发者，说明如何使用 Git 管理一次功能开发、修复和发布。

## 分支职责

| 分支 | 用途 |
|------|------|
| `main` | 面向用户的稳定版本；只接收已验证的发布内容。 |
| `develop` | 日常开发集成分支；下一版本的功能和修复先汇总到这里。 |
| `feature/<名称>` | 从 `develop` 创建的功能分支，例如 `feature/恢复任务中心`。 |
| `fix/<名称>` | 从 `develop` 创建的普通缺陷修复分支。 |
| `hotfix/<名称>` | 从 `main` 创建的线上紧急修复分支；完成后必须合并回 `main` 和 `develop`。 |

不要直接在 `main` 上开发，也不要把未验证的功能推送到 `main`。

## 开始一个功能

先同步开发分支，再创建独立功能分支：

```bash
git switch develop
git pull --rebase origin develop
git switch -c feature/功能名称
```

功能名称使用简短中文或英文短语，例如：

```text
feature/恢复任务中心
fix/视频转码状态
```

## 日常查看改动

开发过程中经常运行以下命令：

```bash
# 查看当前分支、已修改和未跟踪文件
git status

# 查看尚未暂存的内容
git diff

# 查看已经暂存、即将提交的内容
git diff --cached
```

如果看到不属于当前任务的文件，不要顺手提交。先保留它，或和负责人确认后再处理。

## 创建提交

一个提交只做一件完整的事：功能实现和它直接相关的测试可以放在同一个提交；无关的格式化、文档或其他功能应分开。

```bash
# 只暂存本次功能涉及的文件，不推荐直接使用 git add .
git add api-service/src/main/java/.../RecoveryTaskServiceImpl.java
git add api-service/src/test/java/.../RecoveryTaskServiceTest.java

# 再检查提交范围
git diff --cached

# 使用中文、动作开头的提交信息
git commit -m "新增数据库恢复任务中心"
```

推荐的提交信息：

```text
新增数据库恢复任务中心
修复视频转码状态机
优化阅读器视频视口播放
完善用户操作指南
```

提交前至少完成与改动匹配的验证。例如：

```bash
# 前端
npm --prefix frontend run build

# 后端
mvn -pl api-service test
```

不要提交 `.env`、密码、远程服务凭据、日志、构建产物、个人漫画文件或宿主机绝对路径。

## 合并到 develop 并推送

功能完成并验证后，合并到 `develop`：

```bash
git switch develop
git pull --rebase origin develop
git merge --no-ff feature/功能名称 -m "合入 功能名称"
git push origin develop
```

合并前请确认工作区干净：

```bash
git status
```

如果工作区有其他未提交改动，不要强行切换分支或覆盖它们。

## 发布稳定版本

当 `develop` 已通过前端构建、后端测试和真实导入—阅读链路验证后，才发布到 `main`：

```bash
git switch main
git pull --rebase origin main
git merge --no-ff develop -m "发布 X.Y.Z"
git tag -a vX.Y.Z -m "ComicAtlas X.Y.Z 稳定版本"
git push origin main --follow-tags
git push origin develop
```

发布说明写入 `docs/release/vX.Y.Z.md`，用户操作变更同步更新 `README.md` 和 `docs/user-guide.md`。

## 常见情况

### 提交后发现漏了一个文件

如果提交还没有推送，可以创建补充提交；不要为了小遗漏随意改写共享历史：

```bash
git add 漏掉的文件
git commit -m "补充恢复任务测试"
```

### 做错了分支或合并

先停止后续操作，运行：

```bash
git status
git log --oneline -10
```

不要直接执行 `git reset --hard` 或 `git push --force`。先确认哪些提交已推送、哪些文件有未提交改动，再选择恢复方式。

### 不确定某个文件能否提交

在提交前查看：

```bash
git diff --cached -- 文件路径
```

只要文件不属于当前功能、包含敏感信息，或你无法解释它为什么需要进入提交，就先不要暂存。
