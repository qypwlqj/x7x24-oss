package com.x7x24.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.auth.AuthService;
import com.x7x24.chat.ChatService;
import com.x7x24.llm.MockProvider;
import com.x7x24.util.Json;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.MediaType;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 需求对话里的 A 类操作：提议 → 确认卡 → 以当前用户权限执行；幂等、不提权、不信任模型输出、离线规则可用。 */
class ChatActionsIT extends Harness {
  @Autowired TestRestTemplate http; @Autowired AuthService auth; @Autowired ChatService chat; @Autowired com.x7x24.chat.ChatActions actionsBean;
  record U(String id, String login, String token) {}
  final AtomicInteger llmCalls = new AtomicInteger();

  @AfterEach void reset() { chat.providerOverride = null; }
  String newWs() { String id = "ws_a" + System.nanoTime(); db.update("INSERT INTO workspace(id,slug,name,kind,plan_quota,status,created_at) VALUES(?,?,?,?,?,?,?)", id, id.replace('_', '-'), id, "team", "{}", "active", clk.iso()); return id; }
  U member(String ws, String role) {
    long g = System.nanoTime(); String uid = "u_a" + g, login = "a" + g;
    db.update("INSERT INTO app_user(id,github_id,login,created_at,last_login_at) VALUES(?,?,?,?,?)", uid, g, login, clk.iso(), clk.iso());
    db.update("INSERT INTO workspace_member(workspace_id,user_id,role,created_at) VALUES(?,?,?,?)", ws, uid, role, clk.iso());
    return new U(uid, login, auth.issueSession(new AuthService.User(uid, g, login, null, null)));
  }
  JsonNode call(String ws, U u, HttpMethod m, String path, Object body) {
    var h = new HttpHeaders(); h.set("X-Workspace", ws); if (u != null) h.setBearerAuth(u.token); h.setContentType(MediaType.APPLICATION_JSON);
    var res = http.exchange(path, m, new HttpEntity<>(body, h), String.class);
    var o = Json.M.createObjectNode(); o.put("_status", res.getStatusCode().value()); o.put("_raw", String.valueOf(res.getBody()));
    try { o.set("body", res.getBody() == null || res.getBody().isBlank() || res.getBody().startsWith("event:") ? Json.M.createObjectNode() : Json.tree(res.getBody())); } catch (Exception e) { o.set("body", Json.M.createObjectNode()); }
    return o;
  }
  int st(JsonNode n) { return n.path("_status").asInt(); }
  void llm(String out) { chat.providerOverride = new MockProvider("mock-actions", r -> { llmCalls.incrementAndGet(); return out; }); }
  void llmDown() { chat.providerOverride = new MockProvider("mock-down", r -> { llmCalls.incrementAndGet(); throw new RuntimeException("模型不可用"); }); }
  String newChat(String ws, U u) { var r = call(ws, u, HttpMethod.POST, "/api/chats", Map.of()); assertEquals(200, st(r), r.toString()); return r.path("body").path("id").asText(); }
  String say(String ws, U u, String chatId, String text) { var r = call(ws, u, HttpMethod.POST, "/api/chats/" + chatId + "/messages", Map.of("text", text)); assertEquals(200, st(r), r.toString()); return r.path("_raw").asText(); }
  JsonNode actions(String ws, U u, String chatId) { return call(ws, u, HttpMethod.GET, "/api/chats/" + chatId, null).path("body").path("actions"); }
  JsonNode confirm(String ws, U u, String chatId, String aid) { return call(ws, u, HttpMethod.POST, "/api/chats/" + chatId + "/actions/" + aid + "/confirm", Map.of()); }
  long lines(String ws, String name) { return db.count("SELECT COUNT(*) FROM product_line WHERE workspace_id=? AND name=?", ws, name); }

