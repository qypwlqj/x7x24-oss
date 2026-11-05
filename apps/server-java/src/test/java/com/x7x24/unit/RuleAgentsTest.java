package com.x7x24.unit;

import com.x7x24.agents.Models;
import com.x7x24.agents.RuleAgents;
import com.x7x24.engine.*;
import com.x7x24.gov.ConfigStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 规则型本地 agent：必须产出真实的代码 + 测试 + 文档，而不是占位文件。 */
class RuleAgentsTest {
  final ConfigStore cfg = org.mockito.Mockito.mock(ConfigStore.class);
  final RuleAgents agents;
  RuleAgentsTest() { org.mockito.Mockito.when(cfg.get()).thenReturn(com.x7x24.gov.Governance.defaults()); agents = new RuleAgents(cfg); }

  Models.Ctx ctx(String title, String body, String ciLog) {
    var t = new Task(); t.id = "11111111-2222"; t.title = title; t.problem = body; t.productLine = "p"; t.riskLevel = "low";
    var is = new TaskCtx.Issue(); is.number = 1; t.context.issue = is; var l = new ProjectCfg(); l.id = "p"; l.repo = "o/r";
    return new Models.Ctx(t, l, is, (a, r) -> {}, org.mockito.Mockito.mock(com.x7x24.adapters.DeliveryTools.class), null, null, ciLog, false);
  }

  @Test void utilFunctionProducesCodeTestAndDocs() {
    var c = ctx("新增 slugify 工具函数及测试", "示例：slugify(\"Hello World\") => \"hello-world\"", null);
    var prd = agents.draftPrd(c); assertEquals("util-function", prd.template()); assertEquals("slugify", prd.functionName());
    var plan = agents.plan(c.with(prd, null));
    var cc = agents.code(c.with(prd, plan));
    var paths = cc.files().stream().map(f -> f.path()).toList();
    assertTrue(paths.contains("src/slugify.js")); assertTrue(paths.contains("tests/slugify.test.js")); assertTrue(paths.contains("docs/slugify.md"));
    var test = cc.files().stream().filter(f -> f.path().startsWith("tests/")).findFirst().get().content();
    assertTrue(test.contains("hello-world"), "测试应包含 Issue 中给出的示例");
    assertTrue(cc.files().stream().filter(f -> f.path().startsWith("src/")).findFirst().get().content().contains("module.exports"));
  }
  @Test void readmeUpdate() {
    var c = ctx("更新 README 使用说明", "说明 src/ 与 tests/", null);
    var prd = agents.draftPrd(c); assertEquals("readme-update", prd.template());
    var cc = agents.code(c.with(prd, agents.plan(c.with(prd, null))));
    assertEquals(List.of("README.md"), cc.files().stream().map(f -> f.path()).toList());
  }
  @Test void naiveDirectiveThenRepairAfterCiLog() {
    var c = ctx("新增 clamp 工具函数及测试", "示例：clamp(-1, 0, 10) => 0\nx7x24: first-attempt=naive", null);
    var prd = agents.draftPrd(c); assertTrue(prd.naiveFirst());
    var first = agents.code(c.with(prd, agents.plan(c.with(prd, null))));
    assertEquals("naive", first.variant());
    var c2 = ctx("新增 clamp 工具函数及测试", "示例：clamp(-1, 0, 10) => 0\nx7x24: first-attempt=naive", "not ok 1 - clamp(-1,0,10)");
    c2.task().loopCount = 1;
    var second = agents.code(c2.with(prd, agents.plan(c2.with(prd, null))));
    assertEquals("robust", second.variant());
  }
  @Test void unsupportedRequirementIsRejectedByRuleJudges() {
    var c = ctx("重写整个推荐系统", "用深度学习重做", null);
    assertEquals("generic", agents.draftPrd(c).template());
  }
}
