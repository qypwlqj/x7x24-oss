package com.x7x24.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.x7x24.adapters.GitHubClient.*;
import com.x7x24.db.Db;
import com.x7x24.engine.*;
import com.x7x24.gov.*;
import com.x7x24.integration.*;
import com.x7x24.util.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.*;

import static com.x7x24.adapters.GitHubClient.enc;

/**
 * GitHub 真实适配器：Issues / Git Data API / Pulls / Actions / Releases。
 * 每个写操作都经过：kill switch → 禁区策略 → 预算无关 → dry-run → 幂等（tool_ops）→ 调用日志（CallLog）。
 * 绝不 bypass 保护规则、不直推受保护分支、不强推、不删分支/tag。
 */
@Component
public class GitHubTools implements DeliveryTools {
  private final X7Props props; private final CallLog calls; private final ConfigStore cfg; private final AuditLog audit; private final KillSwitch kill; private final Clk clk; private final Db db; private final ProjectRepo projects; private final CredentialProvider creds; private final RuntimeSettings settings;
  private final ThreadLocal<Set<String>> activeOperations = ThreadLocal.withInitial(HashSet::new);
  private final Map<String, Object> repoLocks = new ConcurrentHashMap<>();
  private final Map<String, GitHubClient> clients = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Object>> connCache = new ConcurrentHashMap<>();

  public GitHubTools(X7Props props, CallLog calls, ConfigStore cfg, AuditLog audit, KillSwitch kill, Clk clk, Db db, ProjectRepo projects, CredentialProvider creds, RuntimeSettings settings) {
    this.creds = creds; this.settings = settings; this.props = props; this.calls = calls; this.cfg = cfg; this.audit = audit; this.kill = kill; this.clk = clk; this.db = db; this.projects = projects;
  }

  private boolean realMode() { return "real".equalsIgnoreCase(settings.adapterMode()); }
  private boolean hasToken() { return creds.hasToken(); }
  @Override public boolean live() { return realMode() && hasToken(); }
  private boolean dry() { return settings.dryRun(); }

  GitHubClient client(ProjectCfg l) {
    return clients.computeIfAbsent(com.x7x24.tenant.Tenant.writeId() + ":" + l.id + ":" + l.repo, k -> new GitHubClient(props.github.apiUrl, () -> creds.tokenForRepo(l.repo), calls, h -> {
      String host = h.replaceFirst(":\\d+$", "");
      var d = Risk.evaluateAction(cfg.get(), "net.egress", host);
      if (!d.allowed() && !props.github.apiUrl.contains("localhost") && !props.github.apiUrl.contains("127.0.0.1")) throw new Risk.PolicyDenied(d, "net.egress");
    }, props.adapter.maxRetries, props.adapter.maxWaitMs, null, l.id).authorization(status -> creds.observeRepository(l.repo, status), () -> creds.refreshRejected(l.repo)));
  }
  private String p(ProjectCfg l, String path) { return "/repos/" + enc(l.owner()) + "/" + enc(l.repoName()) + path; }
  private static String taskOf(String key) { int i = key == null ? -1 : key.indexOf(':'); return i > 0 ? key.substring(0, i) : null; }

  // ───── 闸门 ─────
  private void write(ProjectCfg l, String key, String op, String action, String target, String kind) {
    kill.assertWritable(l.productLineId == null ? l.id : l.productLineId, l.id, kind);
    var project = projects.get(l.id);
    if (project != null && projects.runtime(l.id).paused() != 0) throw new GateBlocked("project_paused", "项目或产品线已暂停/归档");
    var d = Risk.evaluateAction(cfg.get(), action, target);
    if (!d.allowed()) {
      audit.task(taskOf(key), null, "ACTION_DENIED", "dev", Map.of("action", action, "target", String.valueOf(target), "category", String.valueOf(d.category()), "reason", d.reason()), null);
      throw new Risk.PolicyDenied(d, action);
    }
    String task = taskOf(key);
    if (task != null) {
      String prefix = task + ":";
      var unresolved = db.rows("SELECT op_key FROM tool_ops WHERE LEFT(op_key,?)=? AND state IN ('PLANNED','DISPATCHING','OUTCOME_UNKNOWN')", prefix.length(),prefix);
      for (var pending : unresolved) { String other=Db.str(pending.get("op_key"));
        if (!key.equals(other) && !activeOperations.get().contains(other)) throw new GateBlocked("blocked_reconciliation","同一任务仍有待核对的写操作："+other+"；新执行不能绕过它重复写入");
      }
    }
    if (!live()) throw new DryRunPlanned(op + " " + l.repo);
    if (dry()) {
      calls.add("git", op, "-", target, null, true, true, 0, key, l.id, "dry-run: 未发送");
      audit.task(taskOf(key), null, "ADAPTER_CALL", "adapter", Map.of("op", op, "dryRun", true, "target", String.valueOf(target)), null);
      throw new DryRunPlanned(op + " " + l.repo + " " + target);
    }
  }
  private JsonNode once(String key, Supplier<JsonNode> fn) { return once(key, U.sha256(key), fn, null); }
  private JsonNode once(String key, Object request, Supplier<JsonNode> fn) { return once(key, U.sha256(Json.str(request)), fn, null); }
  private JsonNode once(String key, String digest, Supplier<JsonNode> fn, Supplier<JsonNode> reconcile) {
    var ex = db.row("SELECT result,state,request_digest FROM tool_ops WHERE op_key=?", key);
    if (ex != null) {
      if (ex.get("request_digest") != null && !digest.equals(ex.get("request_digest"))) throw new GateBlocked("operation_conflict", "同一操作键的请求发生变化");
      if ("SUCCEEDED".equals(ex.get("state"))) return Json.tree((String) ex.get("result"));
      // Even a missing remote object is not proof of absence (visibility/eventual consistency).
      if ("FAILED_KNOWN".equals(ex.get("state"))) {
        db.update("UPDATE tool_ops SET state='PLANNED',updated_at=? WHERE op_key=? AND state='FAILED_KNOWN'",clk.iso(),key);
      } else {
      JsonNode found = reconcile == null ? null : reconcile.get();
      if (found == null) throw new GateBlocked("blocked_reconciliation", "操作 " + key + " 的结果未知；禁止重复发送");
      db.update("UPDATE tool_ops SET result=?,state='SUCCEEDED',updated_at=? WHERE op_key=? AND state<>'SUCCEEDED'", found.toString(), clk.iso(), key);
      return found;
      }
    }
    if(ex==null) try { db.update("INSERT INTO tool_ops(op_key,result,state,request_digest,created_at,updated_at) VALUES (?,'null','PLANNED',?,?,?)", key, digest, clk.iso(), clk.iso()); }
    catch (org.springframework.dao.DuplicateKeyException race) { throw new GateBlocked("blocked_reconciliation", "操作已被其他执行者领取：" + key); }
    if (db.update("UPDATE tool_ops SET state='DISPATCHING',updated_at=? WHERE op_key=? AND state='PLANNED'", clk.iso(), key) != 1) throw new GateBlocked("blocked_reconciliation", key);
    activeOperations.get().add(key);
    try {
      JsonNode r = fn.get();
      db.update("UPDATE tool_ops SET result=?,state='SUCCEEDED',updated_at=? WHERE op_key=? AND state='DISPATCHING'", r == null ? "null" : r.toString(), clk.iso(), key);
      return r;
    } catch (RuntimeException failure) {
      db.update("UPDATE tool_ops SET state=?,updated_at=? WHERE op_key=? AND state='DISPATCHING'",failure instanceof GateBlocked ? "FAILED_KNOWN" : "OUTCOME_UNKNOWN", clk.iso(), key);
      throw failure;
    } finally { activeOperations.get().remove(key); }
  }
  private static String marker(String key) { return "<!-- x7-operation:" + U.sha256(key) + " -->"; }
  private JsonNode findMarked(ProjectCfg l, String path, String key) {
    JsonNode found = null;
    for (var n : client(l).array(p(l, path), "operations.reconcile", "git")) if (n.path("body").asText("").contains(marker(key))) {
      if (found != null) throw new GateBlocked("blocked_reconciliation", "多个远端对象匹配同一操作键"); found = n;
    }
    return found;
  }

  // ───── Issues ─────
  @Override public List<IssueInfo> listSignalIssues(ProjectCfg l) {
    if (!live() || !l.intake) return List.of();
    String path = p(l, "/issues") + "?state=open&sort=created&direction=asc" + (l.intakeAll ? "" : "&labels=" + enc(l.intakeLabel));
    var data = client(l).array(path, "issues.list", "git");
    List<IssueInfo> out = new ArrayList<>();
    for (var n : data) {
      if (n.has("pull_request")) continue;
      List<String> labels = new ArrayList<>(); for (var x : n.path("labels")) labels.add(x.path("name").asText());
      out.add(new IssueInfo(n.path("number").asInt(), n.path("title").asText(), n.path("body").asText(""), n.path("html_url").asText(), n.path("user").path("login").asText(), labels, n.path("created_at").asText()));
    }
    return out;
  }
  @Override public IssueInfo createIssue(ProjectCfg l, String key, String title, String body, List<String> labels) {
    write(l, key, "issues.create", "issue.create", null, "write");
    JsonNode r = once(key, U.sha256(Json.str(List.of(l.repo, title, body, labels))), () -> client(l).send("POST", p(l, "/issues"), Map.of("title", title, "body", body + "\n" + marker(key), "labels", labels), "issues.create", "git", key).data(), () -> findMarked(l, "/issues?state=all", key));
    return new IssueInfo(r.path("number").asInt(), title, body, r.path("html_url").asText(), r.path("user").path("login").asText(), labels, r.path("created_at").asText());
  }
  @Override public void comment(ProjectCfg l, String key, int issue, String body) {
    write(l, key, "issues.comment", "issue.comment", "#" + issue, "write");
    once(key, U.sha256(Json.str(List.of(l.repo, issue, body))), () -> client(l).send("POST", p(l, "/issues/" + issue + "/comments"), Map.of("body", body + "\n" + marker(key)), "issues.comment", "git", key).data(), () -> findMarked(l, "/issues/" + issue + "/comments", key));
  }
  @Override public void labels(ProjectCfg l, String key, int issue, List<String> add, List<String> remove) {
    write(l, key, "issues.labels", "issue.label", "#" + issue, "write");
    once(key, java.util.Arrays.asList(l.repo, issue, add, remove), () -> {
      var c = client(l);
      for (String r : remove) c.request("DELETE", p(l, "/issues/" + issue + "/labels/" + enc(r)), null, "issues.unlabel", "git", key, true, false);
      if (!add.isEmpty()) c.send("POST", p(l, "/issues/" + issue + "/labels"), Map.of("labels", add), "issues.label", "git", key);
      return BooleanNode.TRUE;
    });
  }
  @Override public void closeIssue(ProjectCfg l, String key, int issue, boolean completed) {
    write(l, key, "issues.close", "issue.close", "#" + issue, "write");
    once(key, java.util.Arrays.asList(l.repo, issue, completed), () -> client(l).send("PATCH", p(l, "/issues/" + issue), Map.of("state", "closed", "state_reason", completed ? "completed" : "not_planned"), "issues.close", "git", key).data());
  }

