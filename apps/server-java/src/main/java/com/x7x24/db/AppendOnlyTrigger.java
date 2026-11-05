package com.x7x24.db;

import org.h2.api.Trigger;
import java.sql.Connection;
import java.sql.SQLException;

/** H2 版「只追加」触发器：task_event / evidence 禁止 UPDATE / DELETE（PostgreSQL 见 V2 迁移里的 plpgsql 触发器）。 */
public class AppendOnlyTrigger implements Trigger {
  private String table = "?";
  @Override public void init(Connection c, String schema, String trigger, String table, boolean before, int type) { this.table = table; }
  @Override public void fire(Connection c, Object[] oldRow, Object[] newRow) throws SQLException { throw new SQLException(table + " is append-only"); }
}
