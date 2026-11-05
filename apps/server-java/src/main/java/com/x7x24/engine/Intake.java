package com.x7x24.engine;

import com.x7x24.adapters.DeliveryTools;
import com.x7x24.gov.KillSwitch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 信号来源：目标仓库 Issues（带 intakeLabel，或 intakeAll=true 时所有新 open issue）→ 需求池。幂等：sourceRef=gh:#N。 */
@Component
public class Intake {
  private static final Logger log = LoggerFactory.getLogger(Intake.class);
  private final Engine engine; private final ProjectRepo projects; private final DeliveryTools tools; private final KillSwitch kill; private final com.x7x24.gov.BudgetService budget;
  public Intake(Engine engine, ProjectRepo projects, DeliveryTools tools, KillSwitch kill, com.x7x24.gov.BudgetService budget) { this.budget = budget; this.engine = engine; this.projects = projects; this.tools = tools; this.kill = kill; }

  public int poll() {
    if (!tools.live()) return 0; int n = 0;
    for (var project : projects.list()) n += com.x7x24.tenant.Tenant.run(project.workspaceId(), () -> pollProject(project));
    return n;
  }
  private int pollProject(ProjectRepo.Project project) {
    int n = 0;
    {
      var l = projects.runtime(project.id());
      if (l.intakePausedUntil() != null && com.x7x24.util.Clk.parse(l.intakePausedUntil()) > System.currentTimeMillis()) return 0;
      if (l.cfg().repo == null) return 0; // 未绑定仓库：不拉 issue
      if (!l.cfg().intake || l.paused() != 0 || kill.isBlocked(l.productLineId(), l.id(), "write", null) != null) return 0;
      try { budget.assertAdmission(l.id()); } catch (IllegalArgumentException e) { return 0; }
      for (var i : tools.listSignalIssues(l.cfg())) {
        var is = new TaskCtx.Issue(); is.number = i.number(); is.url = i.url(); is.title = i.title(); is.author = i.author();
        var r = engine.ingest(new Engine.Ingest(l.productLineId(), i.title(), i.body() == null ? "" : i.body(), "github", "gh:#" + i.number(), is, false, null, l.id(), ""));
        if (!r.duplicate()) { n++; log.info("信号 Issue #{} → 任务 {}", i.number(), r.taskId()); }
      }
    }
    return n;
  }
}