  @Test void offlineRuleCreatesLineOnlyAfterConfirmAndIsIdempotent() {
    String ws = newWs(); U owner = member(ws, "owner"); llmDown();
    String c = newChat(ws, owner);
    String sse = say(ws, owner, c, "新增产品线「教育」");
    assertTrue(sse.contains("event:action"), sse); assertEquals(0, llmCalls.get(), "明确命令不调用模型");
    assertEquals(0, lines(ws, "教育"), "确认前不执行");
    var a = actions(ws, owner, c); assertEquals(1, a.size()); assertEquals("proposed", a.get(0).path("status").asText()); assertEquals("教育", a.get(0).path("params").path("name").asText());
    String aid = a.get(0).path("id").asText();
    var r1 = confirm(ws, owner, c, aid); assertEquals(200, st(r1), r1.toString()); assertTrue(r1.path("body").path("executed").asBoolean());
    var r2 = confirm(ws, owner, c, aid); assertEquals(200, st(r2)); assertFalse(r2.path("body").path("executed").asBoolean(), "重复确认幂等");
    assertEquals(1, lines(ws, "教育"));
    assertEquals(1, db.count("SELECT COUNT(*) FROM task_event WHERE event_type='CHAT_ACTION_EXECUTED' AND actor=?", owner.login));
    assertTrue(call(ws, owner, HttpMethod.GET, "/api/chats/" + c, null).path("_raw").asText().contains("已新增产品线「教育」"));
  }

  @Test void memberCannotCreateLineAndCancelWorks() {
    String ws = newWs(); U m = member(ws, "member"); llmDown();
    String c = newChat(ws, m); say(ws, m, c, "创建产品线 支付");
    String aid = actions(ws, m, c).get(0).path("id").asText();
    assertEquals(403, st(confirm(ws, m, c, aid)), "成员不能建产品线：沿用现有权限");
    assertEquals(0, lines(ws, "支付")); assertEquals("proposed", actions(ws, m, c).get(0).path("status").asText());
    assertEquals("admin", actions(ws, m, c).get(0).path("blockedRole").asText(), "D4：记住需要的角色");
    assertEquals(1, db.count("SELECT COUNT(*) FROM task_event WHERE event_type='CHAT_ACTION_DENIED' AND actor=?", m.login));
    assertEquals(200, st(call(ws, m, HttpMethod.POST, "/api/chats/" + c + "/actions/" + aid + "/cancel", Map.of())));
    assertEquals(409, st(confirm(ws, m, c, aid)), "取消后不能再确认");
  }

  @Test void llmProposedProjectIsRevalidatedAndCreatedUnderLine() {
    String ws = newWs(); U owner = member(ws, "owner");
    llm("{\"reply\":\"好\",\"actions\":[{\"kind\":\"create_project\",\"params\":{\"name\":\"官网\",\"productLineName\":\"不存在的线\"}}]}");
    String c = newChat(ws, owner); String sse = say(ws, owner, c, "帮我在不存在的线下面建个官网项目");
    assertEquals(0, actions(ws, owner, c).size(), "产品线不存在：提议被拒绝，不产生确认卡"); assertTrue(sse.contains("提议被拒绝"), sse);
    llmDown(); say(ws, owner, c, "新增产品线 教育"); confirm(ws, owner, c, actions(ws, owner, c).get(0).path("id").asText());
    String lineId = (String) db.row("SELECT id FROM product_line WHERE workspace_id=? AND name=?", ws, "教育").get("id");
    llm("{\"reply\":\"请确认\",\"actions\":[{\"kind\":\"create_project\",\"params\":{\"name\":\"官网\",\"productLineId\":\"" + lineId + "\",\"repo\":\"https://github.com/acme/site.git\",\"workspace_id\":\"ws_evil\",\"owner\":\"root\"}},{\"kind\":\"delete_project\",\"params\":{}}]}");
    say(ws, owner, c, "在教育下建官网项目，仓库 acme/site");
    var a = actions(ws, owner, c); var card = a.get(a.size() - 1);
    assertEquals("create_project", card.path("kind").asText()); assertEquals("acme/site", card.path("params").path("repo").asText());
    assertFalse(card.path("params").has("workspace_id")); assertFalse(card.path("params").has("owner"), "白名单之外的字段被丢弃");
    assertEquals(0, db.count("SELECT COUNT(*) FROM chat_action WHERE session_id=? AND kind='delete_project'", c), "不支持的操作不会生成卡片");
    assertEquals(200, st(confirm(ws, owner, c, card.path("id").asText())));
    var p = db.row("SELECT product_line_id, workspace_id, owner FROM project WHERE name=? AND workspace_id=?", "官网", ws);
    assertEquals(lineId, p.get("product_line_id")); assertEquals(owner.login, p.get("owner"));
  }

