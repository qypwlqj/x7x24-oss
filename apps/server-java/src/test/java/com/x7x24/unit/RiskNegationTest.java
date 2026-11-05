package com.x7x24.unit;

import com.x7x24.gov.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RiskNegationTest {
  final Governance g = Governance.defaults();

  @Test void negatedHighKeywordDoesNotTriggerHigh() {
    var a = Risk.classify(g, "在lms中支持日语多语言切换，只改前端文案，不改后端接口与数据库", null);
    assertNotEquals("high", a.level()); assertTrue(a.negated().contains("数据库"), a.toString());
  }
  @Test void affirmedHighKeywordStillHigh() { assertEquals("high", Risk.classify(g, "新增数据库迁移脚本", null).level()); }
  @Test void negationOnlyInSameClause() { assertEquals("high", Risk.classify(g, "不改前端，需要改数据库表结构", null).level()); }
  @Test void englishAndAfterNegation() {
    assertNotEquals("high", Risk.classify(g, "Add ja locale without changes to database schema", null).level());
    assertNotEquals("high", Risk.classify(g, "所有改动在前端完成，数据库除外", null).level());
  }
  @Test void negatedForbiddenIsNotForbidden() { assertNotEquals("forbidden", Risk.classify(g, "日语切换，不涉及支付与退款", null).level()); }
  @Test void pathsArePrimary() {
    Map<String, String> rules = Map.of("frontend/src/", "mid", "database/migrations/", "high", "docs/", "low");
    java.util.function.Function<String, String> pr = f -> rules.entrySet().stream().filter(e -> f.startsWith(e.getKey())).map(Map.Entry::getValue).findFirst().orElse("mid");
    var a = Risk.classify(g, "支持日语切换，涉及数据库", null, pr, List.of("frontend/src/locales/", "frontend/src/i18n.ts"));
    assertEquals("mid", a.level(), "路径 mid，关键词 high 只能抬到 mid"); assertEquals("paths", a.basis()); assertEquals(2, a.matchedPaths().size());
    assertEquals("high", Risk.classify(g, "日语切换", null, pr, List.of("database/migrations/0003.sql")).level());
    assertEquals("low", Risk.classify(g, "更新文档", null, pr, List.of("docs/i18n.md")).level());
    assertTrue(Risk.classify(g, "x", null, pr, List.of("docs/a.md")).explain().containsKey("matchedPaths"));
  }
}