  // ───── Git ─────
  @Override public void reopenIssue(ProjectCfg l,String key,int issue) {
    write(l,key,"issues.reopen","issue.close","#"+issue,"write");
    once(key,java.util.Arrays.asList(l.repo,issue,"open"),()->client(l).send("PATCH",p(l,"/issues/"+issue),Map.of("state","open"),"issues.reopen","git",key).data());
  }
  @Override public String readFile(ProjectCfg l, String ref, String path) {
    if (!live()) return null;
    var r = client(l).get404(p(l, "/contents/" + encPath(path)) + "?ref=" + enc(ref), "contents.read", "git");
    if (r.status() == 404 || r.data() == null || !r.data().has("content")) return null;
    return new String(Base64.getMimeDecoder().decode(r.data().get("content").asText()), java.nio.charset.StandardCharsets.UTF_8);
  }
  @Override public List<String> listDir(ProjectCfg l, String ref, String dir) {
    if (!live()) return List.of();
    var r = client(l).get404(p(l, "/contents/" + encPath(dir)) + "?ref=" + enc(ref), "contents.list", "git");
    List<String> out = new ArrayList<>(); if (r.status() == 200 && r.data() != null && r.data().isArray()) for (var n : r.data()) out.add(n.path("path").asText());
    return out;
  }
  private static String encPath(String path) { return String.join("/", Arrays.stream(path.split("/")).map(GitHubClient::enc).toList()); }

  private String refSha(ProjectCfg l, String branch) {
    try {
      var r = client(l).get404(p(l, "/git/ref/heads/" + encPath(branch)), "git.get_ref", "git");
      return r.status() == 404 || r.data() == null ? null : r.data().path("object").path("sha").asText();
    } catch (GitHubException e) {
      // 仅 GitHub 明确声明的空仓库允许从零规划；权限、缺失分支及其他 409 仍向上传递。
      if (e.status == 409 && e.getMessage().endsWith(": Git Repository is empty."))
        throw new GateBlocked("repository_not_initialized", "仓库 " + l.repo + " 尚未初始化，可选择「初始化仓库并继续」，由平台在目标分支 " + l.baseBranch + " 创建首个提交（README）；已生成的规划会保留");
      throw e;
    }
  }
  private String initializedHead(ProjectCfg l) {
    try { return repositoryHead(l); }
    catch (GateBlocked e) { if (!"repository_not_initialized".equals(e.category)) throw e; return null; }
  }
  @Override public String initializeRepository(ProjectCfg l, String key) {
    // dry-run 连读取也不发出，与其他写工具的语义一致。
    if (!live() || dry()) write(l, key, "repository.initialize", "git.initialize_repository", l.baseBranch, "write");
    var spec = DeliveryTools.repositoryInit(l);
    String operation = "repository-init-" + U.sha256(Json.str(List.of(com.x7x24.tenant.Tenant.writeId(), l.repo.toLowerCase(Locale.ROOT), l.baseBranch)));
    String message = spec.message() + "\n\n" + marker(operation);
    String digest = U.sha256(Json.str(spec));
    synchronized (repoLocks.computeIfAbsent(operation, ignored -> new Object())) {
      var prior = db.row("SELECT state FROM tool_ops WHERE op_key=?", operation);
      if (prior == null || "SUCCEEDED".equals(prior.get("state"))) {
        String head = initializedHead(l); if (head != null) return head;
        if (prior != null) throw new GateBlocked("repository_not_initialized", "原初始化记录已成功，但远端仓库重新变为空，请管理员核对仓库历史");
      }
      var violations = PathPolicy.check(projects.constrained(l), List.of(spec.path()), Map.of(spec.path(), spec.content()));
      if (!violations.isEmpty()) throw new GateBlocked("repository_not_initialized", "初始化文件被项目路径策略拒绝：" + violations);
      if (!Risk.classify(cfg.get(), "", List.of(spec.path())).governanceFileHits().isEmpty())
        throw new GateBlocked("repository_not_initialized", "初始化文件被治理文件策略保护，请管理员核对项目设置");
      write(l, key, "repository.initialize", "git.initialize_repository", l.baseBranch, "write");
      once(operation, digest, () -> {
        // 登记意图后再读一次；其他任务 / 人工已初始化则采用其最新基线，绝不更新已有文件。
        String head; JsonNode remote;
        try {
          head = initializedHead(l); if (head != null) return TextNode.valueOf(head);
          remote = client(l).get(p(l, ""), "repo.get", "git").data();
        } catch (GitHubException e) {
          // 此处尚未发出写请求，读失败可以在权限 / 网络修复后安全重试。
          throw new GateBlocked("repository_not_initialized", "初始化前无法核验仓库：" + Humanize.reason(e.status, e.getMessage()));
        }
        if (!l.baseBranch.equals(remote.path("default_branch").asText()))
          throw new GateBlocked("repository_not_initialized", "目标分支 " + l.baseBranch + " 与 GitHub 默认分支 " + remote.path("default_branch").asText() + " 不一致，请先对齐分支配置后重试");
        JsonNode result;
        try {
          result = client(l).send("PUT", p(l, "/contents/" + spec.path()), Map.of("message", message, "content", Base64.getEncoder().encodeToString(spec.content().getBytes(java.nio.charset.StandardCharsets.UTF_8)), "branch", l.baseBranch), "repository.initialize", "git", operation).data();
        } catch (GitHubException e) {
          if (!(e instanceof OutcomeUnknown) && e.status >= 400 && e.status < 500)
            throw new GateBlocked("repository_not_initialized", "初始化失败：" + Humanize.reason(e.status, e.getMessage()) + "；请检查 Contents 写权限及仓库规则后重试");
          throw e;
        }
        var commit = result == null ? Json.M.createObjectNode() : result.path("commit");
        String sha = commit.path("sha").asText();
        // Contents API 无空仓库条件写入参数：发现人工并发抢先提交时停止并核对，不能自动补写或回滚。
        if (!isSha(sha) || !commit.path("parents").isArray() || !commit.path("parents").isEmpty())
          throw new OutcomeUnknown(409, "初始化响应无法证明首个提交，请核对远端提交记录", p(l, "/contents/" + spec.path()));
        if (!sha.equals(repositoryHead(l))) throw new OutcomeUnknown(409, "初始化后分支已变化，请核对远端提交记录", p(l, "/git/ref/heads/" + encPath(l.baseBranch)));
        return TextNode.valueOf(sha);
      }, () -> reconcileInitialization(l, spec, message));
      return repositoryHead(l);
    }
  }
  private JsonNode reconcileInitialization(ProjectCfg l, RepositoryInit spec, String message) {
    if (initializedHead(l) == null) return null; // 暂未看到提交不是失败证据，不能重发。
    for (var commit : client(l).array(p(l, "/commits") + "?sha=" + enc(l.baseBranch) + "&path=" + enc(spec.path()), "repository.initialize.reconcile", "git")) {
      if (message.equals(commit.path("commit").path("message").asText()) && commit.path("parents").isArray() && commit.path("parents").isEmpty()) {
        String sha = commit.path("sha").asText();
        if (isSha(sha) && spec.content().equals(readFile(l, sha, spec.path()))) return TextNode.valueOf(sha);
      }
    }
    return null;
  }
  @Override public String ensureBranch(ProjectCfg l, String key, String branch) {
    write(l, key, "git.create_branch", "git.push", branch, "write");
    String base = resolveCommitSha(l, l.baseBranch); // 保留 dry-run 闸门，并在登记操作前校验，避免误记为写入结果未知。
    once(key, java.util.Arrays.asList(l.repo, l.baseBranch, branch), () -> {
      String existing = refSha(l, branch);
      if (existing != null) return TextNode.valueOf(existing);
      try { client(l).send("POST", p(l, "/git/refs"), Map.of("ref", "refs/heads/" + branch, "sha", base), "git.create_ref", "git", key); }
      catch (GitHubException e) { if (e.status != 422 || !e.getMessage().toLowerCase().contains("already exists")) throw e; }
      return TextNode.valueOf(base);
    });
    String current = refSha(l, branch);
    if (current == null) throw new GateBlocked("head_changed", "任务分支已不存在，请核对远端状态");
    return current;
  }
  @Override public String commit(ProjectCfg l, String key, String branch, String message, List<FileChange> files) {
    return commit(l, key, branch, message, files, null);
  }
  @Override public String commit(ProjectCfg l, String key, String branch, String message, List<FileChange> files, String expectedHead) {
    var policy = projects.constrained(l);
    var violations = PathPolicy.check(policy, files.stream().map(FileChange::path).toList(), files.stream().filter(f -> f.content() != null).collect(java.util.stream.Collectors.toMap(FileChange::path, FileChange::content, (a, b) -> a)));
    if (!violations.isEmpty()) { audit.task(taskOf(key), null, "ACTION_DENIED", "dev", Map.of("action", "git.commit", "category", "repo", "reason", "路径策略拒绝：" + violations), null); throw new Risk.PolicyDenied(new Risk.Decision(false, "repo", "路径策略拒绝：" + violations), "git.commit"); }
    var gov = Risk.classify(cfg.get(), "", files.stream().map(FileChange::path).toList());
    if (!gov.governanceFileHits().isEmpty()) {
      audit.task(taskOf(key), null, "ACTION_DENIED", "dev", Map.of("action", "git.commit", "category", "repo", "reason", "改动治理文件：" + gov.governanceFileHits()), null);
      throw new Risk.PolicyDenied(new Risk.Decision(false, "repo", "禁止提交治理文件：" + gov.governanceFileHits()), "git.commit");
    }
    write(l, key, "git.commit", "git.push", branch, "write");
    Object request = expectedHead == null ? java.util.Arrays.asList(l.repo, branch, message, files) : java.util.Arrays.asList(l.repo, branch, message, files, expectedHead);
    return once(key, request, () -> {
      var c = client(l);
      String head = refSha(l, branch); if (head == null) throw new GitHubException(404, "branch " + branch + " not found", "git/ref");
      if (expectedHead != null && !expectedHead.equals(head)) throw new GateBlocked("head_changed", "PR 分支已变化，保留现有提交并停止应用旧补丁");
      JsonNode headCommit = c.get(p(l, "/git/commits/" + head), "git.get_commit", "git").data(); String baseTree = headCommit.path("tree").path("sha").asText();
      ArrayNode tree = Json.M.createArrayNode();
      for (var f : files) {
        ObjectNode e = tree.addObject(); e.put("path", f.path()); e.put("mode", "100644"); e.put("type", "blob");
        if (f.delete()) e.putNull("sha"); else e.put("content", f.content());
      }
      var t = c.send("POST", p(l, "/git/trees"), Map.of("base_tree", baseTree, "tree", tree), "git.create_tree", "git", key).data();
      if (t.path("sha").asText().equals(baseTree)) return TextNode.valueOf(head); // no-op
      var cm = c.send("POST", p(l, "/git/commits"), Map.of("message", message, "tree", t.path("sha").asText(), "parents", List.of(head)), "git.create_commit", "git", key).data();
      String sha = cm.path("sha").asText();
      c.send("PATCH", p(l, "/git/refs/heads/" + encPath(branch)), Map.of("sha", sha, "force", false), "git.update_ref", "git", key);
      return TextNode.valueOf(sha);
    }).asText();
  }

