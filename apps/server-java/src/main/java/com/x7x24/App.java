package com.x7x24;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@SpringBootApplication
@EnableScheduling
public class App {
  public static void main(String[] args) { normalizeDatabaseUrl(); SpringApplication.run(App.class, args); }

  /** Render / Heroku 风格的 DATABASE_URL（postgres://user:pass@host:port/db）→ JDBC URL + 用户名 / 密码（系统属性，不打印） */
  static void normalizeDatabaseUrl() {
    String raw = System.getenv("DATABASE_URL");
    String[] r = toJdbc(raw); if (r == null) return;
    System.setProperty("spring.datasource.url", r[0]);
    if (r[1] != null && System.getenv("DATABASE_USER") == null) System.setProperty("spring.datasource.username", r[1]);
    if (r[2] != null && System.getenv("DATABASE_PASSWORD") == null) System.setProperty("spring.datasource.password", r[2]);
  }
  /** 返回 [jdbcUrl, user, password]；不是 postgres:// 形式时返回 null */
  static String[] toJdbc(String raw) {
    if (raw == null || !(raw.startsWith("postgres://") || raw.startsWith("postgresql://"))) return null;
    URI u = URI.create(raw); String user = null, pass = null;
    if (u.getRawUserInfo() != null) { String[] up = u.getRawUserInfo().split(":", 2); user = URLDecoder.decode(up[0], StandardCharsets.UTF_8); if (up.length > 1) pass = URLDecoder.decode(up[1], StandardCharsets.UTF_8); }
    String q = u.getRawQuery() == null ? "" : "?" + u.getRawQuery();
    return new String[]{"jdbc:postgresql://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + u.getRawPath() + q, user, pass};
  }
}
