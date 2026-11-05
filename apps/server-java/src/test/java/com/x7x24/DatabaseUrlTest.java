package com.x7x24;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseUrlTest {
  @Test void convertsRenderStyleUrl() {
    var r = App.toJdbc("postgres://u:p%40ss@dpg-abc.oregon-postgres.render.com:5432/x7x24?sslmode=require");
    assertEquals("jdbc:postgresql://dpg-abc.oregon-postgres.render.com:5432/x7x24?sslmode=require", r[0]); assertEquals("u", r[1]); assertEquals("p@ss", r[2]);
    assertEquals("jdbc:postgresql://h/db", App.toJdbc("postgresql://h/db")[0]);
  }
  @Test void leavesJdbcUntouched() { assertNull(App.toJdbc("jdbc:h2:mem:x")); assertNull(App.toJdbc(null)); }
}