  private PrInfo prFrom(JsonNode n, boolean existed) { return new PrInfo(n.path("number").asInt(), n.path("html_url").asText(), n.path("head").path("ref").asText(), n.path("head").path("sha").asText(), existed); }
  @Override public PrInfo openPr(ProjectCfg l, String key, String branch, String title, String body) {
    write(l, key, "pulls.open", "git.open_pr", branch, "write");
    JsonNode r = once(key, java.util.Arrays.asList(l.repo, l.baseBranch, branch, title, body), () -> {
      var c = client(l);
      var ex = c.get(p(l, "/pulls") + "?state=open&head=" + enc(l.owner() + ":" + branch), "pulls.find", "git").data();
      if (ex != null && ex.isArray() && ex.size() > 0) { ObjectNode n = ex.get(0).deepCopy(); n.put("_existed", true); return n; }
      try { return c.send("POST", p(l, "/pulls"), Map.of("title", title, "head", branch, "base", l.baseBranch, "body", body, "maintainer_can_modify", true), "pulls.create", "git", key).data(); }
      catch (GitHubException e) {
        if (e.status != 422 || !e.getMessage().toLowerCase().contains("already exists")) throw e;
        var again = c.get(p(l, "/pulls") + "?state=open&head=" + enc(l.owner() + ":" + branch), "pulls.find", "git").data();
        ObjectNode n = again.get(0).deepCopy(); n.put("_existed", true); return n;
      }
    });
    return prFrom(r, r.path("_existed").asBoolean(false));
  }
  @Override public List<PrFile> prFiles(ProjectCfg l, int pr) {
    var r = client(l).array(p(l, "/pulls/" + pr + "/files"), "pulls.files", "git"); List<PrFile> out = new ArrayList<>();
    if(r.size()>=3000)throw new GateBlocked("incomplete_file_manifest","PR 文件过多，无法证明清单完整，请缩小变更或提供完整清单");
    for (var n : r) out.add(new PrFile(n.path("filename").asText(), n.path("additions").asInt(), n.path("deletions").asInt(), n.path("status").asText()));
    return out;
  }
  @Override public String prHead(ProjectCfg l, int pr) { var n = client(l).get(p(l, "/pulls/" + pr), "pulls.get", "git").data(); return n == null ? null : n.path("head").path("sha").asText(null); }
  @Override public List<FilePatch> prPatches(ProjectCfg l, int pr) {
    var r = client(l).array(p(l, "/pulls/" + pr + "/files"), "pulls.files", "git"); List<FilePatch> out = new ArrayList<>();
    if(r.size()>=3000)throw new GateBlocked("incomplete_file_manifest","PR 文件过多，无法证明清单完整，请缩小变更或提供完整清单");
    if (r != null) for (var n : r) out.add(new FilePatch(n.path("filename").asText(), n.path("status").asText(), n.path("additions").asInt(), n.path("deletions").asInt(), n.hasNonNull("patch") ? n.path("patch").asText() : null));
    return out;
  }

  // ───── CI（GitHub Actions）：非阻塞轮询 ─────
  private RuntimeException ciReadFailure(GitHubException error) {
    if (!(error instanceof RateLimited) && Set.of(401, 403, 404).contains(error.status))
      return new GateBlocked("ci_permission", "无法访问 GitHub Actions：请在「集成」页核对仓库授权及 Actions 读写权限。" + error.getMessage());
    return error;
  }

  @Override public void assertCiReady(ProjectCfg project) {
    String path = p(project, "/actions/workflows/" + enc(project.workflow));
    try {
      var workflow = client(project).get404(path, "ci.workflow", "ci");
      if (workflow.status() == 404) {
        // 列表同样不可读时不能把权限不足误报成文件不存在。
        var available = client(project).objectArray(p(project, "/actions/workflows"), "workflows", "ci.workflows", "ci");
        List<String> names = new ArrayList<>(); for (var item : available) names.add(item.path("path").asText());
        throw new GateBlocked("ci_broken", "仓库 " + project.repo + " 缺少或尚未注册 .github/workflows/" + project.workflow
            + (names.isEmpty() ? "，仓库没有可用工作流" : "；已有工作流：" + String.join("、", names))
            + "。请在「集成」页核对文件名或用 PR 补充 CI 模板，合并初始化 PR 后恢复执行。");
      }
      if (workflow.data() == null || !"active".equals(workflow.data().path("state").asText()))
        throw new GateBlocked("ci_broken", "CI 工作流 " + project.workflow + " 已禁用或未就绪，请在 GitHub Actions 启用后恢复执行。");
    } catch (GitHubException error) { throw ciReadFailure(error); }
  }

  @Override public void assertDevelopmentReady(ProjectCfg project, String ref) {
    var modules = project.modules == null || project.modules.isEmpty() ? List.of(new ProjectCfg.Module()) : project.modules;
    for (var module : modules) {
      String dir = module.path == null ? "" : module.path.replaceFirst("^\\./", "").replaceAll("/+$", "");
      if (dir.equals(".")) dir = "";
      String prefix = dir.isEmpty() ? "" : dir + "/";
      var files = listDir(project, ref, dir);
      String language = module.language == null ? "" : module.language.toLowerCase(Locale.ROOT);
      boolean node = files.contains(prefix + "package.json") || language.matches(".*(node|javascript|typescript|react|next|vue).*");
      if (node) {
        String text = readFile(project, ref, prefix + "package.json");
        if (text == null) throw projectNotReady(prefix + "package.json");
        JsonNode manifest; try { manifest = Json.tree(text); if (manifest == null || !manifest.isObject()) throw new IllegalArgumentException(); }
        catch (RuntimeException invalid) { throw projectNotReady(prefix + "package.json（JSON 格式无效）"); }
        String command = module.testCommand == null ? "" : module.testCommand.strip();
        if (command.isEmpty() || command.matches(".*npm\\s+(run\\s+)?test.*")) {
          if (manifest.path("scripts").path("test").asText("").isBlank()) throw projectNotReady(prefix + "package.json 的 scripts.test");
        }
        if (command.contains("--if-present")) throw projectNotReady(prefix + "可强制执行的测试命令（请移除 --if-present）");
      } else {
        List<String> manifests = language.contains("go") ? List.of("go.mod")
            : language.matches(".*(java|kotlin).*") ? List.of("pom.xml", "build.gradle", "build.gradle.kts")
            : language.contains("python") ? List.of("pyproject.toml", "requirements.txt", "setup.py")
            : List.of("go.mod", "pom.xml", "build.gradle", "build.gradle.kts", "pyproject.toml", "requirements.txt", "setup.py", "Cargo.toml");
        if (manifests.stream().noneMatch(name -> files.contains(prefix + name))) throw projectNotReady(prefix + "工程依赖清单与测试入口");
      }
    }
  }

  private GateBlocked projectNotReady(String missing) {
    return new GateBlocked("project_not_ready", "项目工程尚未就绪：缺少 " + missing + "。请通过独立的工程初始化 PR 补齐依赖清单和测试命令后恢复执行；日常编码助手没有修改这些文件的权限。");
  }

