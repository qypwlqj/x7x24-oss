package com.x7x24;

import com.x7x24.adapters.Redactor;
import com.x7x24.agents.*;
import com.x7x24.engine.*;
import com.x7x24.gov.ConfigStore;
import com.x7x24.llm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import org.springframework.core.env.Environment;
import java.util.*;

/** 装配：LLM / 规则型 agent 二选一；产品线从配置同步；治理配置与环境对齐。 */
@Configuration
public class Wiring {
  private static final Logger log = LoggerFactory.getLogger(Wiring.class);


  /** 显式从 Environment 读取配置（环境变量 / application.yml / -D 均可），避免依赖 JavaBean 绑定。 */
  @Bean public X7Props x7Props(Environment e) {
    X7Props p = new X7Props();
    p.github.token = e.getProperty("x7x24.github.token", ""); p.github.apiUrl = e.getProperty("x7x24.github.api-url", p.github.apiUrl);
    p.github.webUrl = e.getProperty("x7x24.github.web-url", p.github.webUrl); p.github.oauthClientId = e.getProperty("x7x24.github.oauth-client-id", ""); p.github.appSlug = e.getProperty("x7x24.github.app-slug", "");
    p.adapter.mode = e.getProperty("x7x24.adapter.mode", "offline"); p.adapter.dryRun = e.getProperty("x7x24.adapter.dry-run", Boolean.class, true);
    p.adapter.ciTimeoutSec = e.getProperty("x7x24.adapter.ci-timeout-sec", Integer.class, p.adapter.ciTimeoutSec); p.adapter.ciStartTimeoutSec = e.getProperty("x7x24.adapter.ci-start-timeout-sec", Integer.class, p.adapter.ciStartTimeoutSec);
    p.adapter.prometheusUrl = e.getProperty("x7x24.adapter.prometheus-url", "");
    p.llm.baseUrl = e.getProperty("x7x24.llm.base-url", ""); p.llm.apiKey = e.getProperty("x7x24.llm.api-key", ""); p.llm.model = e.getProperty("x7x24.llm.model", "");
    p.llm.temperature = e.getProperty("x7x24.llm.temperature", Double.class, p.llm.temperature); p.llm.priceInPer1m = e.getProperty("x7x24.llm.price-in-per-1m", Double.class, p.llm.priceInPer1m); p.llm.priceOutPer1m = e.getProperty("x7x24.llm.price-out-per-1m", Double.class, p.llm.priceOutPer1m);
    p.llm.timeoutSec = e.getProperty("x7x24.llm.timeout-sec", Integer.class, p.llm.timeoutSec); p.llm.maxRetries = e.getProperty("x7x24.llm.max-retries", Integer.class, p.llm.maxRetries); p.llm.maxConcurrency = e.getProperty("x7x24.llm.max-concurrency", Integer.class, p.llm.maxConcurrency); p.llm.minIntervalMs = e.getProperty("x7x24.llm.min-interval-ms", Long.class, p.llm.minIntervalMs);
    p.llm.cheapModel = e.getProperty("x7x24.llm.cheap-model", ""); p.llm.judgeModels = e.getProperty("x7x24.llm.judge-models", "");
    p.engine.enabled = e.getProperty("x7x24.engine.enabled", Boolean.class, true); p.engine.tickMs = e.getProperty("x7x24.engine.tick-ms", Long.class, 2000L); p.engine.intakeMs = e.getProperty("x7x24.engine.intake-ms", Long.class, 30000L);
    p.engine.observeHours = e.getProperty("x7x24.engine.observe-hours", Double.class, -1.0); p.engine.testClock = e.getProperty("x7x24.engine.test-clock", Boolean.class, false);
    p.webDir = e.getProperty("x7x24.web-dir", p.webDir);
    for (int i = 0; e.getProperty("x7x24.lines[" + i + "].id") != null; i++) {
      String k = "x7x24.lines[" + i + "].";
      LegacyLineCfg l = new LegacyLineCfg(); l.id = e.getProperty(k + "id"); l.name = e.getProperty(k + "name", l.id); l.repo = e.getProperty(k + "repo");
      l.baseBranch = e.getProperty(k + "base-branch", "main"); l.workflow = e.getProperty(k + "workflow", "ci.yml"); l.intakeLabel = e.getProperty(k + "intake-label", l.intakeLabel);
      l.intakeAll = e.getProperty(k + "intake-all", Boolean.class, false); l.releasePrefix = e.getProperty(k + "release-prefix", "x7x24"); l.autonomy = e.getProperty(k + "autonomy", "L2");
      l.deployWorkflow = e.getProperty(k + "deploy-workflow");
      p.lines.add(l);
    }
    return p;
  }

