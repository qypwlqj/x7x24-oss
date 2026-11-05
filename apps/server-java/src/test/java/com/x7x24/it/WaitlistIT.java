package com.x7x24.it;

import com.x7x24.auth.AuthFilter;
import com.x7x24.auth.AuthService;
import com.x7x24.web.WaitlistApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 首页等待名单：不需要登录、校验、去重不泄露、限流、蜜罐、只有平台管理员能看和导出 */
class WaitlistIT extends Harness {
  @Autowired TestRestTemplate http; @Autowired WaitlistApi api; @Autowired com.x7x24.pipeline.PipelineService pipes;

  ResponseEntity<String> post(String ip, Map<String, Object> body) {
    var h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.set("X-Real-IP", ip);
    return http.exchange("/api/public/waitlist", HttpMethod.POST, new HttpEntity<>(body, h), String.class);
  }

  @Test void joinValidatesDedupesAndRateLimits() {
    String ip = "10.0." + (System.nanoTime() % 200) + ".1", mail = "a" + System.nanoTime() + "@example.com";
    assertEquals(400, post(ip, Map.of("email", "not-an-email", "consent", true)).getStatusCode().value());
    var noConsent = post(ip, Map.of("email", mail)); assertEquals(400, noConsent.getStatusCode().value()); assertTrue(noConsent.getBody().contains("consent"));
    assertEquals(200, post(ip, Map.of("email", mail.toUpperCase(), "consent", true)).getStatusCode().value());
    assertEquals(200, post(ip, Map.of("email", mail, "consent", true)).getStatusCode().value(), "重复提交同样返回成功");
    assertEquals(1, db.count("SELECT COUNT(*) FROM waitlist WHERE email=?", mail), "大小写归一后去重");
    assertEquals(200, post(ip, Map.of("email", "x" + mail, "consent", true)).getStatusCode().value());
    assertEquals(429, post(ip, Map.of("email", "b" + mail, "consent", true)).getStatusCode().value(), "同一来源 10 分钟内最多 5 次");
    String bot = "bot" + System.nanoTime() + "@example.com";
    assertEquals(200, post("10.9.9.9", Map.of("email", bot, "consent", true, "website", "x")).getStatusCode().value());
    assertEquals(0, db.count("SELECT COUNT(*) FROM waitlist WHERE email=?", bot), "蜜罐命中不入库");
  }

  @Test void onlyPlatformAdminCanListAndExport() {
    String mail = "=c" + System.nanoTime() + "@example.com";
    db.update("INSERT INTO waitlist(id,email,source,consent_at,created_at) VALUES(?,?,?,?,?)", "wl_t" + System.nanoTime(), mail, "home", clk.iso(), clk.iso());
    var r = new MockHttpServletRequest(); r.setAttribute(AuthFilter.ATTR, new AuthService.User("u1", 1L, "nobody" + System.nanoTime(), null, null));
    assertThrows(com.x7x24.tenant.WorkspaceService.Forbidden.class, () -> api.list(r));
    @SuppressWarnings("unchecked") var admins = (Set<String>) org.springframework.test.util.ReflectionTestUtils.getField(pipes, "admins");
    String login = "wladmin" + System.nanoTime(); admins.add(login);
    try {
      var ar = new MockHttpServletRequest(); ar.setAttribute(AuthFilter.ATTR, new AuthService.User("u2", 2L, login, null, null));
      assertTrue(((Number) api.list(ar).get("total")).longValue() >= 1);
      String csv = api.csv(ar).getBody(); assertTrue(csv.contains("\"'" + mail + "\""), "导出时防公式注入");
    } finally { admins.remove(login); }
  }
}
