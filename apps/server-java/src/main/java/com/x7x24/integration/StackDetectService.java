package com.x7x24.integration;

import com.x7x24.adapters.CallLog;
import com.x7x24.adapters.GitHubClient;
import com.x7x24.engine.ProjectCfg;
import com.x7x24.engine.ProjectRepo;
import com.x7x24.engine.X7Props;
import com.x7x24.gov.AuditLog;
import com.x7x24.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 通过 GitHub API 读取文件树与清单文件 → StackDetector；并对仍是旧默认技术栈的项目自动重新识别。 */
@Component
public class StackDetectService {
  private static final Logger log = LoggerFactory.getLogger(StackDetectService.class);
  private final X7Props props; private final CredentialProvider creds; private final CallLog calls; private final ProjectRepo projects; private final AuditLog audit; private final RuntimeSettings settings;
  public StackDetectService(X7Props props, CredentialProvider creds, CallLog calls, ProjectRepo projects, AuditLog audit, RuntimeSettings settings) { this.props = props; this.creds = creds; this.calls = calls; this.projects = projects; this.audit = audit; this.settings = settings; }

  public StackDetector.Suggestion detect(String repo, String branch) {
    if (!creds.hasToken()) throw new IllegalArgumentException("请先在「集成」页连接 GitHub，才能读取仓库结构");
    if (repo == null || !repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("仓库格式应为 owner/repo");
    GitHubClient c = new GitHubClient(props.github.apiUrl, () -> creds.tokenForRepo(repo), calls, h -> { }, 1, 5000, null, null).authorization(status -> creds.observeRepository(repo, status), () -> creds.refreshRejected(repo));
    String ref = branch == null || branch.isBlank() ? "HEAD" : branch;
    var tree = c.get404("/repos/" + repo + "/git/trees/" + URLEncoder.encode(ref, StandardCharsets.UTF_8) + "?recursive=1", "contents.list", "git");
    if (tree.status() == 404 || tree.data() == null) throw new IllegalArgumentException("读不到仓库 " + repo + " 的文件树：仓库或分支不存在，或当前凭据没有 Contents 读权限");
    List<String> paths = new ArrayList<>(); for (var n : tree.data().path("tree")) if ("blob".equals(n.path("type").asText())) paths.add(n.path("path").asText());
    Map<String, String> cache = new HashMap<>();
    var s = StackDetector.detect(paths, p -> cache.computeIfAbsent(p, k -> {
      try {
        var f = c.get404("/repos/" + repo + "/contents/" + k + "?ref=" + URLEncoder.encode(ref, StandardCharsets.UTF_8), "contents.read", "git");
        if (f.status() != 200 || !f.data().has("content")) return null;
        return new String(Base64.getMimeDecoder().decode(f.data().path("content").asText()), StandardCharsets.UTF_8);
      } catch (Exception e) { return null; }
    }));
    if (tree.data().path("truncated").asBoolean()) s.evidence().add("文件树过大被 GitHub 截断，结果可能不完整");
    return s;
  }
  public Map<String, Object> detectProject(String id, boolean apply, String actor) {
    var p = projects.require(id); var s = detect(p.cfg().repo, p.cfg().baseBranch);
    Map<String, Object> o = new LinkedHashMap<>(); o.put("suggestion", s); o.put("patch", s.configPatch());
    if (apply) {
      projects.update(id, Json.M.valueToTree(Map.of("config", s.configPatch())));
      audit.sys("PROJECT_STACK_DETECTED", actor, Map.of("id", id, "repo", p.cfg().repo, "technology", s.technology(), "applied", true), "stack:" + id + ":" + System.currentTimeMillis());
      o.put("applied", true);
    }
    return o;
  }
  public static boolean legacy(ProjectCfg c) { return c.technology == null || c.technology.isBlank() || StackDetector.LEGACY_DEFAULT.equals(c.technology) || c.technology.startsWith("未识别"); }

  /** 启动后与每 30 分钟：仓库已绑定、有凭据、技术栈仍是旧默认值的项目 → 自动识别并应用（有审计）。任务未结束的项目也可更新（不改仓库 / 分支）。 */
  @Scheduled(initialDelay = 60_000, fixedDelay = 30 * 60_000)
  public void redetectLegacy() {
    if (!"real".equalsIgnoreCase(settings.adapterMode())) return;
    for (var p : projects.list()) com.x7x24.tenant.Tenant.run(p.workspaceId(), () -> {
      if (!creds.hasToken() || "ARCHIVED".equals(p.state()) || p.cfg().repo == null || !legacy(p.cfg())) return;
      try { var r = detectProject(p.id(), true, "stack-detector"); log.info("技术栈自动识别 {} ({}) → {}", p.id(), p.cfg().repo, ((StackDetector.Suggestion) r.get("suggestion")).technology()); }
      catch (Exception e) { log.warn("技术栈自动识别失败 {} ({}): {}", p.id(), p.cfg().repo, com.x7x24.adapters.Redactor.redact(String.valueOf(e.getMessage()))); }
    });
  }
}