  private void assertDispatchReady(ProjectCfg project, String branch) {
    String defaultBranch;
    try { defaultBranch = client(project).get(p(project, ""), "repo.get", "ci").data().path("default_branch").asText(project.baseBranch); }
    catch (GitHubException error) { throw ciReadFailure(error); }
    for (String ref : new LinkedHashSet<>(List.of(defaultBranch, project.baseBranch, branch))) {
      String workflow = readFile(project, ref, ".github/workflows/" + project.workflow);
      if (workflow == null || !Pattern.compile("(?m)\\bworkflow_dispatch\\s*:").matcher(workflow).find())
        throw new GateBlocked("ci_broken", "分支 " + ref + " 缺少支持 workflow_dispatch 的 " + project.workflow + "。请先合并 CI 初始化 PR，并更新业务 PR 分支后恢复测试。");
    }
  }

  @Override public CiState ci(ProjectCfg l, String key, String branch, String headSha, TaskCtx.Ci prev, boolean forceDispatch, String taskId) {
    assertCiReady(l);
    var c = client(l); boolean dispatched = prev != null && prev.dispatched;
    Set<Long> stale = new HashSet<>(prev == null ? List.<Long>of() : prev.staleRunIds);
    List<JsonNode> runs = runsFor(l, headSha, stale);
    long started = prev != null && prev.startedAt != null ? Clk.parse(prev.startedAt) : clk.now();
    boolean needDispatch = !dispatched && (forceDispatch || (runs.isEmpty() && clk.now() - started > props.adapter.ciStartTimeoutSec * 1000L));
    if (needDispatch) {
      assertDispatchReady(l, branch);
      write(l, key + ":dispatch", "ci.dispatch", "ci.run", branch, "write");
      once(key + ":dispatch", () -> {
      try { c.send("POST", p(l, "/actions/workflows/" + enc(l.workflow) + "/dispatches"), Map.of("ref", branch, "inputs", Map.of("suite", "agent", "task_id", taskId == null ? "" : taskId)), "ci.dispatch", "ci", key); }
      catch (GitHubException e) {
        Matcher m = Pattern.compile("Unexpected inputs provided:\\s*\\[([^\\]]*)\\]").matcher(e.getMessage());
        if (e.status != 422 || !m.find()) throw ciReadFailure(e);
        try { c.send("POST", p(l, "/actions/workflows/" + enc(l.workflow) + "/dispatches"), Map.of("ref", branch), "ci.dispatch", "ci", key); }
        catch (GitHubException fallback) { throw ciReadFailure(fallback); }
      }
      return BooleanNode.TRUE; });
      dispatched = true;
    }
    List<Long> ids = runs.stream().map(r -> r.path("id").asLong()).toList();
    if (runs.isEmpty()) return new CiState("pending", null, null, 0, 0, 0, 0, "等待 CI run 出现", dispatched, ids, null);
    JsonNode latest = runs.get(0);
    boolean allDone = runs.stream().allMatch(r -> "completed".equals(r.path("status").asText()));
    if (!allDone) return new CiState("pending", latest.path("id").asLong(), latest.path("html_url").asText(), 0, 0, 0, 0, "run " + latest.path("status").asText(), dispatched, ids, null);
    boolean passed = runs.stream().allMatch(r -> "success".equals(r.path("conclusion").asText()));
    double minutes = 0; int total = 0, failed = 0; double coverage = 0; StringBuilder log = new StringBuilder(); boolean coverageRead = false, incomplete = false, logComplete = true;
    for (var r : runs) {
      var jobs = c.objectArray(p(l, "/actions/runs/" + r.path("id").asLong() + "/jobs"), "jobs", "ci.jobs", "ci");
      if (jobs.isEmpty()) incomplete = true;
      for (var j : jobs) {
        total++; String concl = j.path("conclusion").asText("");
        if (j.hasNonNull("started_at") && j.hasNonNull("completed_at")) minutes += Math.max(1, Math.ceil((Clk.parse(norm(j.path("completed_at").asText())) - Clk.parse(norm(j.path("started_at").asText()))) / 60000.0));
        boolean badStep = false;
        for (var step : j.path("steps")) if ("failure".equals(step.path("conclusion").asText())) badStep = true;
        boolean bad = badStep || !concl.isEmpty() && !List.of("success", "skipped", "neutral").contains(concl);
        boolean notRun = !bad && !"success".equals(concl);
        if (notRun) incomplete = true;
        if (bad) failed++;
        if (bad || notRun || !coverageRead) {
          String full = "";
          try { full = com.x7x24.engine.CiFailure.cleanLog(Redactor.redact(c.text(p(l, "/actions/jobs/" + j.path("id").asLong() + "/logs"), "ci.job_log", "ci").text())); }
          catch (RuntimeException unreadable) { if (bad) logComplete = false; full = "日志无法读取（可能过期或权限不足），请补充完整失败报告"; }
          if (bad && full.isBlank()) { logComplete = false; full = "失败日志为空，不能确定失败用例"; }
          Matcher m = Pattern.compile("all files\\s*\\|\\s*(\\d{1,3}(?:\\.\\d+)?)").matcher(full);
          if (m.find() && !coverageRead) { coverage = Double.parseDouble(m.group(1)) / 100; coverageRead = true; }
          if (bad || notRun) log.append("[").append(j.path("name").asText()).append("] ").append(bad ? failedSteps(j) : "检查状态：" + (concl.isBlank() ? "未完成" : concl)).append("\n").append(full).append("\n");
        }
      }
    }
    if (failed > 0 || incomplete) passed = false;
    String head = "run #" + latest.path("id").asLong() + " " + latest.path("conclusion").asText() + "：" + (total - failed) + "/" + total + " 个 job 成功（共 " + runs.size() + " 个 run）";
    String conclusion = passed ? "success" : runs.stream().map(r -> r.path("conclusion").asText()).filter(s -> !s.equals("success")).findFirst().orElse("failure");
    String out = Redactor.redact(head + (log.length() > 0 ? "\n" + log : ""));
    return new CiState(total == 0 || incomplete && failed == 0 ? "unknown" : passed ? "passed" : "failed", latest.path("id").asLong(), latest.path("html_url").asText(), minutes, total, failed, coverage, out, dispatched, ids, conclusion, logComplete);
  }
  @Override public String updateBranch(ProjectCfg l, String key, int pr, String branch, String oldHead) {
    write(l, key, "pulls.update_branch", "git.push", branch, "write");
    return once(key, U.sha256(Json.str(java.util.Arrays.asList(l.repo,pr,branch,oldHead))), () -> {
      var c = client(l);
      try { c.send("PUT", p(l, "/pulls/" + pr + "/update-branch"), oldHead == null ? Map.of() : Map.of("expected_head_sha", oldHead), "pulls.update_branch", "git", key); }
      catch (GitHubException e) {
        if (e.status != 422) throw e;
        if (e.getMessage().toLowerCase(Locale.ROOT).contains("already up to date")) return TextNode.valueOf(refSha(l, branch));
        throw new GateBlocked("ci_broken", "无法把最新主干更新到业务 PR，请处理分支冲突后恢复执行。" + e.getMessage());
      }
      String head=refSha(l,branch);
      if(head==null || head.equals(oldHead)) throw new GitHubClient.OutcomeUnknown(0,"分支更新尚未确认，请先核对远端结果",branch);
      return TextNode.valueOf(head);
    }, () -> { String head = refSha(l, branch); return head != null && !head.equals(oldHead) ? TextNode.valueOf(head) : null; }).asText();
  }
  private final Map<String, Object[]> baseCiCache = new java.util.concurrent.ConcurrentHashMap<>();
  @Override public BaseCi baseCi(ProjectCfg l) {
    String k = l.id + "@" + l.baseBranch + "/" + l.workflow; var hit = baseCiCache.get(k);
    if (hit != null && clk.now() - (Long) hit[0] < 10 * 60_000L) return (BaseCi) hit[1];
    try {
      var c = client(l);
      var r = c.get(p(l, "/actions/workflows/" + enc(l.workflow) + "/runs") + "?branch=" + enc(l.baseBranch) + "&status=completed&per_page=1", "ci.list_runs", "ci").data();
      var runs = r == null ? null : r.path("workflow_runs"); if (runs == null || runs.isEmpty()) return null;
      var run = runs.get(0); boolean ok = "success".equals(run.path("conclusion").asText());
      StringBuilder log = new StringBuilder();
      if (!ok) for (var j : c.get(p(l, "/actions/runs/" + run.path("id").asLong() + "/jobs") + "?per_page=100", "ci.jobs", "ci").data().path("jobs")) {
        String concl = j.path("conclusion").asText(""); if (concl.isEmpty() || List.of("success", "skipped", "neutral").contains(concl)) continue;
        String full = com.x7x24.engine.CiFailure.cleanLog(Redactor.redact(c.text(p(l, "/actions/jobs/" + j.path("id").asLong() + "/logs"), "ci.job_log", "ci").text()));
        log.append("[").append(j.path("name").asText()).append("] ").append(failedSteps(j)).append("\n").append(full).append("\n");
      }
      var out = new BaseCi(ok ? "passed" : "failed", run.path("html_url").asText(), run.path("head_sha").asText(), Redactor.redact(log.toString()));
      baseCiCache.put(k, new Object[]{clk.now(), out}); return out;
    } catch (RuntimeException e) { return null; }
  }
  private List<JsonNode> runsFor(ProjectCfg l, String headSha, Set<Long> stale) {
    JsonNode r;
    try { r = client(l).objectArray(p(l, "/actions/workflows/" + enc(l.workflow) + "/runs") + "?head_sha=" + enc(headSha), "workflow_runs", "ci.list_runs", "ci"); }
    catch (GitHubException error) { if (error.status == 404) assertCiReady(l); throw ciReadFailure(error); }
    List<JsonNode> out = new ArrayList<>(); if (r != null) for (var n : r) if (!stale.contains(n.path("id").asLong()) && headSha.equals(n.path("head_sha").asText())) out.add(n);
    out.sort((a, b) -> Long.compare(b.path("id").asLong(), a.path("id").asLong()));
    return out;
  }
  private static String failedSteps(JsonNode j) { List<String> xs = new ArrayList<>(); for (var s : j.path("steps")) if ("failure".equals(s.path("conclusion").asText())) xs.add("步骤失败：" + s.path("name").asText()); return xs.isEmpty() ? "失败" : String.join("; ", xs); }
  /** 诊断入口也保留每个失败及上下文；展示层负责折叠长内容。 */
  static String excerpt(String full) {
    return com.x7x24.agents.CiLogSummarizer.forRepair(full);
  }