  @Test void inviteRespectsRoleRulesAndNeverElevates() {
    String ws = newWs(); U owner = member(ws, "owner"), admin = member(ws, "admin"), m = member(ws, "member"); llmDown();
    String c = newChat(ws, admin);
    say(ws, admin, c, "邀请 @octocat 为管理员");
    String a1 = actions(ws, admin, c).get(0).path("id").asText();
    assertEquals(403, st(confirm(ws, admin, c, a1)), "管理员不能邀请管理员");
    assertEquals(0, db.count("SELECT COUNT(*) FROM workspace_invite WHERE workspace_id=?", ws));
    var ok = confirm(ws, owner, c, a1); assertEquals(200, st(ok), "所有者可以确认同一张卡");
    assertFalse(ok.path("body").path("inviteToken").asText("").isBlank(), "邀请令牌只在确认响应里返回一次");
    assertFalse(db.row("SELECT result FROM chat_action WHERE id=?", a1).get("result").toString().contains(ok.path("body").path("inviteToken").asText()), "结果不保存令牌");
    assertFalse(confirm(ws, owner, c, a1).path("body").has("inviteToken"), "重复确认不再返回令牌");
    say(ws, admin, c, "邀请 bob 为成员"); var list = actions(ws, admin, c);
    assertEquals(403, st(confirm(ws, m, c, list.get(list.size() - 1).path("id").asText())), "普通成员不能邀请");
    llm("{\"reply\":\"好\",\"actions\":[{\"kind\":\"invite_member\",\"params\":{\"login\":\"eve\",\"role\":\"owner\"}}]}");
    say(ws, owner, c, "把 eve 设成所有者"); assertEquals(0, db.count("SELECT COUNT(*) FROM chat_action WHERE session_id=? AND params LIKE '%owner%'", c), "不能通过对话邀请所有者");
  }

  @Test void otherWorkspaceCannotConfirm() {
    String ws = newWs(), other = newWs(); U owner = member(ws, "owner"), stranger = member(other, "owner"); llmDown();
    String c = newChat(ws, owner); say(ws, owner, c, "新增产品线 隔离测试"); String aid = actions(ws, owner, c).get(0).path("id").asText();
    assertEquals(404, st(confirm(other, stranger, c, aid))); assertEquals(0, lines(ws, "隔离测试"));
  }

  @Test void sessionRemembersProductLineAndListCountsActions() {
    String ws = newWs(), other = newWs(); U owner = member(ws, "owner"), stranger = member(other, "owner"); llmDown();
    String c0 = newChat(ws, owner); say(ws, owner, c0, "新增产品线 教育"); confirm(ws, owner, c0, actions(ws, owner, c0).get(0).path("id").asText());
    say(ws, owner, c0, "新增产品线 医疗"); confirm(ws, owner, c0, actions(ws, owner, c0).get(1).path("id").asText());
    String edu = (String) db.row("SELECT id FROM product_line WHERE workspace_id=? AND name=?", ws, "教育").get("id");
    String med = (String) db.row("SELECT id FROM product_line WHERE workspace_id=? AND name=?", ws, "医疗").get("id");
    var r = call(ws, owner, HttpMethod.POST, "/api/chats", Map.of("productLineId", med)); assertEquals(200, st(r), r.toString());
    String c = r.path("body").path("id").asText(); assertEquals(med, r.path("body").path("product_line_id").asText());
    assertEquals(400, st(call(other, stranger, HttpMethod.POST, "/api/chats", Map.of("productLineId", edu))), "别的工作区的产品线不能用");
    assertEquals(400, st(call(ws, owner, HttpMethod.PUT, "/api/chats/" + c + "/context", Map.of("productLineId", "line-nope"))));
    assertEquals(200, st(call(ws, owner, HttpMethod.PUT, "/api/chats/" + c + "/context", Map.of("productLineId", edu))));
    say(ws, owner, c, "新建项目 官网");
    assertEquals(edu, actions(ws, owner, c).get(0).path("params").path("productLineId").asText(), "没说产品线就用会话上下文");
    var list = call(ws, owner, HttpMethod.GET, "/api/chats", null).path("body"); boolean found = false;
    for (var x : list) if (x.path("id").asText().equals(c0)) { assertEquals(2, x.path("action_count").asInt()); found = true; }
    assertTrue(found, list.toString());
  }

