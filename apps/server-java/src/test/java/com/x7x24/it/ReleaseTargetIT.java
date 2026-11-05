package com.x7x24.it;

import com.x7x24.core.Status;
import com.x7x24.engine.*;
import com.x7x24.util.Json;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real orchestration and persistence with fake GitHub; never uploads or publishes remotely. */
class ReleaseTargetIT extends Harness {
  @org.springframework.test.context.DynamicPropertySource static void isolated(org.springframework.test.context.DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> "jdbc:h2:mem:release-target;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY,VALUE;DB_CLOSE_DELAY=-1");
  }
  @Autowired ProjectRepo projects;
  @Autowired com.x7x24.chat.TaskActions actions;
  @Autowired com.x7x24.web.NodeBoard board;
  @LocalServerPort int port;
  @BeforeEach void resetTarget() {
    // Each scenario starts with the same fixture; prior delivery history must not trigger semantic deduplication.
    db.update("UPDATE task SET deleted_at=?,next_run_at=NULL WHERE project_id='sandbox-default'", clk.iso());
    cfg.update(Json.tree("{\"autonomy\":{\"tightenDiscards\":10000,\"tightenRollbacks\":10000,\"globalRollbackKill\":10000}}"));
    configure("github_release", null);
  }
  void configure(String target, String workflow) {
    var patch = Json.M.createObjectNode(); var config = patch.putObject("config");
    config.put("releaseTarget", target); config.put("deployWorkflow", workflow);
    projects.update("sandbox-default", patch);
  }
  String task() { return ingestIssue("新增 slugify 工具函数及测试", "新增 src/slugify.js，slugify(Hello World) => hello-world"); }
  Task stopped(String id) { return run(id, RecoveryService::stopped, 200); }
  void assertConfigurationWait(Task task) {
    assertEquals("WAITING_CONFIGURATION", task.executionState, task.status + " " + engine.recoveryInfo(task.id));
    assertNull(task.nextRunAt); assertEquals(0, task.feedbackDone);
    assertEquals(ReleasePolicy.WAIT_CODE, engine.recoveryInfo(task.id).get("code"));
    assertFalse(tools.closed.contains(issueNo(task.id)));
    assertEquals("WAITING_CONFIGURATION", ReleasePolicy.describe(task, projects.forTask(task).cfg()).deployment());
    var nodes = Json.M.valueToTree(board.board(null, task.projectId, null, 7)).path("modules").path("delivery");
    boolean visible = false;
    for (var node : nodes) if (node.path("node").asText().equals("RELEASE")) visible = node.path("current").asInt() > 0 && node.path("blocked").asInt() > 0;
    assertTrue(visible, "待配置任务必须保留在发布节点，包括已创建仓库版本的任务");
  }
  @Test void unconfiguredWechatStopsBeforeMergeAndDoesNotRetryOrCloseIssue() throws Exception {
    configure("wechat_miniprogram", null);
    String id = task(); Task blocked = stopped(id);
    assertEquals(Status.DELIVERED, blocked.st()); assertConfigurationWait(blocked);
    assertTrue(tools.merged.isEmpty()); assertTrue(tools.releases.isEmpty());
    var state = actions.blocked(id);
    assertNotNull(state.get("resumeBlocker")); assertTrue(state.get("guide").toString().contains("/p/sandbox-default/detail"));
    assertThrows(IllegalArgumentException.class, () -> engine.resumeTask(id, blocked.version, "fixture"));
    for (int n = 0; n < 3; n++) { clk.advance(120000); engine.tick(); }
    assertEquals(blocked.version, repo.get(id).version);
    var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/tasks/" + id)).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode()); var dto = Json.tree(response.body()).path("task");
    assertEquals("WAITING_CONFIGURATION", dto.path("executionState").asText());
    assertEquals("NOT_CREATED", dto.path("delivery").path("githubRelease").asText());
    assertEquals("WAITING_CONFIGURATION", dto.path("delivery").path("deployment").asText());
  }
  @Test void namingAWorkflowDoesNotPretendTheWechatAdapterExists() {
    configure("wechat_miniprogram", "wechat-release.yml");
    Task blocked = stopped(task()); assertConfigurationWait(blocked);
    assertTrue(tools.merged.isEmpty()); assertTrue(tools.releases.isEmpty());
    assertTrue(engine.releaseCapability(blocked).detail().contains("仅填写工作流名称"));
  }
  @Test void changingTargetThenResumingPreservesProgressAndOnlyPublishesARepositoryVersion() {
    configure("wechat_miniprogram", null); String id = task(); Task blocked = stopped(id);
    assertConfigurationWait(blocked);
    var pr = blocked.context.pr.number; var plan = blocked.plan; var iteration = blocked.currentIteration;
    configure("github_release", null);
    assertNull(actions.blocked(id).get("resumeBlocker"));
    var resumed = engine.resumeTask(id, blocked.version, "fixture");
    assertEquals(pr, resumed.context.pr.number); assertEquals(plan, resumed.plan); assertEquals(iteration, resumed.currentIteration);
    Task done = run(id, t -> t.st() == Status.RELEASED && t.feedbackDone == 1, 100);
    assertEquals("COMPLETED", done.executionState); assertEquals(1, done.feedbackDone);
    var delivery = ReleasePolicy.describe(done, projects.forTask(done).cfg());
    assertEquals("VERIFIED", delivery.githubRelease()); assertEquals("NOT_REQUESTED", delivery.deployment());
    assertEquals("github_release", done.release.get("target")); assertEquals("NOT_REQUESTED", done.release.get("deploymentStatus"));
    assertEquals(1, tools.merged.size()); assertEquals(1, tools.releases.size());
    assertTrue(tools.comments.toString().contains("GitHub Release 已创建"));
    assertFalse(tools.comments.toString().contains("**已上线**"));
    assertTrue(tools.closed.contains(issueNo(id)));
  }
  @Test void currentTargetCannotBeBypassedByAnOldTaskSnapshot() {
    String id = task(); assertEquals(Status.DELIVERED, runTo(id, Status.DELIVERED).st());
    assertEquals("github_release", repo.get(id).context.projectSnapshot.releaseTarget);
    configure("wechat_miniprogram", null);
    assertConfigurationWait(stopped(id)); assertTrue(tools.merged.isEmpty());
  }
  @Test void existingReleaseDoesNotProvePlatformDeliveryOrCloseTheIssue() {
    String id = task(); Task released = runTo(id, Status.RELEASED);
    assertEquals(Status.RELEASED, released.st()); assertTrue(released.context.rel.verified);
    configure("wechat_miniprogram", null); Task blocked = stopped(id);
    assertConfigurationWait(blocked);
    assertEquals("VERIFIED", ReleasePolicy.describe(blocked, projects.forTask(blocked).cfg()).githubRelease());
    assertEquals(1, tools.releases.size());
  }
  @Test void invalidTargetsAreRejectedAndLegacyProjectsRemainRepositoryOnly() {
    assertThrows(IllegalArgumentException.class, () -> configure("unknown-platform", null));
    assertThrows(IllegalArgumentException.class, () -> configure(null, null));
    assertTrue(ReleasePolicy.check(Json.parse("{}", ProjectCfg.class)).ready());
    var invalid = new ProjectCfg(); invalid.releaseTarget = "unknown-platform";
    assertFalse(ReleasePolicy.check(invalid).ready());
    assertTrue(com.x7x24.web.CatalogApi.projectDto(projects.require("sandbox-default"), projects.runtime("sandbox-default")).containsKey("releaseCapability"));
  }
}
