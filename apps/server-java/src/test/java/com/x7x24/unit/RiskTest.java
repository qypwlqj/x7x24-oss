package com.x7x24.unit;

import com.x7x24.gov.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RiskTest {
  final Governance g = Governance.defaults();

  @Test void docsAreLow() { assertEquals("low", Risk.classify(g, "更新 README 使用说明", null).level()); }
  @Test void paymentIsForbidden() {
    var a = Risk.classify(g, "调整支付退款配置", null);
    assertEquals("forbidden", a.level()); assertTrue(a.forbiddenCategories().contains("payment"));
  }
  @Test void workflowFileIsForbidden() {
    assertEquals("forbidden", Risk.classify(g, "修改 .github/workflows/ci.yml 增加部署步骤", null).level());
  }
  @Test void governanceFilePathIsHit() {
    var a = Risk.classify(g, "普通改动", List.of("src/a.js", ".github/workflows/ci.yml"));
    assertFalse(a.governanceFileHits().isEmpty());
  }
  @Test void shortKeywordDoesNotMatchInsideWords() { assertNotEquals("forbidden", Risk.classify(g, "Williams 的文档", null).level()); }
  @Test void highKeywords() { assertEquals("high", Risk.classify(g, "数据库 schema 迁移", null).level()); }
  @Test void autonomyLevels() {
    assertTrue(Risk.autonomyAllows("L1", "low")); assertFalse(Risk.autonomyAllows("L1", "high"));
    assertTrue(Risk.autonomyAllows("L3", "high")); assertFalse(Risk.autonomyAllows("L3", "forbidden"));
  }
  @Test void forbiddenActionsAreDenied() {
    assertFalse(Risk.evaluateAction(g, "payment.refund", "order-1").allowed());
    assertFalse(Risk.evaluateAction(g, "git.push", "main").allowed());
    assertFalse(Risk.evaluateAction(g, "git.force_push", "agent/x").allowed());
    assertFalse(Risk.evaluateAction(g, "net.egress", "evil.example.org").allowed());
    assertTrue(Risk.evaluateAction(g, "net.egress", "api.github.com").allowed());
    assertTrue(Risk.evaluateAction(g, "git.push", "agent/issue-1").allowed());
    assertTrue(Risk.evaluateAction(g, "git.initialize_repository", "main").allowed());
    assertFalse(Risk.evaluateAction(g, "git.initialize_repository", "").allowed());
  }

  // ── 日语多语言需求被「数据库」误判高风险 ──
  static final String JA_PRD = "## 目标\n为前端新增日语多语言\n\n## 范围\n- 新增 ja 语言包\n- 语言切换下拉框增加日语\n\n## 不包含\n- 数据库与后端接口改动\n- 数据迁移\n\n## 验收标准\n1. 切到日语后登录页显示日语\n\n## 评估\n- 风险：low\n- 类型：database\n";
  @Test void outOfScopeSectionIsIgnored() {
    var a = Risk.classify(g, "新增日语多语言 " + JA_PRD, null);
    assertNotEquals("high", a.level(), a.explain().toString()); assertFalse(a.matched().contains("数据库"));
    assertFalse(Risk.scopeText(JA_PRD).contains("数据库")); assertTrue(Risk.scopeText(JA_PRD).contains("ja 语言包"));
  }
  @Test void scopeSectionStillCounts() { assertEquals("high", Risk.classify(g, "## 范围\n- 用户表新增数据库字段\n## 不包含\n- 前端", null).level()); }
  @Test void moreNegations() {
    assertNotEquals("high", Risk.classify(g, "新增日语翻译，不新增数据库字段", null).level());
    assertNotEquals("high", Risk.classify(g, "新增日语翻译，数据库无改动", null).level());
  }
  @Test void localeAndDocFilesAreLowByDefault() {
    var c = new com.x7x24.engine.ProjectCfg();
    assertEquals("low", c.riskOf("frontend/src/locales/ja/login.json")); assertEquals("low", c.riskOf("frontend/src/locales/ja/i18n.test.ts"));
    assertEquals("low", c.riskOf("backend/i18n/ja.yaml")); assertEquals("low", c.riskOf("docs-site/guide.md"));
    assertEquals("mid", c.riskOf("backend/db/migrations/001.sql")); assertEquals("mid", c.riskOf("backend/handler/user.go"));
    assertEquals("mid", c.riskOf("src/locales/ja.json"), "显式路径规则优先（src/ → mid）");
    assertEquals("forbidden", c.riskOf(".github/locales/x.json"));
  }
  @Test void pathsWinOverKeywordsForLocaleWork() {
    var c = new com.x7x24.engine.ProjectCfg();
    var a = Risk.classify(g, "日语多语言，顺带核对数据库里的语言字段", null, c::riskOf, List.of("frontend/src/locales/ja/login.json", "frontend/src/locales/ja/common.json"));
    assertEquals("paths", a.basis()); assertEquals("mid", a.level(), "关键词最多抬到降一档");
    assertEquals("low", Risk.classify(g, "新增日语多语言 " + JA_PRD, null, c::riskOf, List.of("frontend/src/locales/ja/login.json")).level());
  }
}