  /** 模型路由：优先用「模型配置」里保存的配置，否则从 LLM_* 环境变量迁移（行为与改造前一致）。 */
  @Bean public com.x7x24.llm.ModelRouter modelRouter(X7Props p, com.x7x24.pipeline.PipelineService pipes, com.x7x24.llm.ProviderKeys keys, Environment env, com.x7x24.tenant.WorkspaceService workspaces, com.x7x24.db.Db db) {
    keys.registerAll();
    var gate = new LlmGate(p.llm.maxConcurrency, p.llm.minIntervalMs); var stored = pipes.platformDoc().models;
    if (stored != null) { var errs = com.x7x24.llm.ModelRouter.validateRegistration(stored, true); if (!errs.isEmpty()) { log.warn("已保存的模型配置无效，改用环境变量：{}", errs); stored = null; } }
    var r = new com.x7x24.llm.ModelRouter(p, stored, com.x7x24.llm.ModelRouter.openAi(p, gate));
    r.keyStore = keys::get; r.allowLocalHttp = env.getProperty("x7x24.llm-allow-local-http", Boolean.class, false);
    // 多工作区：工作区自己的模型配置 + 平台兜底开关（决策 5A）
    r.wsLoader = pipes::wsModels; r.fallbackAllowed = workspaces::llmFallback; pipes.onModelsChanged = r::invalidate; workspaces.onQuotaChanged = r::invalidate;
    pipes.lineWorkspace = id -> { var row = db.row("SELECT workspace_id FROM product_line WHERE id=?", id); return row == null ? null : (String) row.get("workspace_id"); };
    return r;
  }

  @Bean public Providers providers(X7Props p, com.x7x24.llm.ModelRouter router) {
    var l = p.llm;
    boolean llm = !l.baseUrl.isBlank() && !l.apiKey.isBlank() && !l.model.isBlank();
    if (!llm) {
      log.info("LLM 凭据未提供：使用确定性规则型本地 agent（产出真实可审查 diff）+ 规则评审员");
      return new Providers("rule", new RuleJudgeProvider("rule-agent", 0), new RuleJudgeProvider("rule-agent", 0),
          List.of(new RuleJudgeProvider("rule-judge-a", -0.02), new RuleJudgeProvider("rule-judge-b", 0.0), new RuleJudgeProvider("rule-judge-c", 0.02)), new RuleJudgeProvider("rule-arbiter", 0));
    }
    Redactor.register(l.apiKey);
    // 允许逐个接入模型；委员会按本次任务的生效配置检查就绪状态。
    List<LlmProvider> judges = new ArrayList<>(); int n = router.seatCount();
    for (int i = 0; i < n; i++) judges.add(router.seat(i, "judge-" + (i + 1), java.util.Set.of()));
    LlmProvider exec = router.maker("exec", null, null, false), cheap = router.maker("exec", null, null, true);
    log.info("LLM 模式：exec={} judges={}（模型配置{}）", exec.model(), judges.stream().map(LlmProvider::model).toList(), router.config().migratedFromEnv ? "迁移自环境变量" : "来自模型配置页");
    return new Providers("llm", exec, cheap, judges, judges.get(judges.size() - 1));
  }