  // ───── 合并（治理闸门全在这里再校验一遍）─────
  @Override public MergeResult merge(ProjectCfg l, String key, int pr, String expectedSha, String title) {
    synchronized (repoLocks.computeIfAbsent(l.repo.toLowerCase(java.util.Locale.ROOT), keyRepo -> new Object())) {
    var c = client(l);
    var pull = c.get(p(l, "/pulls/" + pr), "pulls.get", "git").data();
    if (pull.path("merged").asBoolean(false)) return new MergeResult(pull.path("merge_commit_sha").asText(), true);
    write(l, key, "pulls.merge", "git.merge", "#" + pr, "release");
    return new MergeResult(once(key, java.util.Arrays.asList(l.repo, pr, expectedSha, title), () -> {
      if (!"open".equals(pull.path("state").asText())) throw new GateBlocked("pr_state", "PR #" + pr + " 状态为 " + pull.path("state").asText());
      String head = pull.path("head").path("sha").asText();
      if (!head.equals(expectedSha)) throw new GateBlocked("head_changed", "PR head 已变化：验收的是 " + expectedSha.substring(0, 7) + "，当前为 " + head.substring(0, 7));
      if (pull.path("head").path("repo").path("full_name").asText(l.repo).equals(l.repo) == false) throw new GateBlocked("fork", "不接受来自 fork 的 PR");
      List<String> files = prFiles(l, pr).stream().map(PrFile::path).toList();
      var gov = Risk.classify(cfg.get(), "", files);
      if (!gov.governanceFileHits().isEmpty()) throw new GateBlocked("governance_file", "PR 改动治理文件，拦截合并：" + gov.governanceFileHits());
      var policy = projects.constrained(l);
      if (files.stream().anyMatch(path -> PathPolicy.checkPath(policy, path) != null)) throw new GateBlocked("governance_file", "PR 改动超出当前项目路径策略");
      var project = projects.get(l.id);
      if (project != null) {
        var task = db.row("SELECT risk_level FROM task WHERE id=?", taskOf(key));
        String risk = Risk.max(task == null ? gov.level() : Db.str(task.get("risk_level")), files.stream().map(policy::riskOf).reduce("low", (a, b) -> Risk.max(a, b)));
        if (!Risk.autonomyAllows(projects.runtime(l.id).autonomyLevel(), risk)) throw new GateBlocked("scope_tightened", "当前自治等级不允许该任务合并");
      }
      // 必需检查：保护规则可读则用其 required checks；否则（免费私有仓库无保护规则）由 7x24 自己要求该 head 上所有 check run 成功
      var required = requiredChecks(l);
      var checks = c.objectArray(p(l, "/commits/" + head + "/check-runs"), "check_runs", "checks.list", "ci");
      Map<String, String> byName = new LinkedHashMap<>(); for (var ck : checks) byName.put(ck.path("name").asText(), "completed".equals(ck.path("status").asText()) ? ck.path("conclusion").asText() : "pending");
      if (checks.size() == 0) throw new GateBlocked("no_checks", "该提交没有任何 check run，不允许合并");
      for (String rq : required) if (!"success".equals(byName.get(rq))) throw new GateBlocked("required_check", "必需检查 " + rq + " 未通过（" + byName.get(rq) + "）");
      for (var e : byName.entrySet()) if (!List.of("success", "skipped", "neutral").contains(e.getValue())) throw new GateBlocked("checks_not_green", "检查 " + e.getKey() + " 为 " + e.getValue());
      try {
        var m = c.send("PUT", p(l, "/pulls/" + pr + "/merge"), Map.of("sha", head, "merge_method", "squash", "commit_title", title + " (#" + pr + ")"), "pulls.merge", "git", key).data();
        return TextNode.valueOf(m.path("sha").asText());
      } catch (GitHubException e) { if (e.status == 405 || e.status == 409) throw new MergeConflict(e.getMessage()); throw e; }
    }).asText(), false);

    }
  }
  private List<String> requiredChecks(ProjectCfg l) {
    List<String> out = new ArrayList<>();
    try {
      var r = client(l).get404(p(l, "/rules/branches/" + encPath(l.baseBranch)), "git.read_rulesets", "git");
      if (r.data() != null && r.data().isArray()) for (var rule : r.data()) if ("required_status_checks".equals(rule.path("type").asText())) for (var x : rule.path("parameters").path("required_status_checks")) out.add(x.path("context").asText());
    } catch (GitHubException e) { if (e.status != 403 && e.status != 401) throw e; }
    return out;
  }

  // ───── 健康度（无 Prometheus 时用 CI/workflow 健康度作为可用信号）─────
  @Override public Health health(ProjectCfg l, String mergeSha) {
    var c = client(l); List<String> notes = new ArrayList<>(); Map<String, List<Double>> s = new LinkedHashMap<>();
    var mainRuns = c.get(p(l, "/actions/workflows/" + enc(l.workflow) + "/runs") + "?branch=" + enc(l.baseBranch) + "&event=push&per_page=15", "ci.main_runs", "ci").data().path("workflow_runs");
    List<JsonNode> done = new ArrayList<>(), mine = new ArrayList<>();
    for (var r : mainRuns) { if (mergeSha != null && mergeSha.equals(r.path("head_sha").asText())) mine.add(r); if ("completed".equals(r.path("status").asText())) done.add(r); }
    String state = "pending"; String url = null; Long id = null;
    if (!mine.isEmpty()) { var m = mine.get(0); id = m.path("id").asLong(); url = m.path("html_url").asText(); state = "completed".equals(m.path("status").asText()) ? ("success".equals(m.path("conclusion").asText()) ? "passed" : "failed") : "pending"; }
    else notes.add("合并提交在 " + l.baseBranch + " 上尚未出现 push 触发的 run");
    int n = done.size();
    if (n > 0) {
      List<Double> fails = done.stream().map(r -> "success".equals(r.path("conclusion").asText()) ? 0.0 : 100.0).toList();
      double recent = fails.stream().limit(3).mapToDouble(x -> x).average().orElse(0), older = fails.stream().skip(3).mapToDouble(x -> x).average().orElse(0);
      double abs = recent; double delta = Math.max(0, recent - older);
      double dur0 = durationSec(done.get(0)); double med = median(done.stream().skip(1).map(GitHubTools::durationSec).sorted().toList());
      double reg = med >= 30 && dur0 - med >= 30 ? Math.max(0, (dur0 - med) / med * 100) : 0; // 秒级小 run 的抖动不算回归：基线 ≥30s 且绝对增量 ≥30s 才计
      double restarts = done.stream().limit(3).mapToDouble(r -> Math.max(0, r.path("run_attempt").asInt(1) - 1)).max().orElse(0);
      s.put("error_rate_abs_pct", List.of(abs, abs, abs)); s.put("error_rate_delta_pp", List.of(delta, delta, delta));
      s.put("p95_regression_pct", List.of(reg, reg, reg)); s.put("restart_count", List.of(restarts, restarts, restarts));
      notes.add("来源：" + l.baseBranch + " 最近 " + n + " 次 CI run（失败率 " + abs + "%，耗时回归 " + U.round(reg, 1) + "%）");
    }
    if (props.adapter.prometheusUrl != null && !props.adapter.prometheusUrl.isBlank()) notes.add("Prometheus 已配置，但本版本仅在产品线配置了 prometheusQueries 时使用");
    return new Health(s, "github-actions", notes, state, url, id);
  }
  private static double durationSec(JsonNode r) { try { return (Clk.parse(norm(r.path("updated_at").asText())) - Clk.parse(norm(r.path("run_started_at").asText()))) / 1000.0; } catch (Exception e) { return 0; } }
  private static String norm(String iso) { return iso.matches(".*\\.\\d+Z$") ? iso : iso.replace("Z", ".000Z"); }
  private static double median(List<Double> xs) { return xs.isEmpty() ? 0 : xs.get(xs.size() / 2); }

  // ───── Release / tag ─────
  @Override public ReleaseInfo release(ProjectCfg l, String key, String tag, String sha, String name, String notes) {
    synchronized (repoLocks.computeIfAbsent(l.repo.toLowerCase(java.util.Locale.ROOT), keyRepo -> new Object())) {
    write(l, key, "releases.create", "release.publish", tag, "release");
    JsonNode r = once(key, java.util.Arrays.asList(l.repo, tag, sha, name, notes), () -> {
      try { return client(l).send("POST", p(l, "/releases"), Map.of("tag_name", tag, "target_commitish", sha, "name", name, "body", notes, "draft", false, "prerelease", false), "releases.create", "git", key).data(); }
      catch (GitHubException e) {
        if (e.status != 422 || !e.getMessage().toLowerCase().contains("already_exists") && !e.getMessage().toLowerCase().contains("already exists")) throw e;
        return client(l).get(p(l, "/releases/tags/" + enc(tag)), "releases.get", "git").data();
      }
    });
    return new ReleaseInfo(r.path("id").asLong(), r.path("tag_name").asText(tag), r.path("html_url").asText(), sha);

    }
  }
  @Override public boolean verifyRelease(ProjectCfg l, String tag, String sha) {
    var rel = client(l).get404(p(l, "/releases/tags/" + enc(tag)), "releases.get", "git");
    if (rel.status() == 404 || rel.data() == null || rel.data().path("draft").asBoolean(false)) return false;
    var ref = client(l).get404(p(l, "/git/ref/tags/" + enc(tag)), "git.get_tag_ref", "git");
    if (ref.status() == 404 || ref.data() == null) return false;
    String obj = ref.data().path("object").path("sha").asText(); String type = ref.data().path("object").path("type").asText();
    if ("tag".equals(type)) { obj = client(l).get(p(l, "/git/tags/" + obj), "git.get_tag", "git").data().path("object").path("sha").asText(); }
    return sha.equals(obj);
  }

