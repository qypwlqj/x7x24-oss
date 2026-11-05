package com.x7x24.tenant;

import com.x7x24.db.Db;
import com.x7x24.gov.AuditLog;
import com.x7x24.util.Clk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.*;

/** 工作区与成员：首次登录自动创建个人工作区；遗留工作区（ws_legacy）由 legacy-owner 首次登录认领。 */
@Component
public class WorkspaceService {
  public record Ws(String id, String slug, String name, String kind, String role, String status) {
    public Map<String, Object> toMap() { Map<String, Object> m = new LinkedHashMap<>(); m.put("id", id); m.put("slug", slug); m.put("name", name); m.put("kind", kind); m.put("role", role); m.put("status", status); return m; }
  }
  public static final List<String> ROLES = List.of("owner", "admin", "member", "viewer");
  public static class NotMember extends RuntimeException { public NotMember() { super("工作区不存在或你不是成员"); } }
  public static class Forbidden extends RuntimeException { public Forbidden(String m) { super(m); } }

  private final Db db; private final Clk clk; private final AuditLog audit; private final String legacyOwner;
  private final SecureRandom rng = new SecureRandom();
  public WorkspaceService(Db db, Clk clk, AuditLog audit, @Value("${x7x24.workspace.legacy-owner:}") String legacyOwner) { this.db = db; this.clk = clk; this.audit = audit; this.legacyOwner = legacyOwner == null ? "" : legacyOwner.trim(); }

  public List<Ws> memberships(String userId) {
    return db.rows("SELECT w.id,w.slug,w.name,w.kind,w.status,m.role FROM workspace_member m JOIN workspace w ON w.id=m.workspace_id WHERE m.user_id=? ORDER BY CASE WHEN w.kind='personal' THEN 0 ELSE 1 END, w.created_at, w.id", userId)
        .stream().map(r -> new Ws(Db.str(r.get("id")), Db.str(r.get("slug")), Db.str(r.get("name")), Db.str(r.get("kind")), Db.str(r.get("role")), Db.str(r.get("status")))).toList();
  }
  public Ws get(String id) {
    var r = db.row("SELECT id,slug,name,kind,status FROM workspace WHERE id=?", id);
    return r == null ? null : new Ws(Db.str(r.get("id")), Db.str(r.get("slug")), Db.str(r.get("name")), Db.str(r.get("kind")), null, Db.str(r.get("status")));
  }
  public Ws bySlug(String slug) { var r = db.row("SELECT id FROM workspace WHERE slug=?", slug); return r == null ? null : get(Db.str(r.get("id"))); }
  public boolean exists(String id) { return db.count("SELECT COUNT(*) FROM workspace WHERE id=?", id) > 0; }