  @Test void viewerMayAskButNeverChangesAnything() {
    String ws = newWs(); U owner = member(ws, "owner"), v = member(ws, "viewer");
    String c = newChat(ws, v);
    final java.util.List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
    chat.providerOverride = new MockProvider("mock-ro", r -> { seen.add(r.prompt()); return "{\"reply\":\"现在有 0 个任务卡住\",\"draft\":{\"title\":\"偷改\"},\"actions\":[{\"kind\":\"create_product_line\",\"params\":{\"name\":\"偷建\"}}]}"; });
    String sse = say(ws, v, c, "这周有哪些任务卡住了？");
    assertTrue(sse.contains("0 个任务卡住"), sse); assertTrue(seen.get(0).contains("当前用户是只读成员"));
    var g = call(ws, v, HttpMethod.GET, "/api/chats/" + c, null).path("body");
    assertEquals(0, g.path("draft_version").asInt(), "只读成员的这一轮不改草稿"); assertEquals(0, g.path("actions").size(), "也不生成操作卡");
    say(ws, v, c, "新增产品线 偷建"); assertEquals(0, actions(ws, v, c).size()); assertEquals(0, lines(ws, "偷建"));
    assertTrue(call(ws, v, HttpMethod.GET, "/api/chats/" + c, null).path("_raw").asText().contains("你是只读成员"));
    assertEquals(403, st(call(ws, v, HttpMethod.PUT, "/api/chats/" + c + "/draft", Map.of("baseVersion", 0, "patch", Map.of("title", "x")))));
    assertEquals(403, st(call(ws, v, HttpMethod.POST, "/api/chats/" + c + "/draft/confirm", Map.of("baseVersion", 0))));
    assertEquals(403, st(call(ws, v, HttpMethod.DELETE, "/api/chats/" + c, null)));
    assertEquals(403, st(call(ws, v, HttpMethod.PUT, "/api/chats/" + c + "/context", Map.of("productLineId", ""))));
    // 管理员提的卡，只读成员也不能确认 / 取消
    llmDown(); String c2 = newChat(ws, owner); say(ws, owner, c2, "新增产品线 教育"); String aid = actions(ws, owner, c2).get(0).path("id").asText();
    assertEquals(403, st(confirm(ws, v, c2, aid))); assertEquals(403, st(call(ws, v, HttpMethod.POST, "/api/chats/" + c2 + "/actions/" + aid + "/cancel", Map.of())));
  }

  @Test void categoryBGetsGuideWithFixedLinkNotACard() {
    String ws = newWs(); U owner = member(ws, "owner"); llmDown();
    String c = newChat(ws, owner);
    String sse = say(ws, owner, c, "删除项目 官网");
    assertTrue(sse.contains("event:guide") && sse.contains("/catalog"), sse); assertEquals(0, llmCalls.get()); assertEquals(0, actions(ws, owner, c).size());
    llm("{\"reply\":\"去预算页\",\"guide\":{\"topic\":\"budget\",\"href\":\"https://evil.example\"}}");
    sse = say(ws, owner, c, "能不能多给点钱");
    assertTrue(sse.contains("/budget") && !sse.contains("evil.example"), "链接来自服务端固定表");
    llm("{\"reply\":\"好\",\"guide\":{\"topic\":\"rm_rf\"}}");
    assertFalse(say(ws, owner, c, "随便").contains("event:guide"), "未知主题忽略");
    assertNull(com.x7x24.chat.Guides.parse("支持删除项目功能"), "普通需求不被拦");
  }

  @Test void personaIsCommandDeck() {
    assertTrue(com.x7x24.chat.IntakePrompt.SYSTEM.contains("7x24 助手")); assertFalse(com.x7x24.chat.IntakePrompt.SYSTEM.contains("需求录入与工作流编排助手"));
    assertEquals("command-v1", ChatService.PROMPT_VERSION);
  }

