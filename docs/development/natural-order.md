# 名称自然排序

## 规则与边界

展示标题、标签统一使用 ICU4J 78.3 的 `zh` 数字排序（PRIMARY 强度）。`第2话` 排在 `第10话` 前，多段连续数字分别比较；大小写、重音、全角数字和前导零按 ICU 规则等价。相等项按数据库 ID 升序稳定排序，标题降序时 ID 仍保持升序。

数字是无符号连续十进制数字，不解释小数、负号或中文数字；ICU 对单段数字的处理上限为 254 个有效数字，超过后按后续数字段处理。这里遵守标准库语义，不另外编写数字解析算法。

文件导入的 `NaturalPathComparator` 保持既有规则（数字相等时短数字串优先、文字大小写敏感），因为它决定新导入的章节和媒体顺序。目录树继续尊重 `sort_order/global_order`，不按展示标题重新排序。

## 实现

- `NaturalNameOrder` 仅配置并冻结 ICU Collator，提供比较器和二进制排序键。
- `Comic.setTitle` 同时维护 `title_sort_key`，通过同一次 INSERT/UPDATE 保持原子性。导入、恢复、批量编辑均复用实体入口；新增绕过实体的 SQL 写入必须同时提供正确排序键。
- 标题列表按完整 `VARBINARY` 排序键和 ID 在 MySQL 排序，再由分页插件截取；联想同样在 SQL 去重、排序和 LIMIT，不把全部匹配项加载到应用内存。
- 使用 VARBINARY 而非 BLOB/TEXT，避免 `max_sort_length` 导致长键比较被截断。当前未建立截断前缀索引来假装覆盖完整排序；大量匹配仍有数据库排序成本，应按真实数据量分析执行计划。
- 标签是全量参考数据，直接使用相同 ICU 比较器；漫画和标签缓存已更新版本，避免读取旧顺序。

## 迁移与部署

1. 停止旧管理服务写入，执行包含 Java classpath 的新版管理服务迁移。
2. V25 加列；V26 `db.flyway.TitleSortKeyMigration` 按主键每批 500 行回填，不修改业务更新时间；V27 设置 NOT NULL。
3. 迁移成功后启动新版管理服务、阅读服务。未升级的写入端会因缺少排序键被拒绝，不能混用。

V26 是 JavaMigration，直接实现接口提供版本、描述和校验值，既可由 Spring Boot 使用，也可由独立 Flyway 的 `classpath:db/flyway` 扫描；只运行 SQL 文件的迁移工具不适用于此版本。

ICU 版本和排序配置属于持久化格式。以后升级必须新增迁移重建所有排序键，并更换查询缓存版本，不能只更新 Maven 依赖。现有迁移不得改写。

## 验证

`NaturalNameOrderTest` 验证数字、Unicode、并发和排序键比较一致性；`ComicNaturalOrderMySqlTest` 使用独立 MySQL 容器验证跨批回填、非空约束、跨页升降序、联想 LIMIT 和标题修改。迁移测试同时验证空库和旧库升级。

参考：[ICU 数字排序 API](https://unicode-org.github.io/icu-docs/apidoc/released/icu4j/com/ibm/icu/text/RuleBasedCollator.html)、[排序键与版本兼容性](https://unicode-org.github.io/icu/userguide/collation/api.html)。
