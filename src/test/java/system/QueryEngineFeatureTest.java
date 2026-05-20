package system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.optimizer.PhysicalPlanner;
import edu.sustech.cs307.physicalOperator.PhysicalOperator;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.storage.replacer.ClockReplacer;
import edu.sustech.cs307.storage.replacer.PageReplacer;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.system.RecordManager;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryEngineFeatureTest {
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("支持投影、范围过滤、OR 和 ORDER BY")
    void testProjectionWhereOrAndOrderBy() throws DBException {
        DBManager dbManager = buildDbManager();
        execute(dbManager, "create table users(id int, name char, age int, gpa float)");
        execute(dbManager, "insert into users (id, name, age, gpa) values (1, 'a', 18, 3.1)");
        execute(dbManager, "insert into users (id, name, age, gpa) values (2, 'b', 19, 3.2)");
        execute(dbManager, "insert into users (id, name, age, gpa) values (3, 'c', 17, 4.0)");
        execute(dbManager, "insert into users (id, name, age, gpa) values (4, 'd', 22, 2.0)");

        List<List<Object>> rows = query(dbManager,
                "select id, name from users where age > 18 or gpa >= 3.9 order by id");
        assertThat(rows).containsExactly(
                List.of(2L, "b"),
                List.of(3L, "c"),
                List.of(4L, "d")
        );
    }

    @Test
    @DisplayName("支持 DELETE 条件删除和 COUNT 统计")
    void testDeleteAndCount() throws DBException {
        DBManager dbManager = buildDbManager();
        execute(dbManager, "create table users(id int, name char, age int, gpa float)");
        execute(dbManager, "insert into users (id, name, age, gpa) values (1, 'a', 18, 3.1)");
        execute(dbManager, "insert into users (id, name, age, gpa) values (2, 'b', 19, 3.2)");
        execute(dbManager, "insert into users (id, name, age, gpa) values (3, 'c', 17, 4.0)");
        execute(dbManager, "insert into users (id, name, age, gpa) values (4, 'd', 22, 2.0)");

        execute(dbManager, "delete from users where age >= 21 or id = 3");

        List<List<Object>> countRows = query(dbManager, "select count(*) from users where age >= 18");
        assertThat(countRows).containsExactly(List.of(2L));
    }

    @Test
    @DisplayName("支持 MAX/MIN/GROUP BY/ORDER BY")
    void testAggregateGroupByOrderBy() throws DBException {
        DBManager dbManager = buildDbManager();
        execute(dbManager, "create table scores(id int, grp int, score float, name char)");
        execute(dbManager, "insert into scores (id, grp, score, name) values (1, 1, 2.1, 'a')");
        execute(dbManager, "insert into scores (id, grp, score, name) values (2, 1, 3.5, 'b')");
        execute(dbManager, "insert into scores (id, grp, score, name) values (3, 2, 1.0, 'c')");
        execute(dbManager, "insert into scores (id, grp, score, name) values (4, 2, 4.2, 'd')");

        List<List<Object>> rows = query(dbManager,
                "select grp, max(score), min(score) from scores group by grp order by grp");

        assertThat(rows).containsExactly(
                List.of(1L, 3.5d, 2.1d),
                List.of(2L, 4.2d, 1.0d)
        );
    }

    @Test
    @DisplayName("支持 CREATE INDEX / DROP INDEX 与 IN/EXISTS 子查询")
    void testIndexAndSubquery() throws DBException {
        DBManager dbManager = buildDbManager();
        execute(dbManager, "create table a(id int, name char)");
        execute(dbManager, "create table b(id int)");
        execute(dbManager, "insert into a (id, name) values (1, 'a')");
        execute(dbManager, "insert into a (id, name) values (2, 'b')");
        execute(dbManager, "insert into a (id, name) values (3, 'c')");
        execute(dbManager, "insert into b (id) values (2)");

        execute(dbManager, "create index idx_a_id on a(id)");
        assertThat(dbManager.getMetaManager().getTable("a").getIndexes().containsKey("idx_a_id")).isTrue();

        List<List<Object>> inRows = query(dbManager,
                "select id from a where id in (select id from b) order by id");
        assertThat(inRows).containsExactly(List.of(2L));

        List<List<Object>> existsRows = query(dbManager,
                "select id from a where exists (select id from b where id = 2) order by id");
        assertThat(existsRows).containsExactly(
                List.of(1L),
                List.of(2L),
                List.of(3L)
        );

        execute(dbManager, "drop index idx_a_id");
        assertThat(dbManager.getMetaManager().getTable("a").getIndexes().containsKey("idx_a_id")).isFalse();
    }

    @Test
    @DisplayName("支持 NOT IN 与 Nested Loop Join")
    void testNotInAndJoin() throws DBException {
        DBManager dbManager = buildDbManager();
        execute(dbManager, "create table a(id int, name char)");
        execute(dbManager, "create table b(id int, score float)");

        execute(dbManager, "insert into a (id, name) values (1, 'a')");
        execute(dbManager, "insert into a (id, name) values (2, 'b')");
        execute(dbManager, "insert into a (id, name) values (3, 'c')");
        execute(dbManager, "insert into b (id, score) values (2, 90.0)");
        execute(dbManager, "insert into b (id, score) values (3, 80.0)");

        List<List<Object>> notInListRows = query(dbManager,
                "select id from a where id not in (2) order by id");
        assertThat(notInListRows).containsExactly(
                List.of(1L),
                List.of(3L)
        );

        List<List<Object>> notInSubqueryRows = query(dbManager,
                "select id from a where id not in (select id from b) order by id");
        assertThat(notInSubqueryRows).containsExactly(List.of(1L));

        List<List<Object>> joinRows = query(dbManager,
                "select a.id, b.score from a join b on a.id = b.id order by a.id");
        assertThat(joinRows).containsExactly(
                List.of(2L, 90.0d),
                List.of(3L, 80.0d)
        );
    }

    @Test
    @DisplayName("支持 SHOW/DESC/EXPLAIN/DROP 与多行 INSERT")
    void testDdlAndMultiRowInsert() throws DBException {
        DBManager dbManager = buildDbManager();
        execute(dbManager, "create table t(id int, name char, age int, gpa float)");

        execute(dbManager,
                "insert into t (id, name, age, gpa) values (1, 'a', 18, 3.1), (2, 'b', 19, 3.2), (3, 'c', 20, 3.3)");
        List<List<Object>> countRows = query(dbManager, "select count(*) from t");
        assertThat(countRows).containsExactly(List.of(3L));

        execute(dbManager, "show tables");
        execute(dbManager, "describe t");
        execute(dbManager, "explain select t.id, t.name from t where t.age > 18");
        execute(dbManager, "drop table t");

        assertThat(dbManager.isTableExists("t")).isFalse();
        assertThatThrownBy(() -> execute(dbManager, "describe t"))
                .isInstanceOf(DBException.class)
                .hasMessageContaining("Table does not exist");
    }

    @Test
    @DisplayName("支持部分 ALTER TABLE（空表）且拒绝非空表")
    void testAlterTablePartialSupport() throws DBException {
        DBManager dbManager = buildDbManager();
        execute(dbManager, "create table t(id int)");

        execute(dbManager, "alter table t add column name char");
        assertThat(dbManager.getMetaManager().getTable("t").getColumns().containsKey("name")).isTrue();

        execute(dbManager, "alter table t drop column id");
        assertThat(dbManager.getMetaManager().getTable("t").getColumns().containsKey("id")).isFalse();

        execute(dbManager, "insert into t (name) values ('x')");
        assertThatThrownBy(() -> execute(dbManager, "alter table t add column age int"))
                .isInstanceOf(DBException.class)
                .hasMessageContaining("ALTER TABLE");
    }

    private DBManager buildDbManager() throws DBException {
        HashMap<String, Integer> fileOffsets = new HashMap<>();
        DiskManager diskManager = new DiskManager(tempDir.toString(), fileOffsets);
        IntFunction<PageReplacer> replacerFactory = ClockReplacer::new;
        BufferPool bufferPool = new BufferPool(32, diskManager, replacerFactory.apply(32));
        RecordManager recordManager = new RecordManager(diskManager, bufferPool);
        MetaManager metaManager = new MetaManager(tempDir.resolve("meta").toString());
        return new DBManager(diskManager, bufferPool, recordManager, metaManager, null, replacerFactory);
    }

    private void execute(DBManager dbManager, String sql) throws DBException {
        LogicalOperator logicalOperator = LogicalPlanner.resolveAndPlan(dbManager, sql);
        if (logicalOperator == null) {
            return;
        }
        PhysicalOperator physicalOperator = PhysicalPlanner.generateOperator(dbManager, logicalOperator);
        physicalOperator.Begin();
        while (physicalOperator.hasNext()) {
            physicalOperator.Next();
            physicalOperator.Current();
        }
        physicalOperator.Close();
        dbManager.getBufferPool().FlushAllPages("");
    }

    private List<List<Object>> query(DBManager dbManager, String sql) throws DBException {
        LogicalOperator logicalOperator = LogicalPlanner.resolveAndPlan(dbManager, sql);
        PhysicalOperator physicalOperator = PhysicalPlanner.generateOperator(dbManager, logicalOperator);
        List<List<Object>> rows = new ArrayList<>();
        physicalOperator.Begin();
        while (physicalOperator.hasNext()) {
            physicalOperator.Next();
            Tuple tuple = physicalOperator.Current();
            if (tuple == null) {
                continue;
            }
            Value[] values = tuple.getValues();
            ArrayList<Object> row = new ArrayList<>();
            for (Value value : values) {
                row.add(value == null ? null : value.value);
            }
            rows.add(row);
        }
        physicalOperator.Close();
        return rows;
    }
}