  @Test void createdProjectAnchorsSessionAndListsUnderProjectAndLine() {
    String ws = newWs(); U owner = member(ws, "owner"); llmDown();
    String c = newChat(ws, owner); say(ws, owner, c, "新增产品线「教育」");
    var row = db.row("SELECT anchor_kind, anchor_id, title FROM chat_session WHERE id=?", c);
    assertEquals("none", row.get("anchor_kind")); assertEquals("新增产品线「教育」", row.get("title"), "标题兜底：第一条消息");
    assertEquals(200, st(confirm(ws, owner, c, actions(ws, owner, c).get(0).path("id").asText())));
    String line = (String) db.row("SELECT id FROM product_line WHERE workspace_id=? AND name=?", ws, "教育").get("id");
    assertEquals("line", db.row("SELECT anchor_kind FROM chat_session WHERE id=?", c).get("anchor_kind"));
    llm("{\"reply\":\"好\",\"actions\":[{\"kind\":\"create_project\",\"params\":{\"name\":\"官网\",\"productLineName\":\"教育\",\"repo\":\"acme/anchor" + System.nanoTime() + "\"}}]}");
    say(ws, owner, c, "帮我在教育下建个官网项目");
    var a = actions(ws, owner, c); String aid = a.get(a.size() - 1).path("id").asText();
    var rp = confirm(ws, owner, c, aid); assertEquals(200, st(rp), rp.toString() + actions(ws, owner, c));
    String pid = (String) db.row("SELECT project_id FROM chat_session WHERE id=?", c).get("project_id"); assertNotNull(pid, "建项目后会话绑定项目");
    assertEquals("project", db.row("SELECT anchor_kind FROM chat_session WHERE id=?", c).get("anchor_kind"));
    var byProject = call(ws, owner, HttpMethod.GET, "/api/chats?anchor=project:" + pid, null).path("body");
    assertEquals(1, byProject.size()); assertEquals(c, byProject.get(0).path("id").asText()); assertEquals("官网", byProject.get(0).path("anchor_name").asText());
    assertEquals(1, call(ws, owner, HttpMethod.GET, "/api/chats?anchor=line:" + line, null).path("body").size(), "产品线页包含下属项目的会话");
    assertEquals(0, call(ws, owner, HttpMethod.GET, "/api/chats?anchor=none", null).path("body").size());
    assertEquals(200, st(call(ws, owner, HttpMethod.PUT, "/api/chats/" + c + "/title", Map.of("title", "建官网"))));
    assertEquals("建官网", db.row("SELECT title FROM chat_session WHERE id=?", c).get("title"));
  }

  String lineFor(String ws, U owner, String name) {
    llmDown(); String c = newChat(ws, owner); say(ws, owner, c, "新增产品线「" + name + "」");
    confirm(ws, owner, c, actions(ws, owner, c).get(0).path("id").asText());
    return (String) db.rows("SELECT id FROM product_line WHERE workspace_id=? AND name=?", ws, name).get(0).get("id");
  }

  @Test void projectWithoutRepoIsCreatedAndTasksAreRefused() {
    String ws = newWs(); U owner = member(ws, "owner"); lineFor(ws, owner, "线N");
    llm("{\"reply\":\"好\",\"actions\":[{\"kind\":\"create_project\",\"params\":{\"name\":\"无仓库项目\",\"productLineName\":\"线N\"}}]}");
    String c = newChat(ws, owner); say(ws, owner, c, "建个项目，仓库先不绑");
    var a = actions(ws, owner, c).get(0); assertTrue(a.path("params").path("repo").isMissingNode() || a.path("params").path("repo").isNull(), a.toString());
    var r = confirm(ws, owner, c, a.path("id").asText()); assertEquals(200, st(r), r.toString()); assertTrue(r.path("body").path("executed").asBoolean());
    assertEquals(1, db.count("SELECT COUNT(*) FROM project WHERE workspace_id=? AND name=?", ws, "无仓库项目"));
  }

  @Test void ownerDefaultsToBoundInstallationAccount() {
    String ws = newWs(); U owner = member(ws, "owner"); lineFor(ws, owner, "线O");
    db.update("INSERT INTO github_installation(installation_id,workspace_id,account_login,verified_by_user,created_at) VALUES(?,?,?,?,?)", System.nanoTime() % 1_000_000_000L, ws, "acme-org", owner.id, clk.iso());
    llm("{\"reply\":\"好\",\"actions\":[{\"kind\":\"create_project\",\"params\":{\"name\":\"官网O\",\"productLineName\":\"线O\",\"repo\":\"site\"}}]}");
    String c = newChat(ws, owner); say(ws, owner, c, "建个官网");
    assertEquals("acme-org/site", actions(ws, owner, c).get(0).path("params").path("repo").asText());
    var os = call(ws, owner, HttpMethod.GET, "/api/chats/repo-owners", null); assertEquals("acme-org", os.path("body").path("default").asText(), os.toString());
  }

