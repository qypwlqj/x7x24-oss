package com.x7x24.tenant;

import com.x7x24.auth.AuthFilter;
import com.x7x24.auth.AuthService;
import com.x7x24.util.Json;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 在鉴权之后确定当前工作区：请求头 X-Workspace（SSE 用查询参数 ws）→ 校验成员身份 → 写入 {@link Tenant}，请求结束清理。
 * 未开启登录（本地开发 / 测试）时：按请求头选择已存在的工作区，默认遗留工作区。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class WorkspaceFilter implements Filter {
  public static final String HEADER = "X-Workspace", ATTR = "x7.workspace";
  private final AuthService auth; private final WorkspaceService ws;
  public WorkspaceFilter(AuthService auth, WorkspaceService ws) { this.auth = auth; this.ws = ws; }

  @Override public void doFilter(ServletRequest sreq, ServletResponse sres, FilterChain chain) throws IOException, ServletException {
    var req = (HttpServletRequest) sreq; var res = (HttpServletResponse) sres; String p = req.getRequestURI();
    if (!p.startsWith("/api/") || "OPTIONS".equals(req.getMethod())) { chain.doFilter(sreq, sres); return; }
    String wanted = req.getHeader(HEADER); if ((wanted == null || wanted.isBlank()) && com.x7x24.auth.AuthFilter.sseTicketPath(p)) wanted = req.getParameter("ws");
    try {
      var user = req.getAttribute(AuthFilter.ATTR) instanceof AuthService.User u ? u : AuthFilter.peek(auth, req);
      if (user != null) {
        var picked = ws.pick(ws.ensure(user.id(), user.login()), wanted);
        if (!"active".equals(picked.status())) { deny(res, 403, "workspace_suspended", "该工作区已被平台暂停"); return; }
        req.setAttribute(ATTR, picked); Tenant.set(picked.id());
        boolean write = !List.of("GET", "HEAD").contains(req.getMethod()) && !p.startsWith("/api/auth/") && !p.startsWith("/api/me/");
        if (write && "viewer".equals(picked.role()) && !viewerMayAsk(req.getMethod(), p)) { deny(res, 403, "read_only", "只读成员不能修改"); return; }
      } else if (!auth.required) {
        String id = wanted != null && !wanted.isBlank() ? resolveAnon(wanted) : Tenant.LEGACY;
        if (id == null) { deny(res, 404, "workspace_not_found", "工作区不存在"); return; }
        req.setAttribute(ATTR, new WorkspaceService.Ws(id, id, id, "personal", "owner", "active")); Tenant.set(id);
      } else {
        Tenant.set(Tenant.PLATFORM); // 公开端点（/api/auth/*、/api/health）只允许写平台级审计
      }
      chain.doFilter(sreq, sres);
    } catch (WorkspaceService.NotMember e) { deny(res, 404, "workspace_not_found", e.getMessage()); }
    finally { Tenant.clear(); }
  }
  /** D5：只读成员可以在指挥台提问（新建会话、发消息、停止），其它写操作一律拒绝；ChatService 再保证这些请求不改草稿、不生成操作卡 */
  static final java.util.regex.Pattern VIEWER_ASK = java.util.regex.Pattern.compile("^/api/chats(/[A-Za-z0-9_]{1,40}/(messages|stop))?$|^/api/tasks/[A-Za-z0-9_.-]{1,64}/say$");
  static boolean viewerMayAsk(String method, String path) { return "POST".equals(method) && VIEWER_ASK.matcher(path).matches(); }
  private String resolveAnon(String wanted) {
    if (ws.exists(wanted)) return wanted;
    var r = ws.bySlug(wanted); return r == null ? null : r.id();
  }
  private static void deny(HttpServletResponse res, int code, String err, String msg) throws IOException {
    res.setStatus(code); res.setContentType("application/json;charset=UTF-8"); res.getWriter().write(Json.str(Map.of("error", err, "message", msg)));
  }
  /** 当前工作区的角色（未开启登录的开发模式视为 owner） */
  public static String role(HttpServletRequest r) { var w = of(r); return w == null ? null : w.role(); }
  public static boolean manager(HttpServletRequest r) { return WorkspaceService.manager(role(r)); }
  /** 需要工作区 owner/admin */
  public static void requireManager(HttpServletRequest r, String what) { if (!manager(r)) throw new WorkspaceService.Forbidden("只有工作区管理员可以" + what); }
  /** 需要至少 member（viewer 只读） */
  public static void requireMember(HttpServletRequest r) { if (WorkspaceService.rank(role(r)) > 2) throw new WorkspaceService.Forbidden("只读成员不能执行此操作"); }
  /** 当前请求的工作区（含角色） */
  public static WorkspaceService.Ws of(HttpServletRequest r) { return r.getAttribute(ATTR) instanceof WorkspaceService.Ws w ? w : null; }
}
