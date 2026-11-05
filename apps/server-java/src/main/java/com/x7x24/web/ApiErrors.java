package com.x7x24.web;

import com.x7x24.adapters.Redactor;
import com.x7x24.gov.KillSwitch;
import com.x7x24.gov.Risk;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
  private static final java.util.regex.Pattern CJK = java.util.regex.Pattern.compile("[\\u4e00-\\u9fff]");
  /** 统一错误体：error = 给人看的中文说明；code = 机器可识别的错误码；detail = 原始信息（已脱敏，前端放进「详情」） */
  static Map<String, Object> body(String code, String message, Map<String, Object> params, String raw) {
    Map<String, Object> o = new java.util.LinkedHashMap<>(); o.put("error", message); o.put("code", code);
    if (params != null && !params.isEmpty()) o.put("params", params); if (raw != null && !raw.equals(message)) o.put("detail", raw); return o;
  }
  /** 没有中文的原始异常信息不直接给用户看：换成通用中文说明，原文放 detail */
  static Map<String, Object> human(String code, String fallback, Throwable error) {
    String raw = Redactor.redact(error); return CJK.matcher(raw == null ? "" : raw).find() ? body(code, raw, null, null) : body(code, fallback, null, raw);
  }
  @ExceptionHandler(UserError.class)
  ResponseEntity<?> user(UserError error) { return ResponseEntity.badRequest().body(body(error.code, Redactor.redact(error), error.params, null)); }
  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<?> bad(IllegalArgumentException error) { return ResponseEntity.badRequest().body(human("bad_request", "请求内容不正确，请检查后重试", error)); }
  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> conflict(DataIntegrityViolationException error) { return ResponseEntity.status(409).body(body("conflict", "数据和别处冲突了（可能有人刚改过），请刷新后重试", null, null)); }
  @ExceptionHandler(KillSwitch.Blocked.class)
  ResponseEntity<?> blocked(KillSwitch.Blocked error) { return ResponseEntity.status(423).body(human("kill_switch", "紧急停止已启用，暂时不能执行这个操作", error)); }
  @ExceptionHandler(Risk.PolicyDenied.class)
  ResponseEntity<?> denied(Risk.PolicyDenied error) { return ResponseEntity.status(403).body(human("policy_denied", "这个操作在禁止区域内，已被拦截", error)); }
  @ExceptionHandler(com.x7x24.adapters.GitHubClient.GitHubException.class)
  ResponseEntity<?> github(com.x7x24.adapters.GitHubClient.GitHubException error) {
    // GitHub 拒绝令牌≠本站登录失效：不能回 401，否则前端会当成会话过期把用户登出回首页
    return ResponseEntity.status(502).body(Map.of("error", com.x7x24.integration.Humanize.reason(error.status, error.getMessage()), "code", error instanceof com.x7x24.adapters.GitHubClient.RateLimited ? "github_rate_limited" : error.status == 401 ? "github_unauthorized" : "github_error", "githubStatus", error.status));
  }
  @ExceptionHandler(com.x7x24.adapters.DeliveryTools.GateBlocked.class)
  ResponseEntity<?> gate(com.x7x24.adapters.DeliveryTools.GateBlocked error) { return ResponseEntity.status(409).body(human("gate_blocked", "合并前检查没有通过，暂时不能继续", error)); }
  @ExceptionHandler(com.x7x24.tenant.WorkspaceService.NotMember.class)
  ResponseEntity<?> notMember(RuntimeException error) { return ResponseEntity.status(404).body(Map.of("error", error.getMessage())); }
  @ExceptionHandler(com.x7x24.tenant.WorkspaceService.Forbidden.class)
  ResponseEntity<?> wsForbidden(RuntimeException error) { return ResponseEntity.status(403).body(Map.of("error", error.getMessage())); }
  @ExceptionHandler(com.x7x24.tenant.Tenant.Missing.class)
  ResponseEntity<?> noTenant(RuntimeException error) { return ResponseEntity.status(400).body(body("no_workspace", "请先选择一个工作区", null, null)); }
  @ExceptionHandler(RuntimeException.class)
  ResponseEntity<?> failed(RuntimeException error) { return ResponseEntity.internalServerError().body(human("server_error", "服务器处理出错了，请稍后重试；一直出现的话，请把「详情」发给管理员", error)); }
}
