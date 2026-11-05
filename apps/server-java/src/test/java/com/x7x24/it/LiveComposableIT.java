package com.x7x24.it;

import com.x7x24.core.Status;
import com.x7x24.engine.Engine;
import com.x7x24.pipeline.Assembler;
import com.x7x24.pipeline.PipelineService;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.*;
import java.util.*;

/**
 * 可组合流程端到端（真实 DeepSeek + lms 仓库快照）：前后端 + 数据迁移需求在工作区开启 pipeline.v2 后组装出
 * 接口契约设计 / 数据库变更设计 / SQL 安全检查 / 安全审计等节点，并真实执行到安全审计。写操作走内存版 GitHub。
 * 设置 LMS_DIR 与 LIVE_COMPOSABLE=1 时才运行。
 */
@EnabledIfEnvironmentVariable(named = "LIVE_COMPOSABLE", matches = "1")
class LiveComposableIT extends Harness {
  @Autowired com.x7x24.engine.ProjectRepo projects; @Autowired com.x7x24.chat.PlanService plans; @Autowired com.x7x24.integration.RuntimeSettings settings; @Autowired PipelineService pipes;
  @DynamicPropertySource static void llm(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> "jdbc:h2:file:/tmp/composable-live/x7x24;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY,VALUE;DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE");
    r.add("x7x24.llm.base-url", () -> "https://api.deepseek.com"); r.add("x7x24.llm.api-key", () -> System.getenv("LLM_API_KEY").trim());
    r.add("x7x24.llm.model", () -> "deepseek-chat"); r.add("x7x24.llm.judge-models", () -> "deepseek-chat@0.2,deepseek-chat@0.7,deepseek-reasoner");
  }

  @Test void lmsFullstackMigrationRunsAssembledPipelineWithSecAudit() throws Exception {
    Path root = Paths.get(System.getenv("LMS_DIR"));
    try (var w = Files.walk(root)) { for (Path p : w.filter(Files::isRegularFile).toList()) { String rel = root.relativize(p).toString().replace('\\', '/'); if (rel.contains("node_modules/") || rel.startsWith(".git/")) continue; if (Files.size(p) < 200_000) try { tools.main.put(rel, Files.readString(p)); } catch (Exception ignore) {} } }
    projects.update("sandbox-default", Json.tree("{\"config\":{\"autonomy\":\"L3\",\"technology\":" + Json.str(LivePrdIT.TECH + "；数据库迁移在 database/migrations/（按序号命名的 .sql）") + ",\"allowedPaths\":[\"backend/\",\"frontend/src/\",\"database/\",\"docs/\"],\"allowedExtensions\":[\"ts\",\"tsx\",\"json\",\"md\",\"go\",\"css\",\"sql\"],"
        + "\"modules\":[{\"name\":\"frontend\",\"path\":\"frontend/\",\"language\":\"TypeScript (React, Vite, Vitest)\",\"testCommand\":\"cd frontend && npm test\"},{\"name\":\"backend\",\"path\":\"backend/\",\"language\":\"Go\",\"testCommand\":\"cd backend && go test ./...\"},{\"name\":\"database\",\"path\":\"database/\",\"language\":\"SQL (MySQL)\",\"testCommand\":\"\"}]}}"));
    var c = com.x7x24.gov.Governance.defaults(); c.release.observeHours = 0.01; c.thresholds.priority = 0; c.thresholds.prdPass = 0.6; c.release.maxFiles = 20; c.release.maxChangeLines = 1500;
    c.autonomy.tightenDiscards = 10000; c.autonomy.tightenRollbacks = 10000; c.autonomy.globalRollbackKill = 10000;
    cfg.update(Json.M.valueToTree(c));
    lines.update("sandbox", Json.tree("{\"autonomy\":\"L3\"}")); // 涉及鉴权的需求规划器会判为高风险；端到端验证需要 L3 才会自动执行
    Tenant.run(Tenant.LEGACY, () -> settings.setPipelineV2(true));
    try {
      var d = new com.x7x24.chat.Draft(); d.projectId = "sandbox-default"; d.title = "仪器校准提醒"; d.risk = "medium"; d.size = "M";
      d.goal = "实验室管理员可以为仪器登记校准记录，并在仪器详情页看到下次校准日期与是否逾期";
      d.targetPaths = new ArrayList<>(List.of("database/migrations/015_instrument_calibrations.sql", "backend/internal/handlers/instrument_calibration.go", "backend/internal/models/instrument_calibration.go", "frontend/src/pages/InstrumentCalibrations.tsx"));
      String body = """
          ## 目标
          实验室管理员可以为仪器登记校准记录，并在校准记录页看到每台仪器的下次校准日期与是否逾期，避免仪器超期使用。

          ## 范围
          - 数据库：新增迁移 database/migrations/015_instrument_calibrations.sql，创建表 instrument_calibrations（id、instrument_id、calibrated_at、next_due_at、operator、note、created_at），只新增表，不修改、不删除已有表
          - 后端（Go + Gin + GORM）：新增模型 InstrumentCalibration；新增接口 GET /api/instruments/:id/calibrations（登录用户可查）与 POST /api/instruments/:id/calibrations（仅管理员角色，其余返回 403）
          - 前端（React + Vite）：新增校准记录页面，列出某台仪器的校准记录，next_due_at 早于今天的记录高亮为逾期，并提供新增一条记录的表单

          ## 不包含
          - 不做邮件 / 短信提醒
          - 不修改现有仪器表结构

          ## 验收标准
          1. 迁移脚本只包含 CREATE TABLE instrument_calibrations，不含 DROP / ALTER 已有表
          2. 未登录调用 GET 接口返回 401；非管理员调用 POST 返回 403；管理员 POST 后 GET 能查到新记录
          3. 页面上 next_due_at 早于今天的记录显示「已逾期」标记
          4. `cd backend && go test ./...` 与 `cd frontend && npm test` 通过，新增至少 2 个测试

          ## 评估
          - 风险：medium
          - 规模：M
          - 类型：feature

          _来源：需求对话_
          """;
      d.scope = new ArrayList<>(List.of("新增 instrument_calibrations 表（迁移 015，只新增表）", "Go 后端 GET / POST /api/instruments/:id/calibrations，POST 仅管理员", "前端校准记录页面，高亮逾期记录"));
      var pinned = Tenant.run(Tenant.LEGACY, () -> plans.pin(d, "sandbox", "sandbox-default"));
      var e = Tenant.run(Tenant.LEGACY, () -> pipes.snapshot(pinned.snapshotId(), null));
      var g = Assembler.graphOf(e);
      System.out.println("LIVEGRAPH schema=" + e.schema + " labels=" + e.plan.labels + " stacks=" + e.plan.stacks + " mandatory=" + e.plan.mandatory.keySet());
      for (var n : g.nodes) System.out.println("LIVENODE " + n.id + " | " + n.label + " | " + n.source + (n.parent != null ? " | parent=" + n.parent : "") + (n.mandatory != null ? " | 必选:" + n.mandatory : "") + (Boolean.TRUE.equals(n.optional) ? " | optional" : ""));
      var res = engine.ingest(new Engine.Ingest("sandbox", d.title, body, "chat", "chat:composable-" + System.nanoTime(), null, false, null, "sandbox-default", "1. 迁移脚本 015_instrument_calibrations.sql 只包含 CREATE TABLE instrument_calibrations，不含 DROP / ALTER 已有表\n2. 未登录调用 GET /api/instruments/1/calibrations 返回 401；非管理员调用 POST 返回 403；管理员 POST 后 GET 返回的列表包含新记录\n3. 页面上 next_due_at 早于今天的记录显示「已逾期」标记\n4. `cd backend && go test ./...` 与 `cd frontend && npm test` 通过，新增至少 2 个测试", pinned.snapshotId()));
      String id = res.taskId(); db.update("UPDATE task SET priority_score=0.9 WHERE id=?", id);
      var seen = new LinkedHashSet<String>();
      var t = run(id, x -> { if (x.currentNode != null) seen.add(x.currentNode); return Set.of(Status.DELIVERED, Status.RELEASED, Status.DISCARDED, Status.DEGRADED).contains(x.st()) || x.st() == Status.ANOMALY && x.healCount >= 3; }, Integer.parseInt(System.getenv().getOrDefault("LIVE_TICKS", "160")));
      System.out.println("LIVEPATH " + String.join(" → ", seen));
      System.out.println("LIVERESULT status=" + t.status + " node=" + t.currentNode + " version=" + t.pipelineVersion + " reason=" + t.discardReason + " detail=" + t.discardDetail + " failures=" + com.x7x24.util.U.ellipsis(String.valueOf(t.context.failures), 800) + " usd=" + t.spentUsd + " pr=" + (t.context.pr == null ? null : t.context.pr.files.size() + " files"));
      for (var r : db.rows("SELECT stage, state, input, usd, tokens FROM stage_execution WHERE task_id=? ORDER BY started_at, id", id))
        System.out.println("LIVEEXEC " + r.get("stage") + " node=" + Json.tree(String.valueOf(r.get("input"))).path("node").asText("-") + " " + r.get("state") + " $" + r.get("usd") + " tok=" + r.get("tokens"));
      for (var r : db.rows("SELECT gate, decision, aggregate_score, votes FROM verdict WHERE task_id=? ORDER BY created_at, id", id))
        System.out.println("LIVEVERDICT " + r.get("gate") + " " + r.get("decision") + " " + r.get("aggregate_score") + " " + com.x7x24.util.U.ellipsis(String.valueOf(r.get("votes")), 600));
      for (var r : db.rows("SELECT kind, stage, content FROM evidence WHERE task_id=? AND (stage='SEC_AUDIT' OR kind IN ('stage_checks','db_schema','api_design')) ORDER BY id", id))
        System.out.println("LIVEEVID " + r.get("stage") + " " + r.get("kind") + " " + com.x7x24.util.U.ellipsis(String.valueOf(r.get("content")), 1200));
      System.out.println("LIVETASK " + id);
      org.junit.jupiter.api.Assertions.assertEquals("v2", t.pipelineVersion);
      org.junit.jupiter.api.Assertions.assertTrue(seen.contains("SEC_AUDIT"), "安全审计应真实执行：" + seen);
    } finally { Tenant.run(Tenant.LEGACY, () -> settings.setPipelineV2(false)); }
  }
}