  @Test void invalidConfirmKeepsCardEditableThenEditAndRepropose() {
    String ws = newWs(); U owner = member(ws, "owner"); lineFor(ws, owner, "线E");
    llm("{\"reply\":\"好\",\"actions\":[{\"kind\":\"create_project\",\"params\":{\"name\":\"项目E\",\"productLineName\":\"线E\",\"repo\":\"o/r" + System.nanoTime() + "\"}}]}");
    String c = newChat(ws, owner); say(ws, owner, c, "建项目E");
    String aid = actions(ws, owner, c).get(0).path("id").asText();
    var prev = actionsBean.repoAccessible; actionsBean.repoAccessible = x -> false;
    var r = confirm(ws, owner, c, aid); assertEquals(400, st(r), r.toString());
    assertEquals("proposed", r.path("body").path("action").path("status").asText(), "校验失败不是终态"); assertFalse(r.path("body").path("message").asText().isBlank());
    assertEquals("proposed", actions(ws, owner, c).get(0).path("status").asText());
    assertEquals("repo", r.path("body").path("field").asText()); actionsBean.repoAccessible = prev;
    var e = call(ws, owner, HttpMethod.PUT, "/api/chats/" + c + "/actions/" + aid + "/params", Map.of("bindRepo", false)); assertEquals(200, st(e), e.toString());
    assertTrue(e.path("body").path("params").path("repo").isMissingNode() || e.path("body").path("params").path("repo").isNull(), e.toString());
    var bad = call(ws, owner, HttpMethod.PUT, "/api/chats/" + c + "/actions/" + aid + "/params", Map.of("bindRepo", true, "repo", "a/b c!")); assertEquals(400, st(bad)); assertEquals("repo", bad.path("body").path("field").asText(), bad.toString());
    assertEquals(200, st(confirm(ws, owner, c, aid)));
    assertEquals(409, st(call(ws, owner, HttpMethod.PUT, "/api/chats/" + c + "/actions/" + aid + "/params", Map.of("name", "x"))), "已执行不能再改");
    // 失败卡「重新发起」
    db.update("UPDATE chat_action SET status='failed' WHERE id=?", aid);
    var rp = call(ws, owner, HttpMethod.POST, "/api/chats/" + c + "/actions/" + aid + "/repropose", Map.of("name", "项目E2")); assertEquals(200, st(rp), rp.toString());
    assertEquals("proposed", rp.path("body").path("status").asText());
  }

  @Test void pendingActionsListedForInboxAndSessionsCarryCount() {
    String ws = newWs(); U owner = member(ws, "owner"); llmDown();
    String c = newChat(ws, owner); say(ws, owner, c, "新增产品线「待办线」");
    var p = call(ws, owner, HttpMethod.GET, "/api/chats/pending-actions", null); assertEquals(200, st(p), p.toString());
    assertEquals(1, p.path("body").size()); assertEquals(c, p.path("body").get(0).path("session_id").asText()); assertEquals("待办线", p.path("body").get(0).path("params").path("name").asText());
    var list = call(ws, owner, HttpMethod.GET, "/api/chats", null).path("body"); assertEquals(1, list.get(0).path("pending_actions").asInt());
    confirm(ws, owner, c, p.path("body").get(0).path("id").asText());
    assertEquals(0, call(ws, owner, HttpMethod.GET, "/api/chats/pending-actions", null).path("body").size(), "确认后从待我处理消失");
  }

  @Test void setupPhaseHidesDraftUntilProjectIsCreated() {
    String ws = newWs(); U owner = member(ws, "owner"); lineFor(ws, owner, "线P");
    llm("{\"reply\":\"好\",\"actions\":[{\"kind\":\"create_project\",\"params\":{\"name\":\"项目P\",\"productLineName\":\"线P\"}}],\"draft\":{\"title\":\"成绩导出\",\"goal\":\"老师导出成绩\"}}");
    String c = newChat(ws, owner);
    assertEquals("setup", call(ws, owner, HttpMethod.GET, "/api/chats/" + c, null).path("body").path("phase").asText());
    String sse = say(ws, owner, c, "建个项目P，做成绩导出");
    assertFalse(sse.contains("event:draft"), "setup 阶段不推送草稿"); 
    var g = call(ws, owner, HttpMethod.GET, "/api/chats/" + c, null).path("body");
    assertEquals("成绩导出", g.path("draft").path("title").asText(), "草稿照常暂存"); assertTrue(g.path("precheck").isNull(), "setup 不跑预检");
    var r = confirm(ws, owner, c, actions(ws, owner, c).get(0).path("id").asText()); assertEquals("drafting", r.path("body").path("phase").asText(), r.toString());
    g = call(ws, owner, HttpMethod.GET, "/api/chats/" + c, null).path("body");
    assertEquals("drafting", g.path("phase").asText()); assertFalse(g.path("draft").path("projectId").asText("").isBlank(), "草稿补上新项目"); assertFalse(g.path("precheck").isNull());
    assertTrue(g.path("messages").toString().contains("我已把刚才的需求整理到右侧，请检查"));
    // 只进不退：再切上下文到无项目也保持 drafting
    call(ws, owner, HttpMethod.PUT, "/api/chats/" + c + "/context", Map.of());
    assertEquals("drafting", call(ws, owner, HttpMethod.GET, "/api/chats/" + c, null).path("body").path("phase").asText());
  }

