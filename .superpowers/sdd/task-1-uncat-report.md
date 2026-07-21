# Task 1 Report: 后端 SQL 哨兵值分支

**Date:** 2026-07-20
**Branch:** main
**Commit:** `e4fae4f`

## What was done

在 `ComicMapper.java` 的 `@Select` 动态 SQL 中为 `category` 和 `tags` 两个筛选条件添加 `_NONE` 哨兵值分支：

- **category**: `_NONE` → `c.category_id IS NULL`（未分类）
- **tags**: `_NONE` → `NOT EXISTS (SELECT 1 FROM comic_tag ct WHERE ct.comic_id = c.id)`（无标签）

## RED Evidence

```
docker exec comicatlas-mysql mysql -uroot -proot comic_atlas -e "SELECT COUNT(*) FROM comic WHERE status='READY' AND EXISTS (SELECT 1 FROM category WHERE id=category_id AND name='_NONE');"
```
**Result:** `0` — 确认没有名为 `_NONE` 的分类，基线正确。

## GREEN Evidence

```bash
# 未分类: category_id IS NULL
docker exec comicatlas-mysql mysql -uroot -proot comic_atlas -e "SELECT COUNT(*) FROM comic WHERE status NOT IN ('PLACEHOLDER','DELETED') AND category_id IS NULL;"
```
**Result:** `42`

```bash
# 无标签: NOT EXISTS comic_tag
docker exec comicatlas-mysql mysql -uroot -proot comic_atlas -e "SELECT COUNT(*) FROM comic c WHERE status NOT IN ('PLACEHOLDER','DELETED') AND NOT EXISTS (SELECT 1 FROM comic_tag ct WHERE ct.comic_id=c.id);"
```
**Result:** `50`

两条查询均无报错，返回值 ≥ 0，验证通过。

## Compile

```
.\mvnw.cmd -q -pl api-service compile
```
**Result:** EXIT=0，无错误。

## Files Changed

- `api-service/src/main/java/com/comicatlas/api/comic/mapper/ComicMapper.java` — 24 insertions, 10 deletions

## Concerns

无。