  // ───── 回滚：开还原 PR ─────
  @Override public String revert(ProjectCfg l, String key, String mergeSha, String branch, String title) {
    synchronized (repoLocks.computeIfAbsent(l.repo.toLowerCase(java.util.Locale.ROOT), keyRepo -> new Object())) {
    write(l, key, "git.revert_pr", "git.push", branch, "release");
    return once(key, () -> {
      var c = client(l); var commit = c.get(p(l, "/commits/" + mergeSha), "commits.get", "git").data();
      String parent = commit.path("parents").path(0).path("sha").asText();
      ensureBranch(l, key + ":branch", branch);
      List<FileChange> changes = new ArrayList<>();
      for (var f : commit.path("files")) {
        String status = f.path("status").asText(), path = f.path("filename").asText();
        if (status.equals("added")) changes.add(new FileChange(path, null, true));
        else { String old = readFile(l, parent, status.equals("renamed") ? f.path("previous_filename").asText() : path); if (old != null) changes.add(FileChange.put(path, old)); }
      }
      commit(l, key + ":commit", branch, "revert: " + title, changes);
      var pr = openPr(l, key + ":pr", branch, "Revert: " + title, "7x24 自动回滚：还原合并提交 " + mergeSha + "。该 PR 不会被自动合并，需走流水线治理闸门。");
      return TextNode.valueOf(pr.url());
    }).asText();

    }
  }

  // ───── 状态 ─────
  @Override public Map<String, Object> status() {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("mode", realMode() ? "real" : "offline"); o.put("dryRun", dry()); o.put("ciDryRun", dry()); o.put("tokenConfigured", hasToken()); o.put("apiHost", props.github.apiUrl.replaceFirst("^https?://", ""));
    o.put("prometheusConfigured", props.adapter.prometheusUrl != null && !props.adapter.prometheusUrl.isBlank());
    return o;
  }
  public Map<String, Object> lineStatus(ProjectCfg l) {
    Map<String, Object> o = new LinkedHashMap<>(); String reason = !realMode() ? "ADAPTER_MODE 未设为 real（离线）" : !hasToken() ? "未配置 GITHUB_TOKEN" : null;
    String mode = reason == null ? "real" : "offline";
    o.put("productLineId", l.productLineId); o.put("id", l.id); o.put("name", l.name); o.put("repo", l.repo); o.put("baseBranch", l.baseBranch);
    o.put("git", Map.of("mode", mode, "dryRun", dry() || reason != null, "reason", reason == null ? (dry() ? "dry-run：写操作只记录不发送" : "") : reason));
    var ci = new LinkedHashMap<String, Object>(); ci.put("mode", mode); ci.put("dryRun", dry() || reason != null); ci.put("reason", reason); ci.put("workflow", l.workflow); o.put("ci", ci);
    o.put("metrics", Map.of("mode", live() ? "real" : "offline", "queries", new ArrayList<>(l.prometheusQueries.keySet().isEmpty() ? List.of("github-actions: 近期 run 失败率/耗时回归/重试") : l.prometheusQueries.keySet())));
    var c = clients.get(com.x7x24.tenant.Tenant.writeId() + ":" + l.id + ":" + l.repo);
    if (c == null) o.put("rate", null); else {
      var r = new LinkedHashMap<String, Object>(); r.put("limit", c.rate.limit); r.put("remaining", c.rate.remaining); r.put("resetAt", c.rate.resetAt); r.put("retries", c.rate.retries); r.put("throttledMs", c.rate.throttledMs);
      r.put("lastStatus", c.rate.lastStatus); r.put("lastRetryAt", c.rate.lastRetryAt); r.put("lastError", c.rate.lastError); o.put("rate", r);
    }
    o.put("connection", l.repo == null ? Map.of("ok", false, "message", "项目未绑定仓库") : connection(l)); o.put("intake", Map.of("label", l.intakeLabel, "all", l.intakeAll, "enabled", l.intake));
    return o;
  }
  /** 真实连通性：GET /repos/{o}/{r}（30 秒缓存） */
  private Map<String, Object> connection(ProjectCfg l) {
    var cached = connCache.get(l.id + ":" + l.repo);
    if (cached != null && clk.now() - (Long) cached.get("_at") < 30_000) return cached;
    Map<String, Object> o = new LinkedHashMap<>(); o.put("checkedAt", clk.iso()); o.put("_at", clk.now());
    if (!live()) { o.put("ok", false); o.put("error", "离线（未启用真实适配器）"); }
    else try {
      var r = client(l).get(p(l, ""), "repo.get", "git").data();
      o.put("ok", true); o.put("private", r.path("private").asBoolean()); o.put("defaultBranch", r.path("default_branch").asText()); o.put("permissions", Json.M.convertValue(r.path("permissions"), Map.class)); o.put("url", r.path("html_url").asText());
    } catch (RuntimeException e) { o.put("ok", false); o.put("error", Redactor.redact(e.getMessage())); }
    connCache.put(l.id + ":" + l.repo, o); return o;
  }

  static boolean isSha(String ref) { return ref != null && ref.matches("(?i)[0-9a-f]{40}|[0-9a-f]{64}"); }
  /** Git Data API 的 /git/commits/{sha} 只接受 commit SHA：分支名先经 /git/ref/heads/{branch} 解析。 */
  String resolveCommitSha(ProjectCfg project, String ref) {
    String r = ref == null || ref.isBlank() ? project.baseBranch : ref.replaceFirst("^refs/heads/", "");
    if (isSha(r)) return r.toLowerCase();
    String sha = refSha(project, r);
    if (sha == null || sha.isBlank()) throw new GitHubException(404, "分支 " + r + " 不存在（仓库 " + project.repo + "），请检查项目配置的目标分支", "git/ref/heads/" + r);
    return sha;
  }
  @Override public String repositoryHead(ProjectCfg project) { return resolveCommitSha(project, project.baseBranch); }
  @Override public List<String> sourceFiles(ProjectCfg project, String ref) {
    String sha = resolveCommitSha(project, ref);
    var commit = client(project).get(p(project, "/git/commits/" + sha), "discovery.commit", "git").data();
    var tree = client(project).get(p(project, "/git/trees/" + enc(commit.path("tree").path("sha").asText())) + "?recursive=1", "discovery.tree", "git").data();
    if (tree.path("truncated").asBoolean()) throw new GateBlocked("repository_large", "仓库目录超过读取上限，请调整项目扫描范围");
    List<String> files = new ArrayList<>();
    for (var item : tree.path("tree")) {
      String path = item.path("path").asText();
      if ("blob".equals(item.path("type").asText()) && item.path("size").asLong(0) < 100000 && project.discovery.roots.stream().anyMatch(path::startsWith)) files.add(path);
    }
    Collections.sort(files); return files;
  }
  @Override public List<Diagnostic> testFailures(ProjectCfg project) {
    if (!live()) return List.of();
    var response = client(project).get404(p(project, "/actions/workflows/" + enc(project.workflow) + "/runs") + "?branch=" + enc(project.baseBranch) + "&status=completed&per_page=1", "discovery.runs", "ci");
    if (response.status() == 404 || response.data() == null || response.data().path("workflow_runs").isEmpty()) return List.of();
    var run = response.data().path("workflow_runs").get(0);
    if (!Set.of("failure", "timed_out").contains(run.path("conclusion").asText())) return List.of();
    var jobs = client(project).get(p(project, "/actions/runs/" + run.path("id").asLong() + "/jobs?per_page=100"), "discovery.jobs", "ci").data();
    List<Diagnostic> failures = new ArrayList<>();
    for (var job : jobs.path("jobs")) {
      if (!Set.of("failure", "timed_out").contains(job.path("conclusion").asText())) continue;
      String log = failedSteps(job);
      try { log += "\n" + excerpt(Redactor.redact(client(project).text(p(project, "/actions/jobs/" + job.path("id").asLong() + "/logs"), "discovery.job_log", "ci").text())); }
      catch (RuntimeException ignored) { log += "\n日志不可访问，保留失败步骤和 CI 链接作为证据"; }
      failures.add(new Diagnostic("ci:" + project.workflow + ":" + job.path("name").asText(), "修复 CI 失败：" + job.path("name").asText(), run.path("html_url").asText(), run.path("head_sha").asText(), Redactor.redact(log)));
    }
    return failures;
  }
  // ───── 仓库体检（只读）─────
  private static Map<String, Object> item(String id, String label, String status, String detail, String fix, String action) {
    Map<String, Object> m = new LinkedHashMap<>(); m.put("id", id); m.put("label", label); m.put("status", status); m.put("detail", detail); m.put("fix", fix); m.put("action", action); return m;
  }
  private static String describe(GitHubException e, String what) {
    return what + "：" + Humanize.reason(e.status, e.getMessage());
  }
  /** 体检结果缓存，供「连接状态」页展示 CI 文件情况 */
  public Map<String, Object> lastHealth(String projectId) { return healthCache.get(projectId); }
  private final Map<String, Map<String, Object>> healthCache = new ConcurrentHashMap<>();