  @Test void choosingProjectOrStartingFromProjectEntersDrafting() {
    String ws = newWs(); U owner = member(ws, "owner"); lineFor(ws, owner, "线Q");
    llm("{\"reply\":\"好\",\"actions\":[{\"kind\":\"create_project\",\"params\":{\"name\":\"项目Q\",\"productLineName\":\"线Q\"}}]}");
    String c0 = newChat(ws, owner); say(ws, owner, c0, "建项目Q"); confirm(ws, owner, c0, actions(ws, owner, c0).get(0).path("id").asText());
    String pid = (String) db.rows("SELECT id FROM project WHERE workspace_id=? AND name=?", ws, "项目Q").get(0).get("id");
    var fromProject = call(ws, owner, HttpMethod.POST, "/api/chats", Map.of("projectId", pid)); assertEquals("drafting", fromProject.path("body").path("phase").asText(), fromProject.toString());
    String c = newChat(ws, owner); assertEquals("setup", call(ws, owner, HttpMethod.GET, "/api/chats/" + c, null).path("body").path("phase").asText());
    call(ws, owner, HttpMethod.PUT, "/api/chats/" + c + "/context", Map.of("projectId", pid));
    assertEquals("drafting", call(ws, owner, HttpMethod.GET, "/api/chats/" + c, null).path("body").path("phase").asText());
  }

  JsonNode followup(String ws, U u, String c, String aid) { return call(ws, u, HttpMethod.POST, "/api/chats/" + c + "/actions/" + aid + "/followup", Map.of()); }

  @Test void followupStreamsNextStepOnceWithSuggestions() {
    String ws = newWs(); U owner = member(ws, "owner"); llmDown();
    String c = newChat(ws, owner); say(ws, owner, c, "新增产品线「续线」");
    String aid = actions(ws, owner, c).get(0).path("id").asText();
    assertEquals(409, st(followup(ws, owner, c, aid)), "没执行的操作不能续");
    confirm(ws, owner, c, aid);
    llm("{\"tool_calls\":[],\"actions\":[],\"guide\":null,\"reply\":\"下一步可以在续线下建项目。\",\"draft\":{},\"suggestions\":[\"在「续线」下新建项目 官网\",\"在「续线」下新建项目 官网\",\"这一条超过四十个字这一条超过四十个字这一条超过四十个字这一条超过四十个字这一条超长\"]}");
    var r = followup(ws, owner, c, aid); assertEquals(200, st(r)); String sse = r.path("_raw").asText();
    assertTrue(sse.contains("下一步可以在续线下建项目"), sse); assertTrue(sse.contains("event:suggest"), sse);
    var g = call(ws, owner, HttpMethod.GET, "/api/chats/" + c, null).path("body");
    assertEquals(1, g.path("suggestions").size(), "去重、超长丢弃：" + g.path("suggestions")); assertEquals("在「续线」下新建项目 官网", g.path("suggestions").get(0).asText());
    assertEquals(409, st(followup(ws, owner, c, aid)), "每个操作只续一次");
    say(ws, owner, c, "新增产品线「续线2」");
    assertEquals(0, call(ws, owner, HttpMethod.GET, "/api/chats/" + c, null).path("body").path("suggestions").size(), "用户发消息后清空建议");
  }

  @Test void followupFallsBackToRulesWhenModelFails() {
    String ws = newWs(); U owner = member(ws, "owner"); llmDown();
    String c = newChat(ws, owner); say(ws, owner, c, "新增产品线「兜底线」");
    String aid = actions(ws, owner, c).get(0).path("id").asText(); confirm(ws, owner, c, aid);
    var r = followup(ws, owner, c, aid); assertEquals(200, st(r)); String sse = r.path("_raw").asText();
    assertTrue(sse.contains("在「兜底线」下新建项目"), sse); assertFalse(sse.contains("event:error"), "模型失败不报错，走兜底");
    U viewer = member(ws, "viewer"); assertEquals(403, st(followup(ws, viewer, c, aid)));
  }

  String projectFor(String ws, U owner, String line, String name) {
    llm("{\"reply\":\"好\",\"actions\":[{\"kind\":\"create_project\",\"params\":{\"name\":\"" + name + "\",\"productLineName\":\"" + line + "\"}}]}");
    String c = newChat(ws, owner); say(ws, owner, c, "建项目" + name); confirm(ws, owner, c, actions(ws, owner, c).get(0).path("id").asText());
    return (String) db.rows("SELECT id FROM project WHERE workspace_id=? AND name=?", ws, name).get(0).get("id");
  }

