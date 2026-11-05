package com.x7x24.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.auth.AuthFilter;
import com.x7x24.auth.AuthService;
import com.x7x24.tenant.Tenant;
import com.x7x24.tenant.WorkspaceFilter;
import com.x7x24.util.Json;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 指挥台（原需求对话）API。发消息返回 text/event-stream：tool / status / draft / delta / done / error / stopped。 */
@RestController
@RequestMapping("/api/chats")
public class ChatApi {
  private final ChatService chat; private final ChatActions actions;
  // 流式回复会占住线程直到模型说完：线程数调大（每个会话同一时间只有一轮）
  private final ExecutorService pool = Executors.newFixedThreadPool(Integer.getInteger("x7.chatTurnThreads", 16), r -> { Thread t = new Thread(r, "chat-turn"); t.setDaemon(true); return t; });
  private static final java.util.concurrent.ScheduledExecutorService HEARTBEAT = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "chat-heartbeat"); t.setDaemon(true); return t; });
  public ChatApi(ChatService chat, ChatActions actions) { this.chat = chat; this.actions = actions; }
  static String actor(HttpServletRequest r) { return r.getAttribute(AuthFilter.ATTR) instanceof AuthService.User u ? u.login() : "operator"; }

  @PostMapping public Map<String, Object> create(@RequestBody(required = false) JsonNode b, HttpServletRequest r) { return chat.create(actor(r), b == null ? null : b.path("projectId").asText(null), b == null ? null : b.path("presetId").asText(null), b == null ? null : b.path("productLineId").asText(null)); }
  /** D3：切换当前产品线 / 项目 */
  @PutMapping("/{id}/context") public Map<String, Object> context(@PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) { WorkspaceFilter.requireMember(r); return chat.setContext(id, b.path("productLineId").asText(null), b.path("projectId").asText(null)); }
  /** ?anchor=project:<id> / line:<id> / task:<id> / none 只列某个对象下的会话 */
  @GetMapping public List<Map<String, Object>> list(@RequestParam(required = false) String anchor) {
    if (anchor == null || anchor.isBlank()) return chat.list();
    int i = anchor.indexOf(':'); return chat.list(i < 0 ? anchor : anchor.substring(0, i), i < 0 ? null : anchor.substring(i + 1));
  }
  @PutMapping("/{id}/title") public Map<String, Object> rename(@PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) { WorkspaceFilter.requireMember(r); return chat.rename(id, b.path("title").asText("")); }
  @GetMapping("/pending-actions") public List<Map<String, Object>> pendingActions() { return chat.pendingActions(); }
  @GetMapping("/{id}") public Map<String, Object> get(@PathVariable String id) { return chat.get(id); }
  @PostMapping("/{id}/stop") public Map<String, Object> stop(@PathVariable String id) { chat.stop(id); return Map.of("ok", true); }
  @DeleteMapping("/{id}") public Map<String, Object> delete(@PathVariable String id, HttpServletRequest r) {
    WorkspaceFilter.requireMember(r);
    var d = chat.delete(id, actor(r), WorkspaceFilter.manager(r));
    Map<String, Object> m = new LinkedHashMap<>(); m.put("ok", true); m.put("keptTaskId", d.keptTaskId()); m.put("waitingClarification", d.waitingClarification()); return m;
  }
  @PostMapping("/{id}/abandon") public Map<String, Object> abandon(@PathVariable String id) { chat.abandon(id); return Map.of("ok", true); }
  @PutMapping("/{id}/draft") public Map<String, Object> edit(@PathVariable String id, @RequestBody JsonNode b) { return chat.editDraft(id, b.path("baseVersion").asInt(-1), b.path("patch")); }
  @PostMapping("/{id}/draft/precheck") public Object precheck(@PathVariable String id) { return chat.precheck(chat.draft(id)); }
  @PostMapping("/{id}/draft/confirm") public Object confirm(@PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    var c = chat.confirm(id, b.path("baseVersion").asInt(-1), actor(r), WorkspaceFilter.manager(r));
    Map<String, Object> m = new LinkedHashMap<>(); m.put("taskId", c.taskId()); m.put("created", c.created()); m.put("pendingApproval", c.pendingApproval()); m.put("plan", c.plan()); return m;
  }

  /** 审批含放松项的流程计划（owner / admin），通过即建任务 */
  @PostMapping("/{id}/draft/approve") public Object approve(@PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    if (!WorkspaceFilter.manager(r)) return ResponseEntity.status(403).body(Map.of("error", "forbidden", "message", "只有工作区所有者 / 管理员可以审批流程调整"));
    return confirm(id, b, r);
  }

  /** A 类操作：用户在确认卡上确认 → 以当前登录用户的身份和工作区角色执行（复用现有权限检查）；重复确认幂等 */
  @PostMapping("/{id}/actions/{aid}/confirm") public Map<String, Object> confirmAction(@PathVariable String id, @PathVariable String aid, HttpServletRequest r) {
    WorkspaceFilter.requireMember(r); chat.session(id);
    var u = r.getAttribute(AuthFilter.ATTR) instanceof AuthService.User x ? x : null;
    var d = actions.confirm(id, aid, new ChatActions.Ctx(actor(r), u == null ? null : u.id(), WorkspaceFilter.role(r)));
    // 建项目成功 → 会话进入整理需求阶段（草稿补上项目，前端展示草稿面板）
    if (d.executed() && ("create_project".equals(d.action().get("kind")) || "bind_repo".equals(d.action().get("kind"))) && d.action().get("result") instanceof JsonNode res && res.hasNonNull("projectId")) chat.enterDrafting(id, res.path("projectId").asText());
    Map<String, Object> m = new LinkedHashMap<>(); m.put("action", d.action()); m.put("executed", d.executed()); m.put("phase", chat.session(id).get("phase")); if (d.inviteToken() != null) m.put("inviteToken", d.inviteToken()); return m;
  }
  /** 卡片内编辑参数（只限待确认） */
  @PutMapping("/{id}/actions/{aid}/params") public Map<String, Object> editAction(@PathVariable String id, @PathVariable String aid, @RequestBody JsonNode b, HttpServletRequest r) { WorkspaceFilter.requireMember(r); chat.session(id); return actions.editParams(id, aid, b, actor(r)); }
  /** 失败 / 已取消的卡片「重新发起」：新建一张待确认卡 */
  @PostMapping("/{id}/actions/{aid}/repropose") public Map<String, Object> repropose(@PathVariable String id, @PathVariable String aid, @RequestBody(required = false) JsonNode b, HttpServletRequest r) { WorkspaceFilter.requireMember(r); chat.session(id); return actions.repropose(id, aid, b); }
  /** 建项目卡片的 owner 下拉：工作区绑定的 GitHub 账号 / 组织 + 当前登录用户 */
  @GetMapping("/repo-owners") public Map<String, Object> owners(HttpServletRequest r) {
    List<String> os = new ArrayList<>(actions.owners()); String me = r.getAttribute(AuthFilter.ATTR) instanceof AuthService.User u ? u.login() : null;
    if (me != null && !os.contains(me)) os.add(me);
    Map<String, Object> m = new LinkedHashMap<>(); m.put("owners", os); m.put("default", os.isEmpty() ? null : os.get(0)); return m;
  }
  @PostMapping("/{id}/actions/{aid}/cancel") public Map<String, Object> cancelAction(@PathVariable String id, @PathVariable String aid, HttpServletRequest r) { WorkspaceFilter.requireMember(r); chat.session(id); return actions.cancel(id, aid, actor(r)); }

  @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> send(@PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    String text = b.path("text").asText(""); if (text.isBlank()) throw new IllegalArgumentException("消息不能为空");
    chat.preflight(id); // 会话不存在 / 上限 / 并发 → 直接返回 HTTP 错误
    // created_by 记录发起人
    String actor = actor(r); boolean readOnly = "viewer".equals(WorkspaceFilter.role(r)); var em = new SseEmitter(10 * 60_000L);
    // 每 15 秒一个注释行心跳，防止反代 / 浏览器在模型思考时断开；发送失败 = 浏览器已断开 → 停止这一轮（中止上游模型请求）
    var beat = HEARTBEAT.scheduleAtFixedRate(() -> { try { em.send(SseEmitter.event().comment("ping")); } catch (Exception io) { chat.stopQuiet(id); } }, 15, 15, java.util.concurrent.TimeUnit.SECONDS);
    em.onCompletion(() -> beat.cancel(false)); em.onTimeout(() -> { beat.cancel(false); chat.stopQuiet(id); }); em.onError(x -> { beat.cancel(false); chat.stopQuiet(id); });
    pool.submit(Tenant.wrap(() -> {
      try {
        chat.turn(id, actor, text, e -> { try { em.send(SseEmitter.event().name(e.type()).data(Json.str(e.data()), MediaType.APPLICATION_JSON)); } catch (Exception io) { chat.stop(id); } }, readOnly);
        em.complete();
      } catch (RuntimeException e) {
        try { em.send(SseEmitter.event().name("error").data(Json.str(Map.of("message", com.x7x24.adapters.Redactor.redact(e.getMessage()))), MediaType.APPLICATION_JSON)); } catch (Exception ignore) { }
        em.complete();
      } finally { beat.cancel(false); }
      return null;
    }));
    return ResponseEntity.ok().header("X-Accel-Buffering", "no").header("Cache-Control", "no-cache, no-transform").contentType(MediaType.TEXT_EVENT_STREAM).body(em);
  }

  /** 操作完成后续一轮「下一步」提示（流式），每个操作只续一次 */
  @PostMapping(value = "/{id}/actions/{aid}/followup", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> followup(@PathVariable String id, @PathVariable String aid, HttpServletRequest r) {
    WorkspaceFilter.requireMember(r); if ("viewer".equals(WorkspaceFilter.role(r))) throw new com.x7x24.tenant.WorkspaceService.Forbidden("只读成员不能发起对话");
    chat.claimFollowup(id, aid);
    var em = new SseEmitter(5 * 60_000L);
    pool.submit(Tenant.wrap(() -> {
      try { chat.followup(id, aid, e -> { try { em.send(SseEmitter.event().name(e.type()).data(Json.str(e.data()), MediaType.APPLICATION_JSON)); } catch (Exception io) { chat.stopQuiet(id); } }); em.complete(); }
      catch (RuntimeException e) { try { em.send(SseEmitter.event().name("error").data(Json.str(Map.of("message", com.x7x24.adapters.Redactor.redact(e.getMessage()))), MediaType.APPLICATION_JSON)); } catch (Exception ignore) { } em.complete(); }
      return null;
    }));
    return ResponseEntity.ok().header("X-Accel-Buffering", "no").header("Cache-Control", "no-cache, no-transform").contentType(MediaType.TEXT_EVENT_STREAM).body(em);
  }
  @ExceptionHandler(ChatActions.Invalid.class) ResponseEntity<Map<String, Object>> invalid(ChatActions.Invalid e) {
    Map<String, Object> m = new LinkedHashMap<>(); m.put("error", "invalid"); m.put("message", e.getMessage()); m.put("field", e.field); m.put("action", e.action); return ResponseEntity.status(400).body(m);
  }
  @ExceptionHandler(ChatService.Conflict.class) ResponseEntity<Map<String, Object>> conflict(RuntimeException e) { return ResponseEntity.status(409).body(Map.of("error", "conflict", "message", e.getMessage())); }
  @ExceptionHandler(ChatService.CapReached.class) ResponseEntity<Map<String, Object>> cap(RuntimeException e) { return ResponseEntity.status(429).body(Map.of("error", "cap_reached", "message", e.getMessage())); }
}
