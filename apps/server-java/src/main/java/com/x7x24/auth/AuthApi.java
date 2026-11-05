package com.x7x24.auth;

import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** /api/auth/*：登录配置、网页授权、设备码兜底、当前用户、退出。 */
@RestController
@RequestMapping("/api/auth")
public class AuthApi {
  public static final String COOKIE = "x7_session";
  private final AuthService auth;
  public AuthApi(AuthService auth) { this.auth = auth; }

  static String base(HttpServletRequest req) { return ServletUriComponentsBuilder.fromContextPath(req).replacePath(null).replaceQuery(null).build().toUriString(); }

  @GetMapping("/config") public Map<String, Object> config(HttpServletRequest req) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("required", auth.required); o.put("webFlow", auth.webFlowEnabled()); o.put("deviceFlow", !auth.clientId.isBlank()); o.put("clientId", auth.clientId);
    o.put("allowlist", auth.allowlistEnabled()); o.put("callbackUrl", auth.callbackUrl(base(req)));
    return o;
  }
  @GetMapping("/login") public ResponseEntity<?> login(@RequestParam(required = false) String redirect, HttpServletRequest req) {
    try { return ResponseEntity.status(302).location(URI.create(auth.authorizeUrl(redirect, base(req)))).build(); }
    catch (AuthService.Denied e) { return ResponseEntity.badRequest().body(Map.of("error", e.code, "message", e.getMessage())); }
  }
  /** GitHub 回调：签发会话，写 HttpOnly Cookie，并把令牌放在回跳地址的 hash 里（跨站前端用 Bearer） */
  @GetMapping("/callback") public ResponseEntity<?> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state, @RequestParam(required = false) String error, HttpServletRequest req, HttpServletResponse res) {
    String fallback; try { fallback = auth.callbackRedirect(state); } catch (Exception e) { fallback = "/"; }
    if (error != null) return redirect(fallback, "error=" + enc(error));
    try {
      String[] r = auth.callback(code, state, base(req));
      res.addHeader(HttpHeaders.SET_COOKIE, cookie(r[1], auth.sessionSeconds(), req).toString());
      return redirect(r[0], "token=" + enc(r[1]));
    } catch (AuthService.Denied e) { return redirect(fallback, "error=" + enc(e.code) + "&message=" + enc(e.getMessage())); }
  }
  private ResponseEntity<?> redirect(String to, String q) { String b = to.contains("#") ? to.substring(0, to.indexOf('#')) : to; return ResponseEntity.status(302).location(URI.create(b + "#" + q)).build(); }

  @PostMapping("/device/start") public ResponseEntity<?> deviceStart(@RequestParam(defaultValue = "false") boolean reauthorize, HttpServletRequest req) {
    var user = AuthFilter.peek(auth, req);
    if (reauthorize && user == null) return ResponseEntity.status(401).body(Map.of("error", "unauthorized", "message", "请先登录"));
    try { return ResponseEntity.ok(auth.deviceStart(reauthorize ? user.id() : null)); } catch (AuthService.Denied e) { return ResponseEntity.badRequest().body(Map.of("error", e.code, "message", e.getMessage())); }
  }
  public record Reauthorize(String redirect) {}
  @PostMapping("/reauthorize") public ResponseEntity<?> reauthorize(@RequestBody Reauthorize body, HttpServletRequest req) {
    var user = AuthFilter.peek(auth, req);
    if (user == null) return ResponseEntity.status(401).body(Map.of("error", "unauthorized", "message", "请先登录"));
    return ResponseEntity.ok(Map.of("url", auth.authorizeUrl(body.redirect(), base(req), user.id())));
  }
  /** Configure this as the GitHub App setup URL. Ownership is verified when a repository is bound. */
  @GetMapping("/github/setup") public ResponseEntity<?> setup(@RequestParam String state, @RequestParam(name = "setup_action", defaultValue = "install") String action) {
    try { return ResponseEntity.status(302).location(URI.create(auth.installationCallback(state, action))).build(); }
    catch (AuthService.Denied e) { return ResponseEntity.badRequest().body(Map.of("error", e.code, "message", e.getMessage())); }
  }
  @PostMapping("/device/poll") public ResponseEntity<?> devicePoll(@RequestParam String sessionId, HttpServletRequest req, HttpServletResponse res) {
    try {
      var r = auth.devicePoll(sessionId);
      if ("ok".equals(r.get("status"))) res.addHeader(HttpHeaders.SET_COOKIE, cookie((String) r.get("token"), auth.sessionSeconds(), req).toString());
      return ResponseEntity.ok(r);
    } catch (AuthService.Denied e) { return ResponseEntity.status(e.code.equals("not_allowed") ? 403 : 400).body(Map.of("status", "error", "error", e.code, "message", e.getMessage())); }
  }
  @GetMapping("/me") public ResponseEntity<?> me(HttpServletRequest req) {
    var u = AuthFilter.resolve(auth, req);
    if (u == null) return ResponseEntity.status(401).body(Map.of("error", "unauthorized", "required", auth.required));
    return ResponseEntity.ok(AuthService.view(u));
  }
  @PostMapping("/logout") public ResponseEntity<?> logout(HttpServletRequest req) {
    return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie("", 0, req).toString()).body(Map.of("ok", true));
  }

  /** HTTPS 下用 SameSite=None; Secure 以支持独立前端与后端跨站；本地 http 用 Lax */
  ResponseCookie cookie(String v, long maxAge, HttpServletRequest req) {
    boolean secure = req.isSecure() || "https".equalsIgnoreCase(req.getHeader("X-Forwarded-Proto")) || auth.publicUrl.startsWith("https://");
    return ResponseCookie.from(COOKIE, v).httpOnly(true).secure(secure).sameSite(secure ? "None" : "Lax").path("/").maxAge(maxAge).build();
  }
  private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
