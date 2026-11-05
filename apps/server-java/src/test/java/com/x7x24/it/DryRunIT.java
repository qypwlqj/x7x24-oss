package com.x7x24.it;

import com.x7x24.adapters.DeliveryTools;
import com.x7x24.engine.ProjectCfg;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** dry-run 是安全开关：写操作只记录计划，绝不向 GitHub 发请求。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class DryRunIT {
  static final MockWebServer server = new MockWebServer();
  static { try { server.start(); } catch (IOException e) { throw new RuntimeException(e); } }
  @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
    r.add("x7x24.auth.required", () -> "false");
    r.add("x7x24.github.api-url", () -> "http://localhost:" + server.getPort()); r.add("x7x24.github.token", () -> "tok");
    r.add("x7x24.adapter.mode", () -> "real"); r.add("x7x24.adapter.dry-run", () -> "true"); r.add("spring.datasource.url", () -> "jdbc:h2:mem:dry;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY,VALUE;DB_CLOSE_DELAY=-1");
  }
  @AfterAll static void stop() throws IOException { server.shutdown(); }
  @Autowired DeliveryTools tools;

  @Test void writesArePlannedNotSent() {
    var l = new ProjectCfg(); l.id = "sandbox"; l.repo = "o/r";
    assertThrows(DeliveryTools.DryRunPlanned.class, () -> tools.createIssue(l, "k1", "t", "b", List.of("x")));
    assertThrows(DeliveryTools.DryRunPlanned.class, () -> tools.comment(l, "k2", 1, "x"));
    assertThrows(DeliveryTools.DryRunPlanned.class, () -> tools.ensureBranch(l, "k3", "agent/x"));
    assertThrows(DeliveryTools.DryRunPlanned.class, () -> tools.initializeRepository(l, "k4"));
    assertEquals(0, server.getRequestCount(), "dry-run 下没有任何 HTTP 请求");
  }
}
