package com.x7x24.it;

import com.x7x24.core.Status;
import com.x7x24.engine.Engine;
import com.x7x24.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.*;
import java.util.*;

/**
 * 真实 DeepSeek + lms 仓库快照（LMS_DIR 指向解压的仓库）：日语 i18n 需求在 20 文件 / 1500 行预算内通过拆段完成开发。
 * 写操作走内存版 GitHub，不会碰真实仓库。X7_LIVE_LLM=1 且设置 LMS_DIR 时才运行。
 */
@EnabledIfEnvironmentVariable(named = "LMS_DIR", matches = ".+")
class LiveSplitIT extends Harness {
  @Autowired com.x7x24.integration.StackDetectService stack; @Autowired com.x7x24.engine.ProjectRepo projects;
  @DynamicPropertySource static void llm(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> "jdbc:h2:mem:livesplit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY,VALUE;DB_CLOSE_DELAY=-1");
    r.add("x7x24.llm.base-url", () -> "https://api.deepseek.com"); r.add("x7x24.llm.api-key", () -> System.getenv("LLM_API_KEY").trim());
    r.add("x7x24.llm.model", () -> "deepseek-chat"); r.add("x7x24.llm.judge-models", () -> "deepseek-chat@0.2,deepseek-chat@0.7,deepseek-reasoner");
  }

  @Test void lmsJapaneseI18nCompletesWithinBudgetViaSplit() throws Exception {
    Path root = Paths.get(System.getenv("LMS_DIR"));
    try (var w = Files.walk(root)) { for (Path p : w.filter(Files::isRegularFile).toList()) { String rel = root.relativize(p).toString().replace('\\', '/'); if (Files.size(p) < 200_000) try { tools.main.put(rel, Files.readString(p)); } catch (Exception ignore) {} } }
    projects.update("sandbox-default", Json.tree("{\"config\":{\"technology\":" + Json.str(LivePrdIT.TECH + "，测试 `cd frontend && npm test`") + ",\"allowedPaths\":[\"backend/\",\"frontend/src/\",\"docs/\"],\"allowedExtensions\":[\"ts\",\"tsx\",\"json\",\"md\",\"go\",\"css\"],"
        + "\"modules\":[{\"name\":\"frontend\",\"path\":\"frontend/\",\"language\":\"TypeScript (React, Vite, Vitest)\",\"testCommand\":\"cd frontend && npm test\"},{\"name\":\"backend\",\"path\":\"backend/\",\"language\":\"Go\",\"testCommand\":\"cd backend && go test ./...\"}]}}"));
    var c = Governance(); c.release.maxFiles = Integer.parseInt(System.getenv().getOrDefault("LIVE_MAX_FILES", "20")); c.release.maxChangeLines = Integer.parseInt(System.getenv().getOrDefault("LIVE_MAX_LINES", "1500")); c.thresholds.priority = 0; c.thresholds.prdPass = 0.65; // 只验证拆段控规模；PRD 评审另见 LivePrdIT
    for (String k : List.of("**/locales/**/*.json", "**/i18n/**/*.json", "docs/**", "*.md")) c.release.sizeWeights.put(k, 1.0); // 与线上旧配置一致：不打折（最严）
    cfg.update(Json.M.valueToTree(c));
    var res = engine.ingest(new Engine.Ingest("sandbox", "lms 支持日语多语言切换", LivePrdIT.MD, "chat", "chat:split-" + System.nanoTime(), null, false, null, "sandbox-default", LivePrdIT.AC));
    System.out.println("LIVECFG after-update=" + cfg.get().release.maxChangeLines + " weights=" + cfg.get().release.sizeWeights);
    String id = res.taskId(); db.update("UPDATE task SET priority_score=0.9 WHERE id=?", id);
    var t = run(id, x -> Set.of(Status.QA, Status.ACCEPTANCE, Status.DELIVERED, Status.RELEASED, Status.DISCARDED).contains(x.st()) || x.st() == Status.ANOMALY && x.healCount >= 3, 60);
    for (var e : db.rows("SELECT event_type, to_status, payload FROM task_event WHERE task_id=? ORDER BY id", id))
      if (Set.of("STATUS_CHANGED", "SIZE_SPLIT", "SPLIT_PART_COMMITTED", "SELF_HEAL").contains(String.valueOf(e.get("event_type")))) System.out.println("LIVEEV " + e.get("event_type") + " " + e.get("to_status") + " " + com.x7x24.util.U.ellipsis(String.valueOf(e.get("payload")), 400));
    for (var e : db.rows("SELECT kind, content FROM evidence WHERE task_id=? AND kind IN ('plan','pr') ORDER BY id", id)) System.out.println("LIVE" + e.get("kind") + " " + com.x7x24.util.U.ellipsis(String.valueOf(e.get("content")), 1500));
    System.out.println("LIVERESULT status=" + t.status + " reason=" + t.discardReason + " detail=" + t.discardDetail + " pr=" + (t.context.pr == null ? null : t.context.pr.files.size() + " files / " + t.context.pr.weightedLines + " lines / parts " + t.context.pr.splitParts));
    org.junit.jupiter.api.Assertions.assertTrue(Set.of(Status.QA, Status.ACCEPTANCE, Status.DELIVERED, Status.RELEASED).contains(t.st()), "应在预算内完成开发进入 QA：" + t.status + " " + t.discardDetail);
    System.out.println("LIVEBUDGET iterations=" + t.spentIterations + " usd=" + t.spentUsd + " tokens=" + t.spentTokens + " parts=" + (t.plan == null ? 0 : t.plan.path("subtasks").size()));
    var rel = engine.rel(t); System.out.println("LIVECFG end=" + cfg.get().release.maxChangeLines + " rel=" + rel.maxChangeLines + " src=" + rel.limitSource);
    org.junit.jupiter.api.Assertions.assertTrue(Engine.prWithin(rel, t.context.pr), Engine.prSizeText(rel, t.context.pr));
  }
  static com.x7x24.gov.Governance Governance() { var d = com.x7x24.gov.Governance.defaults(); d.release.observeHours = 0.01; return d; }
}