  public Map<String, Object> verifyProject(ProjectCfg project) {
    if (project.repo == null) { Map<String, Object> m = new LinkedHashMap<>(); m.put("ok", false); m.put("repo", null); m.put("message", "项目未绑定仓库"); m.put("items", List.of()); m.put("checkedAt", clk.iso()); return m; }
    List<Map<String, Object>> items = new ArrayList<>(); Map<String, Object> result = new LinkedHashMap<>();
    result.put("repo", project.repo); result.put("baseBranch", project.baseBranch); result.put("workflow", project.workflow); result.put("checkedAt", clk.iso());
    String scopes = creds.info().scopes();
    if (!hasToken()) {
      items.add(item("token", "已连接 GitHub", "fail", "还没有可用的 GitHub 凭据", "到「集成」页连接 GitHub（授权登录或粘贴令牌）", null));
      return finishHealth(project, result, items);
    }
    JsonNode repo = null;
    try {
      var r = client(project).get404(p(project, ""), "repo.get", "git");
      if (r.status() == 404 || r.data() == null) {
        items.add(item("repo_read", "仓库可读", "fail", "找不到仓库 " + project.repo + "，或当前凭据没有访问权限", "核对 owner/repo 拼写；若使用 GitHub 授权登录，请在 GitHub 上安装该 App 并选择此仓库；或改用有 repo 权限的令牌", null));
      } else {
        repo = r.data();
        items.add(item("repo_read", "仓库可读", "ok", "可以读取" + (repo.path("private").asBoolean() ? "私有" : "公开") + "仓库，默认分支 " + repo.path("default_branch").asText(), null, null));
        result.put("private", repo.path("private").asBoolean()); result.put("defaultBranch", repo.path("default_branch").asText()); result.put("url", repo.path("html_url").asText());
        result.put("permissions", Json.M.convertValue(repo.path("permissions"), Map.class));
      }
    } catch (GitHubException e) {
      items.add(item("repo_read", "仓库可读", "fail", describe(e, "读取仓库失败"), e.status == 401 ? "到「集成」页重新连接 GitHub" : "稍后重试；持续失败请检查网络和凭据", null));
    }
    if (repo == null) {
      for (String[] x : new String[][]{{"branch", "目标分支存在"}, {"ci_file", "CI 文件存在"}, {"ci_dispatch", "CI 支持手动触发"}, {"write_code", "可写代码"}, {"write_pr", "可创建 PR"}, {"write_issues", "可管理 Issue"}, {"write_actions", "可触发 CI"}, {"protection", "分支保护"}})
        items.add(item(x[0], x[1], "unknown", "仓库不可读，未能检查", "先解决「仓库可读」", null));
      return finishHealth(project, result, items);
    }
    boolean branchOk = false;
    try {
      var b = client(project).get404(p(project, "/branches/" + enc(project.baseBranch)), "project.branch", "git");
      branchOk = b.status() == 200; result.put("branchExists", branchOk);
      items.add(branchOk ? item("branch", "目标分支存在", "ok", "分支 " + project.baseBranch + " 存在" + (b.data().path("protected").asBoolean() ? "，且受保护" : ""), null, null)
          : item("branch", "目标分支存在", "fail", "仓库里没有分支 " + project.baseBranch, "在项目配置里把目标分支改为 " + result.get("defaultBranch") + "，或先在仓库里创建该分支", null));
    } catch (GitHubException e) { items.add(item("branch", "目标分支存在", "unknown", describe(e, "检查分支失败"), "稍后重试", null)); }
    String wfPath = ".github/workflows/" + project.workflow;
    boolean wfOk = false;
    if (branchOk) try {
      var f = client(project).get404(p(project, "/contents/" + encPath(wfPath)) + "?ref=" + enc(project.baseBranch), "project.workflow", "ci");
      wfOk = f.status() == 200 && f.data() != null && f.data().has("content"); result.put("workflowExists", wfOk);
      boolean contentsDenied = false;
      if (!wfOk && f.status() == 404) {
        // 仓库可读但文件 404：再列一次根目录。根目录也 404 → 凭据没有 Contents 读权限，而不是文件不存在
        var root = client(project).get404(p(project, "/contents/") + "?ref=" + enc(project.baseBranch), "contents.list", "git");
        contentsDenied = root.status() == 404; result.put("contentsReadable", !contentsDenied);
      }
      if (!wfOk && contentsDenied) {
        items.add(item("ci_file", "CI 文件存在", "fail", "缺少 Contents 读权限：凭据能看到仓库，但读不到任何文件，无法判断 " + wfPath + " 是否存在", "GitHub App：在 App 设置 Permissions 里把 Contents 设为 Read and write，并在安装页接受新权限、确认已选中此仓库；细粒度令牌：确认仓库范围包含此仓库且 Contents 至少为 Read", null));
        items.add(item("ci_dispatch", "CI 支持手动触发", "unknown", "读不到文件，无法检查", "先解决 Contents 权限", null));
      } else if (!wfOk) {
        items.add(item("ci_file", "CI 文件存在", "fail", "分支 " + project.baseBranch + " 上没有 " + wfPath, "点击「用 PR 补充 CI 模板」：会新建分支、提交模板并开一个 PR（不会自动合并，需要你在 GitHub 上审阅合并）", "create_ci_pr"));
        items.add(item("ci_dispatch", "CI 支持手动触发", "unknown", "没有 CI 文件，无法检查", "先补充 CI 文件", null));
      } else {
        items.add(item("ci_file", "CI 文件存在", "ok", wfPath + " 已存在", null, null));
        String text = new String(Base64.getMimeDecoder().decode(f.data().get("content").asText()), java.nio.charset.StandardCharsets.UTF_8);
        if (outdatedTemplate(project, text)) items.add(item("ci_template", "CI 模板是最新的", "fail", "这个 CI 文件是 7x24 早先生成的旧模板：它在仓库根目录安装依赖、跑测试；代码放在子目录（如 frontend/、backend/）的仓库会在「Install」这一步直接失败，测试根本没跑", "点击「用 PR 更新 CI 模板」：按项目配置的模块分别安装依赖并运行测试（不会自动合并，需要你在 GitHub 上审阅合并）", "repair_ci_pr"));
        boolean dispatch = Pattern.compile("(?m)\\bworkflow_dispatch\\b").matcher(text).find();
        items.add(dispatch ? item("ci_dispatch", "CI 支持手动触发", "ok", "workflow 声明了 workflow_dispatch，7x24 可以主动触发 CI", null, null)
            : item("ci_dispatch", "CI 支持手动触发", "warn", "workflow 没有声明 workflow_dispatch：只能靠 push / PR 事件触发", "在 " + wfPath + " 的 on: 下加一行 workflow_dispatch:（需要你自己提交，7x24 不会改动已有 CI 文件）", null));
      }
    } catch (GitHubException e) { items.add(item("ci_file", "CI 文件存在", "unknown", describe(e, "检查 CI 文件失败"), "稍后重试", null)); items.add(item("ci_dispatch", "CI 支持手动触发", "unknown", "未能检查", null, null)); }
    else { items.add(item("ci_file", "CI 文件存在", "unknown", "分支不存在，未能检查", "先解决「目标分支存在」", null)); items.add(item("ci_dispatch", "CI 支持手动触发", "unknown", "未能检查", null, null)); }
    // 写权限：不做任何写入，只依据 GitHub 返回的权限信息 + 令牌类型推断；无法确认的如实标「未知」
    JsonNode perm = repo.path("permissions"); boolean known = perm.isObject() && perm.has("push");
    boolean push = perm.path("push").asBoolean(), triage = perm.path("triage").asBoolean() || push;
    boolean classic = scopes != null && !scopes.isBlank();
    String unk = "GitHub 不允许在不写入的情况下确认；首次执行时会实际验证，失败会在这里和「连接状态」页提示";
    items.add(known ? item("write_code", "可写代码", push ? "ok" : "fail", push ? "令牌对该仓库有 push 权限" : "令牌对该仓库只有读权限", push ? null : "使用有 repo 写权限的令牌；GitHub App 需要 Contents 读写权限", null) : item("write_code", "可写代码", "unknown", unk, null, null));
    items.add(known ? item("write_pr", "可创建 PR", push ? "ok" : "fail", push ? "有 push 权限，可以建分支并开 PR" : "没有写权限，无法开 PR", push ? null : "需要 Pull requests 读写权限", null) : item("write_pr", "可创建 PR", "unknown", unk, null, null));
    boolean hasIssues = repo.path("has_issues").asBoolean(true);
    items.add(!hasIssues ? item("write_issues", "可管理 Issue", "fail", "该仓库关闭了 Issues 功能", "在仓库 Settings → Features 里开启 Issues", null)
        : known ? item("write_issues", "可管理 Issue", triage ? "ok" : "fail", triage ? "Issues 已开启，且令牌有 triage 以上权限" : "令牌权限不足以编辑 Issue", triage ? null : "需要 Issues 读写权限", null) : item("write_issues", "可管理 Issue", "unknown", unk, null, null));
    boolean workflowScope = scopes != null && Arrays.asList(scopes.split("[ ,]+")).contains("workflow");
    items.add(classic && !workflowScope ? item("write_actions", "可触发 CI", "fail", "经典令牌缺少 workflow 权限范围", "重新生成令牌并勾选 workflow，或改用细粒度令牌（Actions 读写）", null)
        : known && push ? item("write_actions", "可触发 CI", classic ? "ok" : "unknown", classic ? "令牌含 workflow 权限范围" : "有写权限，但 Actions 权限无法在不触发的情况下确认", null, null) : item("write_actions", "可触发 CI", "unknown", unk, null, null));
    // 分支保护（只读）
    if (branchOk) try {
      var pr = client(project).get404(p(project, "/branches/" + enc(project.baseBranch) + "/protection"), "git.read_rulesets", "git");
      items.add(pr.status() == 200 ? item("protection", "分支保护", "ok", "目标分支已启用保护规则", null, null)
          : item("protection", "分支保护", "warn", "目标分支没有启用保护规则（7x24 仍会走自己的治理闸门，但直推不会被 GitHub 拦住）", "建议在仓库 Settings → Branches 里要求 PR 与必需检查（免费私有仓库不支持）", null));
    } catch (GitHubException e) {
      items.add(item("protection", "分支保护", "unknown", "拿不到保护规则（" + Humanize.reason(e.status, e.getMessage()) + "）。常见于免费私有仓库或令牌没有管理权限，这不影响使用", "如需确认，请到仓库 Settings → Branches 查看", null));
    } else items.add(item("protection", "分支保护", "unknown", "分支不存在，未检查", null, null));
    return finishHealth(project, result, items);
  }
  private Map<String, Object> finishHealth(ProjectCfg project, Map<String, Object> result, List<Map<String, Object>> items) {
    boolean ok = items.stream().noneMatch(i -> "fail".equals(i.get("status")));
    result.put("items", items); result.put("ok", ok);
    result.putIfAbsent("branchExists", false); result.putIfAbsent("workflowExists", false);
    if (!ok) result.put("error", items.stream().filter(i -> "fail".equals(i.get("status"))).map(i -> i.get("label") + "：" + i.get("detail")).findFirst().orElse("体检未通过"));
    healthCache.put(project.id, result); return result;
  }

