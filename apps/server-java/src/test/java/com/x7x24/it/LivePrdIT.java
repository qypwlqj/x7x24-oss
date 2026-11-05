package com.x7x24.it;

import com.x7x24.core.Status;
import com.x7x24.engine.Engine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实 DeepSeek 端到端：对话确认产生的 lms 任务能否通过 PRD 评审（X7_LIVE_LLM=1 时才运行）。 */
@EnabledIfEnvironmentVariable(named = "X7_LIVE_LLM", matches = "1")
class LivePrdIT extends Harness {
  @DynamicPropertySource static void llm(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> "jdbc:h2:mem:liveprd;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY,VALUE;DB_CLOSE_DELAY=-1");
    r.add("x7x24.llm.base-url", () -> "https://api.deepseek.com"); r.add("x7x24.llm.api-key", () -> System.getenv("LLM_API_KEY").trim());
    r.add("x7x24.llm.model", () -> "deepseek-chat"); r.add("x7x24.llm.judge-models", () -> "deepseek-chat@0.2,deepseek-chat@0.7,deepseek-reasoner");
  }
  public static final String TECH = "前后端分离全栈仓库：backend/：Go 1.23 + Gin + GORM + MySQL；frontend/：TypeScript + React + Vite + Tailwind + Vitest";
  public static final String MD = """
      ## 目标
      为 lms 前端增加日语（ja）界面语言，用户可在中文 / 英文 / 日语之间切换，选择会被记住。

      ## 范围
      - 前端 frontend/src 的 i18n 配置新增 ja 语言包，覆盖现有全部界面文案键
      - 顶部导航的语言切换下拉框新增「日本語」选项
      - 语言选择保存到 localStorage，刷新后保持

      ## 不包含
      - 不改后端接口与数据库
      - 不翻译用户生成的课程内容

      ## 验收标准
      1. ja 语言包的键数量与 zh-CN 语言包完全一致（缺失键为 0）
      2. 在语言下拉框选择「日本語」后，导航栏「课程」显示为「コース」
      3. 刷新页面后仍保持日语界面（localStorage 中 lang=ja）
      4. 未登录访问 /admin 时仍返回登录页，行为与切换前一致
      5. `cd frontend && npm test` 全部通过，新增至少 2 个语言切换相关测试

      ## 评估
      - 风险：low
      - 规模：M
      - 类型：feature

      _来源：需求对话_
      """;
  public static final String AC = "1. ja 语言包的键数量与 zh-CN 语言包完全一致（缺失键为 0）\n2. 在语言下拉框选择「日本語」后，导航栏「课程」显示为「コース」\n3. 刷新页面后仍保持日语界面（localStorage 中 lang=ja）\n4. 未登录访问 /admin 时仍返回登录页，行为与切换前一致\n5. `cd frontend && npm test` 全部通过，新增至少 2 个语言切换相关测试";

  @Test void chatCreatedLmsTaskPassesPrdReview() {
    db.update("UPDATE project SET config=REPLACE(config, '\"technology\":\"未识别（请点「自动识别」）\"', ?) WHERE id='sandbox-default'", "\"technology\":\"" + TECH + "\"");
    lines.setAutonomy("sandbox", "L2");
    var res = engine.ingest(new Engine.Ingest("sandbox", "lms 支持日语多语言切换", MD, "chat", "chat:live-" + System.nanoTime(), null, false, null, "sandbox-default", AC));
    String id = res.taskId(); db.update("UPDATE task SET priority_score=0.9 WHERE id=?", id);
    var t = run(id, x -> x.st() != Status.BACKLOG && x.st() != Status.PRD_REVIEW, 12);
    for (var v : db.rows("SELECT * FROM verdict WHERE task_id=? ORDER BY id", id)) System.out.println("LIVEVERDICT " + v.get("decision") + " agg=" + v.get("aggregate") + " rules=" + v.get("rules") + " votes=" + v.get("votes") + " meta=" + v.get("meta"));
    for (var e : db.rows("SELECT content FROM evidence WHERE task_id=? AND kind='prd' ORDER BY id", id)) System.out.println("LIVEPRD " + e.get("content"));
    System.out.println("LIVERESULT status=" + t.status + " reason=" + t.discardReason + " detail=" + t.discardDetail);
    org.junit.jupiter.api.Assertions.assertEquals(Status.PLANNING, t.st(), "应通过 PRD 评审进入规划：" + t.discardReason + " " + t.discardDetail);
  }
}