  /** 保证用户至少有一个工作区：legacy-owner 认领遗留工作区，其他人新建个人工作区。返回成员身份列表。 */
  public synchronized List<Ws> ensure(String userId, String login) {
    var ms = memberships(userId); if (!ms.isEmpty()) return ms;
    String now = clk.iso();
    if (!legacyOwner.isBlank() && login != null && login.equalsIgnoreCase(legacyOwner) && exists(Tenant.LEGACY) && db.count("SELECT COUNT(*) FROM workspace_member WHERE workspace_id=? AND role='owner'", Tenant.LEGACY) == 0) {
      db.update("UPDATE workspace SET owner_user_id=? WHERE id=?", userId, Tenant.LEGACY);
      db.update("INSERT INTO workspace_member(workspace_id,user_id,role,created_at) VALUES(?,?,?,?)", Tenant.LEGACY, userId, "owner", now);
      Tenant.run(Tenant.PLATFORM, () -> audit.sys("WORKSPACE_CLAIMED", "auth", Map.of("workspace", Tenant.LEGACY, "login", login), "ws-claim:" + userId));
      return memberships(userId);
    }
    String id = "ws_" + Long.toHexString(rng.nextLong() & Long.MAX_VALUE);
    String slug = uniqueSlug(login == null ? "ws" : login.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-"));
    db.tx(() -> {
      db.update("INSERT INTO workspace(id,slug,name,kind,owner_user_id,plan_quota,status,created_at) VALUES(?,?,?,?,?,?,?,?)", id, slug, login == null ? slug : login, "personal", userId, "{}", "active", now);
      db.update("INSERT INTO workspace_member(workspace_id,user_id,role,created_at) VALUES(?,?,?,?)", id, userId, "owner", now);
    });
    Tenant.run(Tenant.PLATFORM, () -> audit.sys("WORKSPACE_CREATED", "auth", Map.of("workspace", id, "slug", slug, "login", String.valueOf(login)), "ws-create:" + id));
    return memberships(userId);
  }
  private String uniqueSlug(String base) {
    String s = base.isBlank() ? "ws" : base; if (s.length() > 50) s = s.substring(0, 50);
    String c = s; int i = 2; while (db.count("SELECT COUNT(*) FROM workspace WHERE slug=?", c) > 0) c = s + "-" + i++;
    return c;
  }
  /** 按请求头（slug 或 id）选择；没有请求头时取第一个（个人工作区优先）。不是成员 → NotMember。 */
  public Ws pick(List<Ws> ms, String wanted) {
    if (ms.isEmpty()) throw new NotMember();
    if (wanted == null || wanted.isBlank()) return ms.getFirst();
    return ms.stream().filter(w -> w.id().equals(wanted) || w.slug().equalsIgnoreCase(wanted)).findFirst().orElseThrow(NotMember::new);
  }
  /** 启动自检（只输出计数，便于部署后核对迁移：遗留工作区的数据量、孤儿行应为 0） */
  @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
  public void selfCheck() {
    try {
      StringBuilder b = new StringBuilder("workspace self-check:");
      for (String t : List.of("product_line", "project", "task", "signal"))
        b.append(' ').append(t).append("=").append(db.count("SELECT COUNT(*) FROM " + t)).append("/legacy=").append(db.count("SELECT COUNT(*) FROM " + t + " WHERE workspace_id=?", Tenant.LEGACY))
         .append("/orphan=").append(db.count("SELECT COUNT(*) FROM " + t + " x WHERE NOT EXISTS (SELECT 1 FROM workspace w WHERE w.id=x.workspace_id)"));
      b.append(" workspaces=").append(db.count("SELECT COUNT(*) FROM workspace")).append(" members=").append(db.count("SELECT COUNT(*) FROM workspace_member"));
      org.slf4j.LoggerFactory.getLogger(WorkspaceService.class).info(b.toString());
    } catch (Exception e) { org.slf4j.LoggerFactory.getLogger(WorkspaceService.class).warn("workspace self-check failed: {}", e.getMessage()); }
  }
  /** 平台配额（plan_quota JSON）：llmFallback（是否允许兜底到平台默认模型）、llmFallbackUsdMonth（月额度，null = 不限） */
  public Map<String, Object> quota(String wsId) {
    var r = db.row("SELECT plan_quota FROM workspace WHERE id=?", wsId); if (r == null) return new LinkedHashMap<>();
    try { return new LinkedHashMap<>(com.x7x24.util.Json.M.readValue((String) r.get("plan_quota"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})); } catch (Exception e) { return new LinkedHashMap<>(); }
  }
  private final Map<String, Boolean> fallbackCache = new java.util.concurrent.ConcurrentHashMap<>();
  public Runnable onQuotaChanged = () -> {};
  public boolean llmFallback(String wsId) { return fallbackCache.computeIfAbsent(wsId, w -> Boolean.TRUE.equals(quota(w).get("llmFallback"))); }
  public void putQuota(String wsId, Map<String, Object> q) { db.update("UPDATE workspace SET plan_quota=? WHERE id=?", com.x7x24.util.Json.str(q), wsId); fallbackCache.remove(wsId); onQuotaChanged.run(); }
  public static boolean manager(String role) { return "owner".equals(role) || "admin".equals(role); }
  public static int rank(String role) { int i = ROLES.indexOf(role); return i < 0 ? 99 : i; }
}