  // ───── 用户显式发起的一次性集成初始化：用 PR 补 CI 模板 ─────
  static final String CI_HEADER = "# 由 7x24 的集成初始化生成：通过 PR 提交，需要你审阅后手动合并。";
  /**
   * CI 模板：按项目模块（目录 + 语言 + 测试命令）各建一个 job，在模块目录里安装依赖并运行测试。
   * 没有配置模块时按仓库根目录的 Node 项目处理（与旧模板一致）。旧模板在根目录跑 npm，前后端分目录的仓库会在「Install」直接失败。
   */
  public static String ciTemplate(ProjectCfg project) {
    StringBuilder b = new StringBuilder(CI_HEADER + "\nname: CI\n\non:\n  push:\n    branches: [ " + project.baseBranch + " ]\n  pull_request:\n  workflow_dispatch:\n\njobs:\n");
    var mods = project.modules == null ? List.<ProjectCfg.Module>of() : project.modules.stream().filter(m -> m.path != null && !m.path.isBlank()).toList();
    if (mods.isEmpty()) {
      b.append("  test:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n      - uses: actions/setup-node@v4\n        with:\n          node-version: 24.22.1\n      - name: Install\n        run: if [ -f package-lock.json ]; then npm ci; elif [ -f package.json ]; then npm install; else echo \"根目录没有 package.json，跳过安装\"; fi\n      - name: Test\n        run: if [ -f package.json ]; then npm test; else echo \"根目录没有 package.json：请在 7x24 项目配置里填写模块（目录与测试命令）后重新生成 CI 模板\"; exit 1; fi\n");
      return b.toString();
    }
    Set<String> ids = new HashSet<>();
    for (var m : mods) {
      String dir = m.path.replaceAll("^\\./", "").replaceAll("/+$", ""); String lang = (m.language == null ? "" : m.language).toLowerCase();
      String id = (m.name == null || m.name.isBlank() ? dir : m.name).toLowerCase().replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", ""); if (id.isEmpty() || !ids.add(id)) id = "m" + ids.size(); ids.add(id);
      String test = m.testCommand == null ? "" : m.testCommand.strip().replaceFirst("^cd\\s+" + java.util.regex.Pattern.quote(dir) + "/?\\s*&&\\s*", "");
      b.append("  ").append(id).append(":\n    name: ").append(m.name == null || m.name.isBlank() ? dir : m.name).append("\n    runs-on: ubuntu-latest\n    defaults:\n      run:\n        working-directory: ").append(dir).append("\n    steps:\n      - uses: actions/checkout@v4\n");
      if (lang.contains("go")) {
        b.append("      - uses: actions/setup-go@v5\n        with:\n          go-version-file: ").append(dir).append("/go.mod\n      - name: Install\n        run: go mod download\n      - name: Test\n        run: ").append(test.isEmpty() ? "go test ./..." : test).append("\n");
      } else if (lang.contains("python")) {
        b.append("      - uses: actions/setup-python@v5\n        with:\n          python-version: '3.12'\n      - name: Install\n        run: if [ -f requirements.txt ]; then pip install -r requirements.txt; fi; pip install pytest\n      - name: Test\n        run: ").append(test.isEmpty() ? "pytest" : test).append("\n");
      } else if (lang.contains("java") || lang.contains("kotlin")) {
        b.append("      - uses: actions/setup-java@v4\n        with:\n          distribution: temurin\n          java-version: '21'\n      - name: Test\n        run: ").append(test.isEmpty() ? "mvn -B test" : test).append("\n");
      } else {
        b.append("      - uses: actions/setup-node@v4\n        with:\n          node-version: 24.22.1\n      - name: Install\n        run: if [ -f package-lock.json ]; then npm ci; else npm install; fi\n      - name: Test\n        run: ").append(test.isEmpty() ? "npm test" : test).append("\n");
      }
    }
    return b.toString();
  }
  /** 仓库里的 CI 文件是 7x24 旧模板生成的、且和按当前模块生成的不一样 → 建议用 PR 更新 */
  public static boolean outdatedTemplate(ProjectCfg project, String current) { return current != null && current.startsWith(CI_HEADER) && !current.strip().equals(ciTemplate(project).strip()); }
  /**
   * 创建「补充 CI 模板」PR。这是用户在界面上显式发起的一次性动作：
   * 因此单独放行对 .github/workflows 的写入（PathPolicy / 禁区词对日常 agent 仍然生效），但仍受 kill switch、项目暂停、观察模式约束；
   * 只创建分支 + 提交 + PR，绝不自动合并；全过程写审计。
   */
  public Map<String, Object> bootstrapCi(ProjectCfg project) { return bootstrapCi(project, false); }
  /** repair = 更新 7x24 之前生成的旧模板（只改 7x24 生成的文件，不覆盖你自己写的 CI） */
  public Map<String, Object> bootstrapCi(ProjectCfg project, boolean repair) {
    if (!hasToken()) throw new IllegalArgumentException("请先连接 GitHub");
    String path = ".github/workflows/" + project.workflow; String content = ciTemplate(project);
    String stem = project.workflow.replaceAll("\\.ya?ml$", "").replaceAll("[^A-Za-z0-9_-]", "-"); String branch = (repair ? "x7x24/fix-ci-" : "x7x24/setup-ci-") + stem;
    Map<String, Object> out = new LinkedHashMap<>(); out.put("path", path); out.put("content", content); out.put("branch", branch);
    out.put("steps", List.of("新建分支 " + branch, "在该分支提交 " + path, "向 " + project.baseBranch + " 开一个 PR（不会自动合并）"));
    if (!realMode() || dry()) { out.put("mode", "preview"); out.put("message", "当前是观察模式，没有向 GitHub 写入。以下是将要提交的模板；切换到执行模式后再点一次即可创建 PR，也可以自己手动提交。"); return out; }
    kill.assertWritable(project.productLineId == null ? project.id : project.productLineId, project.id, "write");
    if (projects.get(project.id) != null && projects.runtime(project.id).paused() != 0) throw new GateBlocked("project_paused", "项目或产品线已暂停/归档");
    var exists = client(project).get404(p(project, "/contents/" + encPath(path)) + "?ref=" + enc(project.baseBranch), "project.workflow", "ci");
    if (exists.status() == 200 && !repair) throw new IllegalArgumentException("CI 文件 " + path + " 已存在，无需补充");
    if (repair) {
      if (exists.status() != 200) throw new IllegalArgumentException("CI 文件 " + path + " 不存在，请用「补充 CI 模板」");
      String cur = new String(Base64.getMimeDecoder().decode(exists.data().path("content").asText()), java.nio.charset.StandardCharsets.UTF_8);
      if (!cur.startsWith(CI_HEADER)) throw new IllegalArgumentException("CI 文件 " + path + " 不是 7x24 生成的，不会覆盖；请参考下面的模板自己修改");
      if (!outdatedTemplate(project, cur)) throw new IllegalArgumentException("CI 文件已经是最新模板");
    }
    audit.sys("INTEGRATION_CI_BOOTSTRAP", "operator", Map.of("repo", project.repo, "path", path, "branch", branch, "note", "用户显式发起；仅创建 PR，不自动合并"), null);
    String key = "integration:ci:" + project.id + ":" + project.workflow + (repair ? ":fix:" + U.sha256(content).substring(0, 8) : "");
    String title = repair ? "ci: 更新 7x24 CI 模板（按项目模块安装依赖并测试）" : "ci: 添加 7x24 所需的 CI 模板";
    JsonNode pr = once(key, () -> {
      String base = refSha(project, project.baseBranch); if (base == null) throw new IllegalArgumentException("目标分支 " + project.baseBranch + " 不存在");
      if (refSha(project, branch) == null) client(project).send("POST", p(project, "/git/refs"), Map.of("ref", "refs/heads/" + branch, "sha", base), "git.create_ref", "git", key);
      var cur = client(project).get404(p(project, "/contents/" + encPath(path)) + "?ref=" + enc(branch), "contents.read", "git");
      Map<String, Object> put = new LinkedHashMap<>(); put.put("message", title); put.put("content", Base64.getEncoder().encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))); put.put("branch", branch);
      if (cur.status() == 200 && cur.data() != null) put.put("sha", cur.data().path("sha").asText());
      client(project).send("PUT", p(project, "/contents/" + encPath(path)), put, "git.create_commit", "git", key);
      try { return client(project).send("POST", p(project, "/pulls"), Map.of("title", title, "head", branch, "base", project.baseBranch, "body", repair ? "由 7x24 的「仓库体检」发起：`" + path + "` 是 7x24 早先生成的旧模板，它在仓库根目录安装依赖、跑测试；这个仓库的代码在子目录，CI 会在「Install」这一步直接失败，测试没有真正运行。新模板按项目配置的模块分别安装依赖并运行测试。请审阅后合并；7x24 不会自动合并。" : "由 7x24 的「仓库体检」发起：仓库缺少 `" + path + "`。请审阅后合并；7x24 不会自动合并这个 PR。"), "pulls.create", "git", key).data(); }
      catch (GitHubException e) {
        if (e.status != 422) throw e;
        var list = client(project).get(p(project, "/pulls?state=open&head=" + enc(project.owner() + ":" + branch)), "pulls.find", "git").data();
        if (list != null && list.isArray() && list.size() > 0) return list.get(0); throw e;
      }
    });
    out.put("mode", "created"); out.put("prUrl", pr.path("html_url").asText()); out.put("prNumber", pr.path("number").asInt()); out.put("message", "已创建 PR，请到 GitHub 审阅并合并；合并后再次体检即可通过。");
    return out;
  }
  public void invalidateConnections() { connCache.clear(); }
}
