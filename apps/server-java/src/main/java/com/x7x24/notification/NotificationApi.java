package com.x7x24.notification;

import com.x7x24.auth.AuthFilter;
import com.x7x24.auth.AuthService;
import com.x7x24.tenant.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** /me 路径允许只读成员管理自己的通知；始终从登录态取用户 id。 */
@RestController
@RequestMapping("/api/me/notifications")
public class NotificationApi {
  private final NotificationService service;
  public NotificationApi(NotificationService service) { this.service = service; }
  private String uid(HttpServletRequest r) { if (r.getAttribute(AuthFilter.ATTR) instanceof AuthService.User u) return u.id(); throw new WorkspaceService.Forbidden("请先登录后设置个人通知"); }
  private String ws(HttpServletRequest r) { var w = WorkspaceFilter.of(r); if (w == null) throw new Tenant.Missing(); return w.id(); }
  @GetMapping("/settings") public Map<String, Object> settings(HttpServletRequest r) { return service.settings(ws(r), uid(r)); }
  public record Settings(boolean emailEnabled, boolean inAppEnabled, String scope, List<String> projectIds, Map<String, Boolean> events) {}
  @PutMapping("/settings") public Map<String, Object> save(@RequestBody Settings b, HttpServletRequest r) { String u = uid(r), w = ws(r); service.saveSettings(w,u,b.emailEnabled(),b.inAppEnabled(),b.scope(),b.projectIds(),b.events()); return service.settings(w,u); }
  @PutMapping("/email") public Map<String, Object> email(@RequestBody Map<String,String> b, HttpServletRequest r) { String u = uid(r); service.saveEmail(u,b.get("email")); return service.settings(ws(r),u); }
  @PostMapping("/email/send-code") public Map<String,Object> sendCode(HttpServletRequest r) { String u = uid(r); service.sendCode(ws(r),u); return service.settings(ws(r),u); }
  @PostMapping("/email/verify") public Map<String,Object> verify(@RequestBody Map<String,String> b,HttpServletRequest r) { String u = uid(r); service.verify(u,b.get("code")); return service.settings(ws(r),u); }
  @PostMapping("/test") public Map<String,Boolean> test(HttpServletRequest r) { service.testEmail(ws(r),uid(r)); return Map.of("queued",true); }
  @GetMapping public Map<String,Object> inbox(@RequestParam(defaultValue="false") boolean history,HttpServletRequest r) { return service.inbox(ws(r),uid(r),history); }
  @PutMapping("/{id}/read") public Map<String,Boolean> read(@PathVariable String id,HttpServletRequest r) { service.read(ws(r),uid(r),id); return Map.of("ok",true); }
}
