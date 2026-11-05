package com.x7x24.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** M4：迁移链顺序（#9 的 V40–V43 → #12 的 V44 → #13 的 V45/V46 → M4 的 V47+）；全新库和停在 V34 的生产库都能顺序升级 */
class MigrationChainTest {
  static Flyway fw(String url, String target) {
    var c = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/common", "classpath:db/migration/h2");
    if (target != null) c.target(target);
    return c.load();
  }
  static String url(String name) { return "jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY,VALUE;DB_CLOSE_DELAY=-1"; }
  static List<String> applied(String url) throws Exception {
    List<String> v = new ArrayList<>();
    try (var c = DriverManager.getConnection(url, "sa", ""); var rs = c.createStatement().executeQuery("SELECT \"version\" FROM \"flyway_schema_history\" WHERE \"success\" ORDER BY \"installed_rank\"")) { while (rs.next()) v.add(rs.getString(1)); }
    return v;
  }

  @Test void freshDatabaseAppliesWholeChainInOrder() throws Exception {
    String u = url("fresh" + System.nanoTime()); var r = fw(u, null).migrate();
    assertTrue(r.success); var v = applied(u);
    assertTrue(v.indexOf("40") < v.indexOf("42") && v.indexOf("42") < v.indexOf("44") && v.indexOf("44") < v.indexOf("45") && v.indexOf("45") < v.indexOf("46") && v.indexOf("46") < v.indexOf("47"), v.toString());
  }

  @Test void productionAtV34UpgradesWithoutOutOfOrder() throws Exception {
    String u = url("v34" + System.nanoTime());
    assertTrue(fw(u, "34").migrate().success);
    assertEquals("34", applied(u).getLast());
    var r = fw(u, null).migrate(); assertTrue(r.success);
    var v = applied(u); assertTrue(v.containsAll(List.of("40", "42", "44", "45", "46", "47")), v.toString());
    assertEquals(0, fw(u, null).info().pending().length);
    assertTrue(fw(u, null).validateWithResult().validationSuccessful);
  }
}
