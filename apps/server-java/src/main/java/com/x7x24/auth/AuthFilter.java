package com.x7x24.auth;

import com.x7x24.util.Json;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/** 保护 /api/**（除 /api/auth/*、/api/health 和 CORS 预检）。凭据来源：Bearer 头 → Cookie → ticket 一次性票据（仅 /api/events，见 POST /api/events/ticket）。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter implements Filter {
  public static final String ATTR = "x7.user";
  private final AuthService auth;
  public AuthFilter(AuthService auth) { this.auth = auth; }

  /** 只有 SSE 地址接受一次性票据：全局 /api/events 和单任务 /api/tasks/{id}/stream（EventSource 不能带请求头） */
  public static boolean sseTicketPath(String p) { return "/api/events".equals(p) || (p != null && p.matches("^/api/tasks/[^/]+/stream$")); }

  /** 只解析不拦截（工作区过滤器用；登录未强制时也能识别已登录用户） */
  public static AuthService.User peek(AuthService auth, HttpServletRequest r) { return resolve(auth, r); }
  static AuthService.User resolve(AuthService auth, HttpServletRequest r) {
    Object cached = r.getAttribute(ATTR); if (cached instanceof AuthService.User u) return u;
    String token = null, h = r.getHeader("Authorization");
    if (h != null && h.regionMatches(true, 0, "Bearer ", 0, 7)) token = h.substring(7).trim();
    if (token == null && r.getCookies() != null) for (var c : r.getCookies()) if (AuthApi.COOKIE.equals(c.getName()) && !c.getValue().isBlank()) token = c.getValue();
    var u = token == null ? null : auth.userFromToken(token);
    if (u == null && sseTicketPath(r.getRequestURI()) && r.getParameter("ticket") != null) u = auth.userFromTicket(r.getParameter("ticket"));
    if (u != null) r.setAttribute(ATTR, u);
    return u;
  }

  @Override public void doFilter(ServletRequest sreq, ServletResponse sres, FilterChain chain) throws IOException, ServletException {
    var req = (HttpServletRequest) sreq; var res = (HttpServletResponse) sres; String p = req.getRequestURI();
    boolean open = !auth.required || !p.startsWith("/api/") || p.startsWith("/api/auth/") || p.startsWith("/api/public/") || p.equals("/api/health") || "OPTIONS".equals(req.getMethod());
    if (open || resolve(auth, req) != null) { chain.doFilter(sreq, sres); return; }
    res.setStatus(401); res.setContentType("application/json;charset=UTF-8");
    res.getWriter().write(Json.str(Map.of("error", "unauthorized", "message", "请先用 GitHub 登录")));
  }
}
