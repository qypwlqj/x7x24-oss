package com.x7x24.tenant;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Postgres 行级安全的会话变量：每次从连接池取连接时写入 x7.ws（见 V12__row_level_security.sql）。
 * 连接池复用连接，所以每次取连接都覆盖，不会残留上一个请求的工作区。H2 下不包装。
 */
@Component
public class TenantDataSource implements BeanPostProcessor {
  private final boolean postgres;
  public TenantDataSource(Environment env) { postgres = String.valueOf(env.getProperty("spring.datasource.url", "")).startsWith("jdbc:postgresql"); }

  @Override public Object postProcessAfterInitialization(Object bean, String name) {
    if (!postgres || !(bean instanceof DataSource ds) || bean instanceof Scoped) return bean;
    return new Scoped(ds);
  }

  static final class Scoped extends DelegatingDataSource {
    Scoped(DataSource d) { super(d); }
    @Override public Connection getConnection() throws SQLException { return apply(super.getConnection()); }
    @Override public Connection getConnection(String u, String p) throws SQLException { return apply(super.getConnection(u, p)); }
    private static Connection apply(Connection c) throws SQLException {
      try (var st = c.prepareStatement("SELECT set_config('x7.ws', ?, false)")) { st.setString(1, Tenant.dbScope()); st.execute(); }
      catch (SQLException e) { c.close(); throw e; }
      return c;
    }
  }
}
