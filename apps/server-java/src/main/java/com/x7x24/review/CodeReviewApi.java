package com.x7x24.review;

import com.x7x24.adapters.DeliveryTools;
import com.x7x24.engine.Engine;
import com.x7x24.engine.ProjectRepo;
import com.x7x24.gov.AuditLog;
import com.x7x24.gov.BudgetService;
import com.x7x24.gov.ConfigStore;
import com.x7x24.llm.ModelRouter;
import com.x7x24.tenant.WorkspaceFilter;
import com.x7x24.tenant.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 逐行审查试跑：对项目里一个已有 PR 跑一次 open-code-review（不建任务、不改流程、不在 GitHub 留评论，只读 PR 并记账），用于看效果和花费。
 * 只有工作区管理员可用；用量记在项目所在业务线下（agent = code_review_trial）。
 */
@RestController
@RequestMapping("/api/code-review")
public class CodeReviewApi {
  private final ProjectRepo projects; private final DeliveryTools tools; private final ModelRouter router; private final BudgetService budget; private final ConfigStore cfg; private final AuditLog audit; private final Engine engine;
  public CodeReviewApi(ProjectRepo projects, DeliveryTools tools, ModelRouter router, BudgetService budget, ConfigStore cfg, AuditLog audit, Engine engine) {
    this.projects = projects; this.tools = tools; this.router = router; this.budget = budget; this.cfg = cfg; this.audit = audit; this.engine = engine;
  }

  @PostMapping("/trial") public Map<String, Object> trial(@RequestBody Map<String, Object> body, HttpServletRequest r) {
    if (!WorkspaceFilter.manager(r)) throw new WorkspaceService.Forbidden("只有工作区管理员可以试跑逐行审查");
    String pid = String.valueOf(body.getOrDefault("projectId", "")); int pr;
    try { pr = Integer.parseInt(String.valueOf(body.get("pr")).replaceAll("[^0-9]", "")); } catch (RuntimeException e) { throw new IllegalArgumentException("PR 编号不对"); }
    var p = projects.get(pid); if (p == null) throw new IllegalArgumentException("项目不存在或不在当前工作区");
    var lc = p.cfg(); var cr = cfg.get().codeReview == null ? new com.x7x24.gov.Governance.CodeReview() : cfg.get().codeReview;
    String head = tools.prHead(lc, pr);
    if (head == null || head.isBlank()) throw new IllegalArgumentException("读不到 PR #" + pr + "（PR 不存在，或项目没有接 GitHub）");
    var runner = engine.ocrRunner();
    List<OcrRunner.Llm> llms = new ArrayList<>();
    if (runner.usesModels()) {
      for (var e : router.reviewEndpoints(0, "review.code", Set.of(), null, cr.fallbackModels)) llms.add(new OcrRunner.Llm(e.model(), e.baseUrl(), e.apiKey(), e.protocol(), e.priceInPer1m(), e.priceOutPer1m()));
      if (llms.isEmpty()) throw new IllegalArgumentException("没有可用的审查模型：请先在「模型与流程」里配好审查模型");
    }
    String key = "code_review_trial:" + pid + ":" + pr + ":" + UUID.randomUUID().toString().substring(0, 8);
    long t0 = System.currentTimeMillis();
    var req = new OcrRunner.Request(key, "trial:" + pid + ":" + pr, lc.repo, lc.baseBranch == null ? "main" : lc.baseBranch, head, "试跑：审查 PR #" + pr, null, null,
        cr.maxTokens, cr.timeoutMin, cr.concurrency, cr.wallClockMin, runner.usesModels() ? List.of() : tools.prPatches(lc, pr));
    var run = CodeReviewRun.run(runner, req, llms, null, () -> false, null, null);
    for (var a : run.attempts()) if (a.tokens() > 0 || a.usd() > 0) budget.chargeProject(key + ":" + a.model(), null, p.productLineId(), p.id(), "code_review_trial", a.model(), a.tokens(), a.usd(), 0);
    var res = run.result();
    if (res == null) throw new IllegalStateException("试跑没拿到结果：" + run.error());
    var coverage = Engine.codeReviewCoverage(res, false);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("projectId", pid); out.put("pr", pr); out.put("engine", "open-code-review " + Engine.OCR_VERSION); out.put("summary", res.bySeverity()); out.put("blockingCount", res.blocking().size()); out.put("wouldBlock", !res.blocking().isEmpty());
    out.put("findings", res.findings().stream().map(OcrResult.Finding::map).toList()); out.put("unlocated", res.unlocated().stream().map(OcrResult.Finding::map).toList()); out.putAll(coverage);
    out.put("tokens", run.tokens()); out.put("usd", run.usd()); out.put("models", run.models()); out.put("seconds", Math.round((System.currentTimeMillis() - t0) / 100.0) / 10.0);
    if (!run.done()) out.put("error", run.error());
    audit.sys("CODE_REVIEW_TRIAL", "governance", Map.of("projectId", pid, "pr", pr, "findings", res.total(), "tokens", run.tokens(), "usd", run.usd()), key);
    return out;
  }
}