  @Test void bindRepoByRuleUsesSessionProjectAndEntersDrafting() {
    String ws = newWs(); U owner = member(ws, "owner"); lineFor(ws, owner, "线B"); String pid = projectFor(ws, owner, "线B", "项目B");
    llmDown(); String c = newChat(ws, owner); call(ws, owner, HttpMethod.PUT, "/api/chats/" + c + "/context", Map.of("projectId", pid));
    String repo = "acme/b" + System.nanoTime();
    say(ws, owner, c, "绑定仓库 " + repo);
    var a = actions(ws, owner, c).get(0); assertEquals("bind_repo", a.path("kind").asText()); assertEquals(pid, a.path("params").path("projectId").asText()); assertEquals(repo, a.path("params").path("repo").asText());
    var r = confirm(ws, owner, c, a.path("id").asText()); assertEquals(200, st(r), r.toString()); assertTrue(r.path("body").path("executed").asBoolean());
    assertTrue(db.rows("SELECT config FROM project WHERE id=?", pid).get(0).get("config").toString().contains(repo));
    assertEquals("drafting", r.path("body").path("phase").asText());
    assertEquals(3, db.count("SELECT COUNT(*) FROM task_event WHERE event_type='CHAT_ACTION_EXECUTED' AND actor=?", owner.login), "建线、建项目、绑仓库各一条审计");
  }

  @Test void bindRepoByAgentNamedProjectValidatesAndKeepsCardEditable() {
    String ws = newWs(); U owner = member(ws, "owner"); lineFor(ws, owner, "线C"); String p1 = projectFor(ws, owner, "线C", "项目C1"); projectFor(ws, owner, "线C", "项目C2");
    String repo = "acme/c" + System.nanoTime();
    llm("{\"reply\":\"好\",\"actions\":[{\"kind\":\"bind_repo\",\"params\":{\"projectName\":\"项目C1\",\"repo\":\"https://github.com/" + repo + ".git\"}}]}");
    String c = newChat(ws, owner); say(ws, owner, c, "给项目C1绑定仓库");
    var a = actions(ws, owner, c).get(0); assertEquals(p1, a.path("params").path("projectId").asText()); assertEquals(repo, a.path("params").path("repo").asText(), "去掉前缀和 .git");
    U m = member(ws, "member"); assertEquals(403, st(confirm(ws, m, c, a.path("id").asText())), "成员不能绑定仓库");
    assertEquals(200, st(confirm(ws, owner, c, a.path("id").asText())));
    // 同一个仓库绑到另一个项目：校验失败，卡片保持可编辑
    llm("{\"reply\":\"好\",\"actions\":[{\"kind\":\"bind_repo\",\"params\":{\"projectName\":\"项目C2\",\"repo\":\"" + repo + "\"}}]}");
    String c2 = newChat(ws, owner); say(ws, owner, c2, "给项目C2绑定同一个仓库");
    var a2 = actions(ws, owner, c2).get(0); var bad = confirm(ws, owner, c2, a2.path("id").asText());
    assertEquals(400, st(bad), bad.toString()); assertEquals("proposed", bad.path("body").path("action").path("status").asText());
    var prev = actionsBean.repoAccessible; actionsBean.repoAccessible = x -> false;
    try { var e = call(ws, owner, HttpMethod.PUT, "/api/chats/" + c2 + "/actions/" + a2.path("id").asText() + "/params", Map.of("repo", "acme/other" + System.nanoTime())); assertEquals(200, st(e));
      var r = confirm(ws, owner, c2, a2.path("id").asText()); assertEquals(400, st(r)); assertEquals("repo", r.path("body").path("field").asText()); assertTrue(r.path("body").path("message").asText().contains("没有权限"));
    } finally { actionsBean.repoAccessible = prev; }
    llm("{\"reply\":\"好\",\"actions\":[{\"kind\":\"bind_repo\",\"params\":{\"projectName\":\"不存在\",\"repo\":\"a/b\"}}]}");
    String c3 = newChat(ws, owner); String sse = say(ws, owner, c3, "给不存在的项目绑仓库"); assertEquals(0, actions(ws, owner, c3).size()); assertTrue(sse.contains("提议被拒绝"), sse);
  }
}
