package com.x7x24.chat;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {
  @Bean public IntakeTools intakeTools(com.x7x24.engine.ProjectRepo projects, com.x7x24.adapters.DeliveryTools tools, com.x7x24.db.Db db, com.x7x24.gov.ConfigStore cfg, com.x7x24.integration.StackDetectService stack) {
    return new IntakeTools(projects, tools, db, cfg, stack);
  }
  @Bean public PlanService planService(com.x7x24.pipeline.PipelineService pipes, com.x7x24.engine.ProjectRepo projects, com.x7x24.integration.RuntimeSettings settings, com.x7x24.pipeline.WorkspaceNodes wsNodes, com.x7x24.pipeline.Presets presets, com.x7x24.gov.ConfigStore cfg) { var p = new PlanService(pipes, projects, settings); p.cfg = cfg; p.wsNodes = wsNodes; p.presets = presets; return p; }
  @Bean public ChatActions chatActions(com.x7x24.db.Db db, com.x7x24.util.Clk clk, com.x7x24.gov.AuditLog audit, com.x7x24.engine.LineRepo lines, com.x7x24.engine.ProjectRepo projects, com.x7x24.tenant.MemberService members, org.springframework.beans.factory.ObjectProvider<com.x7x24.integration.GitHubAuth> gh) { var a = new ChatActions(db, clk, audit, lines, projects, members); a.repoAccessible = repo -> { var g = gh.getIfAvailable(); if (g == null) return null;
      try {
        var items = (java.util.List<?>) g.repos(repo, 1, 100, java.util.Map.of()).get("items");
        if (items.stream().anyMatch(x -> x instanceof java.util.Map<?, ?> m && repo.equalsIgnoreCase(String.valueOf(m.get("fullName"))))) return true;
        // 只有能列出仓库（已安装、有仓库权限、至少看得到一个仓库）时才判定「不存在 / 没权限」；否则无法判断，不拦
        var all = g.repos("", 1, 1, java.util.Map.of());
        boolean authoritative = !Boolean.TRUE.equals(all.get("needInstall")) && all.get("hint") == null && all.get("total") instanceof Number n && n.intValue() > 0;
        return authoritative ? false : null;
      }
      catch (RuntimeException e) { return null; } }; return a; }
  @Bean public TaskActions taskActions(com.x7x24.db.Db db, com.x7x24.util.Clk clk, com.x7x24.gov.AuditLog audit, com.x7x24.engine.TaskRepo repo, com.x7x24.engine.Engine engine, com.x7x24.gov.BudgetService budget, com.x7x24.llm.ModelRouter router) {
    return new TaskActions(db, clk, audit, repo, engine, budget, () -> router.maker(ChatService.ROLE, null, null, false));
  }
  @Bean public ChatService chatService(com.x7x24.db.Db db, com.x7x24.util.Clk clk, com.x7x24.gov.ConfigStore cfg, com.x7x24.gov.AuditLog audit, com.x7x24.gov.BudgetService budget,
                                       com.x7x24.engine.ProjectRepo projects, com.x7x24.engine.Engine engine, com.x7x24.llm.ModelRouter router, IntakeTools tools, PlanService plans, ChatActions actions, com.x7x24.engine.LineRepo lines) {
    var c = new ChatService(db, clk, cfg, audit, budget, projects, engine, router, tools); c.planHook = plans; tools.planner = plans; c.actions = actions; c.lineExists = x -> { var l = lines.get(x); return l != null && !"ARCHIVED".equals(l.state()); };
    tools.lineLister = () -> lines.list().stream().filter(l -> !"ARCHIVED".equals(l.state())).map(l -> { java.util.Map<String, Object> m = new java.util.LinkedHashMap<>(); m.put("id", l.id()); m.put("name", l.name()); m.put("state", l.state()); return m; }).toList();
    return c;
  }
}
