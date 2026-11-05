package com.x7x24.execution.agent;

import com.x7x24.agents.Models;
import com.x7x24.engine.ProjectCfg;
import com.x7x24.engine.Task;
import com.x7x24.engine.TaskCtx;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DevModeTest {
  static Task task(String override) { var t = new Task(); t.context = new TaskCtx(); t.context.devModeOverride = override; return t; }
  static ProjectCfg project(String mode) { var p = new ProjectCfg(); p.devMode = mode; return p; }

  @Test void taskOverridesProjectWhichOverridesDefault() {
    assertEquals(new DevMode.Choice("legacy", "task"), DevMode.resolve(task("legacy"), project("sandbox")));
    assertEquals(new DevMode.Choice("legacy", "project"), DevMode.resolve(task(null), project("legacy")));
    assertEquals(new DevMode.Choice("sandbox", "default"), DevMode.resolve(task(null), project(null)), "旧数据没有字段时按沙盒");
    assertEquals(new DevMode.Choice("sandbox", "default"), DevMode.resolve(task("weird"), project("weird")));
  }
  @Test void oldProjectJsonWithoutDevModeReadsAsSandbox() {
    var p = com.x7x24.util.Json.convert(com.x7x24.util.Json.tree("{\"id\":\"p\",\"name\":\"n\"}"), ProjectCfg.class);
    assertEquals("sandbox", p.devMode);
    var c = com.x7x24.util.Json.convert(com.x7x24.util.Json.tree("{\"recoveryId\":\"r\"}"), TaskCtx.class);
    assertNull(c.devModeOverride);
  }
  @Test void normalizeAcceptsInheritAndRejectsUnknown() {
    assertNull(DevMode.normalize(null)); assertNull(DevMode.normalize("inherit")); assertEquals("legacy", DevMode.normalize("legacy"));
    assertThrows(IllegalArgumentException.class, () -> DevMode.normalize("legacy-json"));
  }
  @Test void largeRequirementOnlyHintsForLegacy() {
    var many = new ArrayList<String>(); for (int i = 0; i < 11; i++) many.add("src/f" + i + ".js");
    var big = new Models.Plan(List.of("DEV"), "t", "s", 0, "low", many, List.of(), Map.of());
    var segmented = new Models.Plan(List.of("DEV"), "t", "s", 0, "low", List.of("a"), List.of(Map.of(), Map.of()), Map.of());
    var small = new Models.Plan(List.of("DEV"), "t", "s", 0, "low", List.of("a"), List.of(), Map.of());
    assertEquals("这个需求可能超出快速方式能力，建议改用沙盒", DevMode.largeHint("legacy", big));
    assertNotNull(DevMode.largeHint("legacy", segmented));
    assertNull(DevMode.largeHint("legacy", small)); assertNull(DevMode.largeHint("sandbox", big));
  }
  @Test void onlyNetworkFailuresAreRetryable() {
    assertTrue(SandboxFailure.retryable(new AgentJobService.JobFailed("FAILED", "clone 失败（已重试 3 次）：timeout")));
    assertTrue(SandboxFailure.retryable(new AgentJobService.JobFailed("FAILED", "runner 启动失败：connect refused")));
    assertFalse(SandboxFailure.retryable(new AgentJobService.JobFailed("FAILED", "agent 用完轮数上限（61 轮，error_max_turns）")));
    assertFalse(SandboxFailure.retryable(new AgentJobService.JobFailed("UNCONFIGURED", "沙盒未配置")));
    assertFalse(SandboxFailure.retryable(new AgentJobService.JobFailed("TIMED_OUT", "clone 失败")));
  }
}
