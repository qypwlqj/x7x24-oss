package com.x7x24.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Supplier;

/** JdbcTemplate 的薄封装：结果行统一小写键（H2 默认大写、PostgreSQL 小写）。 */
@Component
public class Db {
  public final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  public Db(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc = jdbc; this.tx = tx; }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(sql, (rs, i) -> {
      var md = rs.getMetaData(); Map<String, Object> m = new LinkedHashMap<>();
      for (int c = 1; c <= md.getColumnCount(); c++) m.put(md.getColumnLabel(c).toLowerCase(Locale.ROOT), rs.getObject(c));
      return m;
    }, args);
  }
  public Map<String, Object> row(String sql, Object... args) { var r = rows(sql, args); return r.isEmpty() ? null : r.get(0); }
  public long count(String sql, Object... args) { Long v = jdbc.queryForObject(sql, Long.class, args); return v == null ? 0 : v; }
  public double dbl(String sql, Object... args) { Double v = jdbc.queryForObject(sql, Double.class, args); return v == null ? 0 : v; }
  public int update(String sql, Object... args) { return jdbc.update(sql, args); }
  public <T> T tx(Supplier<T> s) { return tx.execute(st -> s.get()); }
  public void tx(Runnable r) { tx.executeWithoutResult(st -> r.run()); }

  public static String str(Object o) { return o == null ? null : o.toString(); }
  public static int i(Object o) { return o == null ? 0 : ((Number) o).intValue(); }
  public static long l(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
  public static double d(Object o) { return o == null ? 0 : ((Number) o).doubleValue(); }
}
