package com.x7x24.unit;

import com.x7x24.adapters.GitHubTools;
import com.x7x24.engine.CiFailure;
import com.x7x24.engine.ProjectCfg;
import com.x7x24.it.FakeTools;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CiFailureTest {
  @Test void npmSetupFailureWithoutRootPackageJsonIsSetupNotTest() {
    var i = CiFailure.classify(FakeTools.SETUP_LOG.replace("#RUN#", "38709439542"));
    assertTrue(i.setup(), i.toString()); assertEquals(List.of("Install"), i.failedSteps());
    assertTrue(i.headline().contains("package.json"), i.headline());
  }
  @Test void realTestFailureIsTestAndExtractsFiles() {
    var i = CiFailure.classify("[ci] 步骤失败：Test\nFAIL src/i18n/ja.test.ts > has all keys\nAssertionError: expected 'x' to be 'y'\n at frontend/src/locales/ja.json:3");
    assertEquals("test", i.kind()); assertTrue(i.files().contains("src/i18n/ja.test.ts"), i.files().toString());
  }
  @Test void signatureIgnoresRunIdsTimingsAndShas() {
    String a = CiFailure.signature("run #111 failure\n[ci] 步骤失败：Test\nnot ok 1 - clamp (12ms)\n# commit abcdef1234567");
    String b = CiFailure.signature("run #222 failure\n[ci] 步骤失败：Test\nnot ok 1 - clamp (40ms)\n# commit 9876543fedcba");
    assertEquals(a, b);
    assertNotEquals(a, CiFailure.signature("[ci] 步骤失败：Test\nnot ok 2 - other"));
  }
  @Test void completeRepairContextRetainsEveryFailureAndLongMultilineDiff() {
    String errors = java.util.stream.IntStream.rangeClosed(1, 40).mapToObj(i ->
        "FAIL src/case" + i + ".test.ts > case " + i + "\nAssertionError: expected " + i + " to be 100\n ❯ src/case" + i + ".test.ts:42:8\n").collect(java.util.stream.Collectors.joining("\n"));
    String diff = java.util.stream.IntStream.rangeClosed(1, 100).mapToObj(i -> "- old line " + i + "\n+ new line " + i).collect(java.util.stream.Collectors.joining("\n"));
    String log = errors + diff;
    assertEquals(log, com.x7x24.agents.CiLogSummarizer.forRepair(log));
    var report = CiFailure.report(log);
    assertEquals(40, report.failures().size());
    assertEquals(40, report.summary().files().size());
    var last = report.failures().getLast();
    assertEquals("src/case40.test.ts", last.file()); assertEquals(42, last.line());
    assertTrue(last.test().contains("case 40")); assertTrue(last.detail().endsWith("+ new line 100"));
    assertNotEquals(CiFailure.signature(log), CiFailure.signature(log.replace("expected 40 to be 100", "expected 40 to be 200")));
  }
  @Test void typedErrorsAndAnsiDecoratedVitestFailuresKeepTheirLocations() {
    String log = "2026-10-31T10:00:00.000Z src/url.ts(12,3): error TS2322: Type 'number' is not assignable to type 'string'.\n"
        + "\u001b[31mFAIL /home/runner/work/repo/repo/src/url.test.ts > normalize > ftp\u001b[39m\n"
        + "AssertionError: expected 'ftp://example.com/a' to be ''\n ❯ src/url.test.ts:42:8\n";
    var report = CiFailure.report(log);
    assertEquals(2, report.failures().size());
    assertEquals(List.of("src/url.ts", "src/url.test.ts"), report.summary().files());
    assertEquals(12, report.failures().getFirst().line()); assertEquals(42, report.failures().getLast().line());
    assertTrue(report.failures().getFirst().message().contains("TS2322"));
    assertFalse(com.x7x24.agents.CiLogSummarizer.forRepair(log).contains("\u001b["));
    assertEquals(CiFailure.signature(log), CiFailure.signature(CiFailure.cleanLog(log)));
  }
  @Test void ciTemplateRunsEachModuleInItsOwnDirectory() {
    var p = new ProjectCfg(); p.baseBranch = "main"; p.workflow = "ci.yml";
    var fe = new ProjectCfg.Module(); fe.name = "frontend"; fe.path = "frontend"; fe.language = "TypeScript"; fe.testCommand = "cd frontend && npm test";
    var be = new ProjectCfg.Module(); be.name = "backend"; be.path = "backend"; be.language = "Go"; be.testCommand = "";
    p.modules = List.of(fe, be);
    String y = GitHubTools.ciTemplate(p);
    assertTrue(y.contains("working-directory: frontend") && y.contains("working-directory: backend"), y);
    assertTrue(y.contains("run: npm test\n") && y.contains("go test ./...") && y.contains("go-version-file: backend/go.mod"), y);
    assertTrue(y.contains("workflow_dispatch"));
    assertFalse(y.contains("--if-present"), "缺少测试脚本必须失败，不能静默跳过");
    String old = "# 由 7x24 的集成初始化生成：通过 PR 提交，需要你审阅后手动合并。\nname: CI\njobs:\n  test:\n    steps:\n      - run: npm ci --if-present || npm install --if-present\n";
    assertTrue(GitHubTools.outdatedTemplate(p, old), "旧模板需要更新");
    assertFalse(GitHubTools.outdatedTemplate(p, y)); assertFalse(GitHubTools.outdatedTemplate(p, "name: my own ci"), "不是 7x24 生成的不动");
  }
}