  @Bean public com.x7x24.execution.agent.AgentRuntimeSettings agentRuntimeSettings(Environment e) { return com.x7x24.execution.agent.AgentRuntimeSettings.from(e); }
  /** 开发方式由项目 / 任务选择：沙盒（默认，需要 AGENT_RUNTIME=remote-docker）或快速方式；沙盒失败不自动回退。 */
  /** 页面验收：只有 AGENT_RUNTIME=remote-docker 且 UI_ACCEPTANCE=on 才创建服务并在目录里放开 UI_ACCEPTANCE 节点。 */
  /** agent 日志：推给旧 SSE（agent-log），同时把精简运行明细写成任务事件（M4 时间线）。只有开关打开时才会被调用。 */
  @org.springframework.beans.factory.annotation.Autowired private com.x7x24.gov.AuditLog auditForAgent;
  private com.x7x24.execution.agent.AgentJobService.LogSink agentSink(com.x7x24.web.Sse sse, com.x7x24.db.Db db, Object ignored) {
    var tl = new com.x7x24.execution.agent.AgentTimeline(db, auditForAgent);
    return (ws, task, job, e) -> { sse.agentLog(ws, task, job, e); try { tl.accept(ws, task, job, e); } catch (RuntimeException ex) { log.warn("agent 明细写入时间线失败：{}", ex.getMessage()); } };
  }
  @Bean public Object uiAcceptanceWiring(com.x7x24.execution.agent.AgentRuntimeSettings agentRuntime, Engine engine, com.x7x24.db.Db db, com.x7x24.util.Clk clk, com.x7x24.web.Sse sse, X7Props props, Environment env,
      com.x7x24.integration.CredentialProvider githubCredentials) {
    if (!agentRuntime.uiAcceptanceEnabled()) return "ui-acceptance:off";
    var judge = new com.x7x24.execution.agent.AcceptanceJudge(new com.x7x24.execution.agent.AcceptanceJudge.Config(
        env.getProperty("ACCEPTANCE_JUDGE_BASE_URL", props.llm.baseUrl), env.getProperty("ACCEPTANCE_JUDGE_API_KEY", props.llm.apiKey),
        env.getProperty("ACCEPTANCE_JUDGE_MODEL", "deepseek-flash"), env.getProperty("ACCEPTANCE_JUDGE_IMAGES", Boolean.class, true), env.getProperty("ACCEPTANCE_JUDGE_MAX_SHOTS", Integer.class, 8)));
    var svc = new com.x7x24.execution.agent.UiAcceptanceService(db, clk, agentRuntime.newRemoteRuntime(), agentRuntime, repo -> githubCredentials.token(), agentSink(sse, db, engine), judge,
        java.nio.file.Path.of(env.getProperty("DATA_DIR", "./data")));
    engine.setUiAcceptance(svc, env.getProperty("X7_PUBLIC_URL", ""));
    com.x7x24.pipeline.Nodes.def("UI_ACCEPTANCE").available = true;
    return "ui-acceptance:on";
  }
  /** 逐行审查：AGENT_RUNTIME=remote-docker 且有模型凭据时在 runner 沙箱里跑 open-code-review；否则用离线规则版（不调模型） */
  @Bean public Object ocrReviewWiring(com.x7x24.execution.agent.AgentRuntimeSettings agentRuntime, Engine engine, Providers pr, com.x7x24.integration.CredentialProvider githubCredentials) {
    if (!agentRuntime.remote() || !pr.llm()) return "ocr-review:rule";
    engine.setOcrRunner(new com.x7x24.execution.agent.RemoteOcrRunner((com.x7x24.execution.agent.RemoteDockerRuntime) agentRuntime.newRemoteRuntime(), agentRuntime, repo -> githubCredentials.token()));
    return "ocr-review:runner";
  }
  @Bean public Models.ExecAgents execAgents(Providers pr, ConfigStore cfg, com.x7x24.llm.ModelRouter router, com.x7x24.pipeline.PipelineService pipes,
      com.x7x24.execution.agent.AgentRuntimeSettings agentRuntime, com.x7x24.db.Db db, com.x7x24.util.Clk clk, com.x7x24.web.Sse sse, X7Props props,
      com.x7x24.integration.CredentialProvider githubCredentials, ModelCalls modelCalls, com.x7x24.learning.LearningPolicies learningPolicies) {
    Models.ExecAgents base = pr.llm() ? new LlmAgents(router, cfg, pipes) : new RuleAgents(cfg);
    if (base instanceof LlmAgents llm) llm.modelCalls = modelCalls;
    // 没有模型凭据时（离线演示 / 测试）用确定性规则 agent。
    var inner = learningPolicies.wrap(base);
    if (!pr.llm()) return inner;
    // 开发方式（沙盒 / 快速方式）由项目、任务设置选择；快速方式走 inner（含返修策略）。
    return com.x7x24.execution.agent.AgentRuntimeAgents.wrap(inner, agentRuntime, () -> {
      var jobs = new com.x7x24.execution.agent.AgentJobService(db, clk, agentRuntime.newRemoteRuntime(), agentRuntime, repo -> githubCredentials.token(), agentSink(sse, db, null), props.llm.priceInPer1m, props.llm.priceOutPer1m);
      jobs.modelCalls = modelCalls; return jobs;
    });
  }
  @Bean public Committee committee(Providers pr, ConfigStore cfg, com.x7x24.llm.ModelRouter router, com.x7x24.db.Db db, com.x7x24.pipeline.PipelineService pipes, ModelCalls modelCalls) { var committee = new Committee(pr, cfg, pr.llm() ? router : null, t -> new java.util.HashSet<>(db.rows("SELECT DISTINCT model FROM budget_ledger WHERE task_id=? AND agent IN ('drafter','planner','spec','design','dev')", t.id).stream().map(r -> String.valueOf(r.get("model"))).toList())); committee.pipes = pipes; committee.modelCalls = modelCalls; return committee; }

  @Component
  public static class Bootstrap implements ApplicationRunner {
    private final X7Props props; private final LineRepo lines; private final ProjectRepo projects; private final ConfigStore cfg;
    public Bootstrap(X7Props props, LineRepo lines, ProjectRepo projects, ConfigStore cfg) { this.props = props; this.lines = lines; this.projects = projects; this.cfg = cfg; }
    @Override public void run(ApplicationArguments args) {
      Redactor.register(props.github.token);
      for (var l : props.lines) { if (l.name == null) l.name = l.id; lines.upsert(l, true); projects.bootstrap(l); log.info("产品线 {} → {}（{}）", l.id, l.repo, props.adapter.mode); }
      if (props.engine.observeHours >= 0 && cfg.get().release.observeHours != props.engine.observeHours)
        cfg.update(com.x7x24.util.Json.tree("{\"release\":{\"observeHours\":" + props.engine.observeHours + "}}"));
      log.info("适配器 mode={} dryRun={} tokenConfigured={}", props.adapter.mode, props.adapter.dryRun, !props.github.token.isBlank());
    }
  }
}
