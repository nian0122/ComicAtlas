package db.flyway;

import com.comicatlas.contract.common.util.NaturalNameOrder;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** V26：按主键游标分批回填旧标题，与 V25 加列、V27 非空约束一起执行。 */
public class TitleSortKeyMigration implements JavaMigration {
    private static final int BATCH_SIZE = 500;
    private static final int CHECKSUM = 783001;

    @Override
    public MigrationVersion getVersion() {
        return MigrationVersion.fromVersion("26");
    }

    @Override
    public String getDescription() {
        return "回填 ICU 78.3 标题排序键";
    }

    @Override
    public Integer getChecksum() {
        return CHECKSUM;
    }

    @Override
    public boolean canExecuteInTransaction() {
        return true;
    }

    @Override
    public void migrate(Context context) throws SQLException {
        // 连接由 Flyway 管理，本迁移只关闭自己创建的语句和结果集。
        Connection connection = context.getConnection();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, title FROM comic WHERE id > ? ORDER BY id LIMIT ?");
                PreparedStatement update = connection.prepareStatement(
                        "UPDATE comic SET title_sort_key = ?, updated_at = updated_at WHERE id = ?")) {
            long lastId = 0;
            while (true) {
                select.setLong(1, lastId);
                select.setInt(2, BATCH_SIZE);
                int rowCount = 0;
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        lastId = rows.getLong("id");
                        update.setBytes(1, NaturalNameOrder.sortKey(rows.getString("title")));
                        update.setLong(2, lastId);
                        update.addBatch();
                        rowCount++;
                    }
                }
                if (rowCount == 0) {
                    return;
                }
                update.executeBatch();
                update.clearBatch();
            }
        }
    }
}
