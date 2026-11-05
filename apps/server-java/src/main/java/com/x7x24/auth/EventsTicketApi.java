package com.x7x24.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** POST /api/events/ticket：给 SSE 签发一次性票据（60 秒内有效、只能用一次），代替把长期会话令牌放进 URL。 */
@RestController
public class EventsTicketApi {
  private final AuthService auth;
  public EventsTicketApi(AuthService auth) { this.auth = auth; }
  @PostMapping("/api/events/ticket") public Map<String, Object> ticket(HttpServletRequest req) {
    var u = AuthFilter.peek(auth, req); // 强制登录时 AuthFilter 已经拦掉未登录请求；未强制登录且未登录时返回空票据
    return Map.of("ticket", u == null ? "" : auth.issueSseTicket(u), "expiresIn", AuthService.TICKET_MS / 1000);
  }
}
