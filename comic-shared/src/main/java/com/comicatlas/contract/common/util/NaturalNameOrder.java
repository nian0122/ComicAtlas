package com.comicatlas.contract.common.util;

import com.ibm.icu.text.Collator;
import com.ibm.icu.text.RuleBasedCollator;
import com.ibm.icu.util.ULocale;

import java.util.Comparator;
import java.util.Objects;

/**
 * 展示名称使用 ICU 中文数字排序；排序键按无符号字节比较，与数据库 VARBINARY 顺序一致。
 * 数值相等的前导零、大小写和重音由调用者使用 ID 稳定排序，不额外实现数字拆分规则。
 * ICU 版本、语言或强度变化必须同步迁移持久化排序键。
 */
public final class NaturalNameOrder {
    private static final RuleBasedCollator COLLATOR = createCollator();
    public static final Comparator<String> COMPARATOR = Comparator.nullsLast(COLLATOR::compare);

    private NaturalNameOrder() {
    }

    /** 生成可持久化的 ICU 排序键；标题为空属于调用方数据错误。 */
    public static byte[] sortKey(String name) {
        return COLLATOR.getCollationKey(Objects.requireNonNull(name, "排序名称不能为空")).toByteArray();
    }

    private static RuleBasedCollator createCollator() {
        RuleBasedCollator collator = (RuleBasedCollator) Collator.getInstance(ULocale.SIMPLIFIED_CHINESE);
        collator.setNumericCollation(true);
        collator.setStrength(Collator.PRIMARY);
        // 冻结后可安全地被多个 HTTP 请求和迁移任务共享。
        collator.freeze();
        return collator;
    }
}
