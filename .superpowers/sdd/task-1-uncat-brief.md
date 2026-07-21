# Task 1: 后端 SQL 哨兵值分支

> 来源:`docs/superpowers/plans/2026-07-19-uncategorized-untagged-filter.md` Task 1。本文件是你的完整需求,精确值逐字使用。

## Global Constraints

- 提交信息一律中文(conventional 前缀)
- 哨兵值固定为 `_NONE`(全大写,单个下划线前缀)
- 禁止改动:后端 service/controller 层、任何前端文件、类型定义
- MySQL 运行在 docker 容器 `comicatlas-mysql`(root/root, db comic_atlas)
- 编译命令:`.\mvnw.cmd -q -pl api-service compile`(repo root 执行)

## Files

- Modify: `api-service/src/main/java/com/comicatlas/api/comic/mapper/ComicMapper.java`

完全按照 spec 中的方式修改，将两个 `<if test='query.category...'` 和 `<if test='query.tags...'` 条件分别包装在 `<choose>` 中，并添加对哨兵值 `_NONE` 的检测分支。将现有的 ELSE 逻辑移入 `<otherwise>` 块中。修改完成后在 MySQL 上直接验证新的 SQL 分支，然后编译通过。

## Steps

- [ ] **Step 1: RED — 确认 `_NONE` 不作为普通分类名匹配任何数据**

```bash
docker exec comicatlas-mysql mysql -uroot -proot comic_atlas -e "SELECT COUNT(*) FROM comic WHERE status='READY' AND EXISTS (SELECT 1 FROM category WHERE id=category_id AND name='_NONE');"
```
Expected: 0(没有名为 `_NONE` 的分类,为后续 GREEN 验证提供基线)

- [ ] **Step 2: 修改 `@Select` 脚本中 category 条件**

将现有(约第 51-53 行):
```xml
<if test='query.category != null and query.category != ""'>
    AND EXISTS (SELECT 1 FROM category cat WHERE cat.id = c.category_id AND cat.name = #{query.category})
</if>
```
替换为:
```xml
<if test='query.category != null and query.category != ""'>
    <choose>
        <when test='query.category == "_NONE"'>
            AND c.category_id IS NULL
        </when>
        <otherwise>
            AND EXISTS (SELECT 1 FROM category cat WHERE cat.id = c.category_id AND cat.name = #{query.category})
        </otherwise>
    </choose>
</if>
```

- [ ] **Step 3: 修改 `@Select` 脚本中 tags 条件**

将现有(约第 32-47 行,整个 `<if test='query.tags...'>` 块):
```xml
<if test='query.tags != null and query.tags.size > 0'>
    <choose>
        <when test='query.tagMode == "AND"'>
            AND (SELECT COUNT(DISTINCT t.name) FROM comic_tag ct JOIN tag t ON t.id = ct.tag_id
                 WHERE ct.comic_id = c.id AND t.name IN
                 <foreach collection='query.tags' item='tagName' open='(' separator=',' close=')'>#{tagName}</foreach>
                ) = #{query.tags.size}
        </when>
        <otherwise>
            AND EXISTS (SELECT 1 FROM comic_tag ct JOIN tag t ON t.id = ct.tag_id
                        WHERE ct.comic_id = c.id AND t.name IN
                        <foreach collection='query.tags' item='tagName' open='(' separator=',' close=')'>#{tagName}</foreach>
                       )
        </otherwise>
    </choose>
</if>
```
替换为(外层包 `<choose>`,哨兵值分支在前):
```xml
<if test='query.tags != null and query.tags.size > 0'>
    <choose>
        <when test='query.tags.contains(&quot;_NONE&quot;)'>
            AND NOT EXISTS (SELECT 1 FROM comic_tag ct WHERE ct.comic_id = c.id)
        </when>
        <otherwise>
            <choose>
                <when test='query.tagMode == &quot;AND&quot;'>
                    AND (SELECT COUNT(DISTINCT t.name) FROM comic_tag ct JOIN tag t ON t.id = ct.tag_id
                         WHERE ct.comic_id = c.id AND t.name IN
                         <foreach collection='query.tags' item='tagName' open='(' separator=',' close=')'>#{tagName}</foreach>
                        ) = #{query.tags.size}
                </when>
                <otherwise>
                    AND EXISTS (SELECT 1 FROM comic_tag ct JOIN tag t ON t.id = ct.tag_id
                                WHERE ct.comic_id = c.id AND t.name IN
                                <foreach collection='query.tags' item='tagName' open='(' separator=',' close=')'>#{tagName}</foreach>
                               )
                </otherwise>
            </choose>
        </otherwise>
    </choose>
</if>
```

注意:MyBatis XML 中 `<` `>` 需要转义(`&quot;`)。

- [ ] **Step 4: GREEN — MySQL 验证两条新分支**

```bash
# 验证未分类(category_id IS NULL)
docker exec comicatlas-mysql mysql -uroot -proot comic_atlas -e "SELECT COUNT(*) FROM comic WHERE status NOT IN ('PLACEHOLDER','DELETED') AND category_id IS NULL;"

# 验证无标签(NOT EXISTS comic_tag)
docker exec comicatlas-mysql mysql -uroot -proot comic_atlas -e "SELECT COUNT(*) FROM comic c WHERE status NOT IN ('PLACEHOLDER','DELETED') AND NOT EXISTS (SELECT 1 FROM comic_tag ct WHERE ct.comic_id=c.id);"
```

两条均应返回值 ≥ 1(具体数字取决于当前 DB 状态,spec 记录基准值为 48 和 49)。

- [ ] **Step 5: 编译**

Run(workdir repo root): `.\mvnw.cmd -q -pl api-service compile`
Expected: EXIT=0

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/comic/mapper/ComicMapper.java
git commit -m "feat(后端): ComicMapper 支持未分类(_NONE→IS NULL)和无标签(_NONE→NOT EXISTS)筛选"
```

只提交这一个文件。
