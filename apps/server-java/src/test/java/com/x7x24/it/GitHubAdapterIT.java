package com.x7x24.it;

import com.x7x24.adapters.DeliveryTools;
import com.x7x24.adapters.GitHubTools;
import com.x7x24.db.Db;
import com.x7x24.engine.ProjectCfg;
import com.x7x24.gov.Risk;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** GitHub 适配器对 mock HTTP（MockWebServer）的行为：认证、限流退避、多文件提交、合并闸门、幂等、令牌不泄露。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class GitHubAdapterIT {
  static final String TOKEN = "test-token-0123456789-abcdefghijklmnop";
  static final MockWebServer server = new MockWebServer();
  static final ConcurrentLinkedQueue<RecordedRequest> seen = new ConcurrentLinkedQueue<>();
  static final AtomicInteger rateLimitOnce = new AtomicInteger(1);
  static volatile String prHead = "h".repeat(40); static volatile List<String> prFiles = List.of("src/a.js");
  static volatile String checkConclusion = "success";

  static {
    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest r) {
        seen.add(r); String m = r.getMethod(), p = r.getPath();
        MockResponse ok = new MockResponse().setHeader("Content-Type", "application/json").setHeader("x-ratelimit-limit", "5000").setHeader("x-ratelimit-remaining", "4990");
        if (p.startsWith("/repos/o/r/contents/?")) return ok.setBody(projectPackage == null ? "[{\"path\":\"README.md\"}]" : "[{\"path\":\"package.json\"}]");
        if (p.startsWith("/repos/o/r/contents/package.json?")) return projectPackage == null ? ok.setResponseCode(404).setBody("{}") : ok.setBody(com.x7x24.util.Json.str(Map.of("content", Base64.getEncoder().encodeToString(projectPackage.getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
        if (p.startsWith("/repos/o/r/contents/.github/workflows/ci.yml?")) return workflowFile ? ok.setBody(com.x7x24.util.Json.str(Map.of("content", Base64.getEncoder().encodeToString("on:\n  workflow_dispatch:\n".getBytes())))) : ok.setResponseCode(404).setBody("{}");
        if (p.equals("/repos/o/r/actions/workflows/ci.yml/dispatches")) return dispatchStatus == 204 ? ok.setResponseCode(204) : ok.setResponseCode(dispatchStatus).setBody("{\"message\":\"Resource not accessible by integration\"}");
        if (p.equals("/repos/o/r/actions/workflows/ci.yml")) return ok.setResponseCode(workflowStatus).setBody(workflowStatus == 200 ? "{\"id\":10,\"state\":\"" + workflowState + "\"}" : "{\"message\":\"Not Found\"}");
        if (p.startsWith("/repos/o/r/actions/workflows?")) return ok.setResponseCode(workflowsStatus).setBody(workflowsStatus == 200 ? "{\"total_count\":0,\"workflows\":[]}" : "{\"message\":\"Resource not accessible by integration\"}");
        if (p.startsWith("/repos/o/r/actions/workflows/ci.yml/runs?")) return ok.setResponseCode(workflowStatus).setBody(workflowStatus == 200 ? "{\"workflow_runs\":" + ciRuns + "}" : "{\"message\":\"Not Found\"}");
        if (p.startsWith("/repos/o/r/actions/runs/20/jobs?")) return ok.setBody("{\"jobs\":" + ciJobs + "}");
        if (p.matches("/repos/o/r/actions/jobs/\\d+/logs")) return ok.setHeader("Content-Type", "text/plain").setResponseCode(jobLogStatus).setBody(jobLogs.getOrDefault(p, ""));
        if (p.equals("/repos/o/r") && m.equals("GET")) return ok.setBody(com.x7x24.util.Json.str(Map.of("default_branch", defaultBranch)));
        if (p.equals("/repos/o/r/contents/README.md") && m.equals("PUT")) {
          if (initializationStatus == 403 || initializationStatus == 409) return ok.setResponseCode(initializationStatus).setBody("{\"message\":\"Resource not accessible by integration\"}");
          var body = com.x7x24.util.Json.tree(r.getBody().clone().readUtf8());
          initializationMessage = body.path("message").asText(); initializationContent = body.path("content").asText(); refStatus = 200;
          if (initializationStatus == 503) return ok.setResponseCode(503).setBody("{}");
          return ok.setResponseCode(201).setBody(com.x7x24.util.Json.str(Map.of("commit", Map.of("sha", "a".repeat(40), "parents", initializationRace ? List.of(Map.of("sha", "f".repeat(40))) : List.of()))));
        }
        if (p.startsWith("/repos/o/r/commits?") && m.equals("GET")) return ok.setBody(initializationMessage == null ? "[]" : com.x7x24.util.Json.str(List.of(Map.of("sha", "a".repeat(40), "parents", initializationRace ? List.of(Map.of("sha", "f".repeat(40))) : List.of(), "commit", Map.of("message", initializationMessage)))));
        if (p.startsWith("/repos/o/r/contents/README.md?") && m.equals("GET") && initializationContent != null) return ok.setBody(com.x7x24.util.Json.str(Map.of("content", initializationContent)));
        if (p.startsWith("/repos/o/r/issues?") && m.equals("GET")) {
          if (p.contains("per_page=100") && rateLimitOnce.getAndSet(0) == 1) return new MockResponse().setResponseCode(429).setHeader("Retry-After", "0").setBody("{\"message\":\"rate limit\"}");
          return ok.setBody("[{\"number\":7,\"title\":\"新增 slugify\",\"body\":\"b\",\"html_url\":\"https://x/7\",\"user\":{\"login\":\"u\"},\"labels\":[{\"name\":\"7x24:signal\"}],\"created_at\":\"2026-01-01T00:00:00Z\"},{\"number\":8,\"title\":\"a PR\",\"pull_request\":{},\"labels\":[]}]");
        }
        if (p.equals("/repos/o/r/issues/7/comments") && m.equals("POST")) {
          if(lostComment){remoteComment=r.getBody().readUtf8();return ok.setResponseCode(503).setBody("{}");}
          return ok.setResponseCode(201).setBody("{\"id\":1}");
        }
        if(p.startsWith("/repos/o/r/issues/7/comments?") && m.equals("GET"))return ok.setBody(remoteComment==null?"[]":"["+remoteComment+"]");
        if (p.equals("/repos/o/r/git/ref/heads/nope")) return new MockResponse().setResponseCode(404).setBody("{\"message\":\"Not Found\"}");
        if (p.startsWith("/repos/o/r/git/ref/heads/") && refStatus != 200) return ok.setResponseCode(refStatus).setBody(com.x7x24.util.Json.str(Map.of("message", refError)));
        if (p.startsWith("/repos/o/r/git/commits/") && m.equals("GET") && !p.matches("/repos/o/r/git/commits/[0-9a-f]{40}")) return new MockResponse().setResponseCode(404).setBody("{\"message\":\"Not Found\"}"); // 与真实 GitHub 一致：只认 SHA
        if (p.startsWith("/repos/o/r/git/trees/") && m.equals("GET")) return ok.setBody("{\"truncated\":false,\"tree\":[{\"path\":\"src/a.js\",\"type\":\"blob\"},{\"path\":\"docs\",\"type\":\"tree\"}]}");
        if (p.startsWith("/repos/o/r/git/ref/heads/")) return ok.setBody("{\"object\":{\"sha\":\"" + branchHead + "\"}}");
        if (p.startsWith("/repos/o/r/git/commits/") && m.equals("GET")) return ok.setBody("{\"tree\":{\"sha\":\"" + "b".repeat(40) + "\"}}");
        if (p.equals("/repos/o/r/git/trees")) return ok.setResponseCode(201).setBody("{\"sha\":\"" + "c".repeat(40) + "\"}");
        if (p.equals("/repos/o/r/git/commits") && m.equals("POST")) return ok.setResponseCode(201).setBody("{\"sha\":\"" + "d".repeat(40) + "\"}");
        if (p.startsWith("/repos/o/r/git/refs/heads/") && m.equals("PATCH")) return ok.setBody("{}");
        if (p.equals("/repos/o/r/pulls/5") && m.equals("GET")) return ok.setBody("{\"number\":5,\"state\":\"open\",\"merged\":false,\"head\":{\"sha\":\"" + prHead + "\",\"ref\":\"agent/x\",\"repo\":{\"full_name\":\"o/r\"}}}");
        if (p.startsWith("/repos/o/r/pulls/5/files")) {
          if(secondPage && p.contains("page=2"))return ok.setBody("[{\"filename\":\".github/workflows/protected.yml\",\"additions\":1,\"deletions\":0,\"status\":\"added\"}]");
          if(secondPage)ok.setHeader("Link","<http://localhost/ignored>; rel=\"next\""); StringBuilder b = new StringBuilder("["); for (int i = 0; i < prFiles.size(); i++) b.append(i > 0 ? "," : "").append("{\"filename\":\"").append(prFiles.get(i)).append("\",\"additions\":3,\"deletions\":0,\"status\":\"added\"}"); return ok.setBody(b.append("]").toString()); }
        if (p.startsWith("/repos/o/r/rules/branches/")) return new MockResponse().setResponseCode(403).setBody("{\"message\":\"Upgrade to GitHub Pro\"}");
        if(p.contains("/check-runs") && secondPageChecks){
          if(p.contains("page=2"))return ok.setBody("{\"total_count\":2,\"check_runs\":[{\"name\":\"late-failing-check\",\"status\":\"completed\",\"conclusion\":\"failure\"}]}");
          ok.setHeader("Link","<http://localhost/ignored>; rel=\"next\"");
        }
        if (p.contains("/check-runs")) return ok.setBody("{\"check_runs\":[{\"name\":\"ci\",\"status\":\"completed\",\"conclusion\":\"" + checkConclusion + "\"}]}");
        if (p.equals("/repos/o/r/pulls/5/merge") && m.equals("PUT")) return ok.setBody("{\"sha\":\"" + "e".repeat(40) + "\",\"merged\":true}");
        return new MockResponse().setResponseCode(404).setBody("{\"message\":\"Not Found\"}");
      }
    });
    try { server.start(); } catch (IOException e) { throw new RuntimeException(e); }
  }
  static volatile boolean secondPage=false, lostComment=false, secondPageChecks=false;
  static volatile String remoteComment;
  static volatile int workflowStatus = 200, workflowsStatus = 200;
  static volatile String workflowState = "active", ciRuns = "[]";
  static volatile String ciJobs = "[{\"id\":21,\"name\":\"test\",\"conclusion\":\"success\"}]";
  static final Map<String, String> jobLogs = new java.util.concurrent.ConcurrentHashMap<>();
  static volatile int jobLogStatus = 200;
  static volatile String branchHead = "a".repeat(40);
  static volatile String projectPackage = "{\"scripts\":{\"test\":\"vitest run\"}}";
  static volatile boolean workflowFile = true;
  static volatile int dispatchStatus = 204;
  @BeforeEach void resetProjectFixture() { projectPackage = "{\"scripts\":{\"test\":\"vitest run\"}}"; workflowFile = true; dispatchStatus = 204; ciJobs = "[{\"id\":21,\"name\":\"test\",\"conclusion\":\"success\"}]"; jobLogs.clear(); jobLogStatus = 200; branchHead = "a".repeat(40); }
  static volatile int refStatus = 200;
  static volatile String refError = "Git Repository is empty.";
  static volatile int initializationStatus = 201;
  static volatile boolean initializationRace;
  static volatile String defaultBranch = "main", initializationMessage, initializationContent;
  @org.junit.jupiter.api.AfterEach void resetSafetyFixtures(){workflowStatus=200;workflowsStatus=200;workflowState="active";ciRuns="[]";secondPage=false;lostComment=false;secondPageChecks=false;remoteComment=null;refStatus=200;refError="Git Repository is empty.";initializationStatus=201;initializationRace=false;defaultBranch="main";initializationMessage=null;initializationContent=null;db.update("DELETE FROM tool_ops WHERE op_key LIKE 'repository-init-%'");}
  @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
    r.add("x7x24.auth.required", () -> "false");
    r.add("x7x24.github.api-url", () -> "http://localhost:" + server.getPort()); r.add("x7x24.github.token", () -> TOKEN);
    r.add("x7x24.adapter.mode", () -> "real"); r.add("x7x24.adapter.dry-run", () -> "false"); r.add("spring.datasource.url", () -> "jdbc:h2:mem:adapter;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY,VALUE;DB_CLOSE_DELAY=-1");
  }
  @AfterAll static void stop() throws IOException { server.shutdown(); }

  @Autowired DeliveryTools tools; @Autowired Db db;
  @Autowired com.x7x24.gov.ConfigStore config; @Autowired com.x7x24.gov.KillSwitch kill;
  final ProjectCfg line = line();
  static ProjectCfg line() { var l = new ProjectCfg(); l.id = "sandbox"; l.repo = "o/r"; return l; }
  List<RecordedRequest> reqs(String method, String pathPrefix) { return seen.stream().filter(r -> r.getMethod().equals(method) && r.getPath().startsWith(pathPrefix)).toList(); }

  @Test void missingWorkflowIsAnActionableCiGateRatherThanAnAgentError() {
    workflowStatus = 404; seen.clear();
    var e = assertThrows(DeliveryTools.GateBlocked.class, () -> tools.ci(line, "missing-ci:ci", "agent/x", prHead, new com.x7x24.engine.TaskCtx.Ci(), false, "missing-ci"));
    assertEquals("ci_broken", e.category); assertTrue(e.getMessage().contains("ci.yml"));
    assertTrue(seen.stream().allMatch(r -> "GET".equals(r.getMethod())));
  }

  @Test void projectReadinessRequiresAManifestAndAnExecutableTestCommand() {
    projectPackage = null; seen.clear();
    assertEquals("project_not_ready", assertThrows(DeliveryTools.GateBlocked.class, () -> tools.assertDevelopmentReady(line, "main")).category);
    projectPackage = "{\"scripts\":{\"build\":\"next build\"}}";
    assertTrue(assertThrows(DeliveryTools.GateBlocked.class, () -> tools.assertDevelopmentReady(line, "main")).getMessage().contains("scripts.test"));
    projectPackage = "{\"scripts\":{\"test\":\"vitest run\"}}";
    assertDoesNotThrow(() -> tools.assertDevelopmentReady(line, prHead));
    assertTrue(seen.stream().allMatch(r -> "GET".equals(r.getMethod())));
  }

  @Test void inaccessibleActionsAndDisabledWorkflowsHaveDistinctRepairReasons() {
    workflowStatus = 404; workflowsStatus = 403;
    var denied = assertThrows(DeliveryTools.GateBlocked.class, () -> tools.ci(line, "denied-ci:ci", "agent/x", prHead, null, false, null));
    assertEquals("ci_permission", denied.category);
    workflowStatus = 200; workflowState = "disabled_manually";
    var disabled = assertThrows(DeliveryTools.GateBlocked.class, () -> tools.ci(line, "disabled-ci:ci", "agent/x", prHead, null, false, null));
    assertEquals("ci_broken", disabled.category); assertTrue(disabled.getMessage().contains("禁用"));
  }

  @Test void qaCanPassOnAnUnmergedPrAndNeverUsesAnotherCommitRun() {
    prHead = "9".repeat(40); seen.clear();
    ciRuns = "[{\"id\":20,\"head_sha\":\"" + prHead + "\",\"status\":\"completed\",\"conclusion\":\"success\",\"html_url\":\"https://example.test/runs/20\"},{\"id\":22,\"head_sha\":\"old\",\"status\":\"completed\",\"conclusion\":\"failure\"}]";
    var ci = tools.ci(line, "unmerged-ci:ci", "agent/x", prHead, null, false, null);
    assertEquals("passed", ci.state()); assertEquals(List.of(20L), ci.runIds());
    assertTrue(seen.stream().allMatch(r -> "GET".equals(r.getMethod())), "QA 通过不需要合并 PR");
  }

  @Test void allFailureLocationsAndTypeErrorsSurviveMultipleJobLogs() {
    ciRuns = "[{\"id\":20,\"head_sha\":\"" + prHead + "\",\"status\":\"completed\",\"conclusion\":\"failure\"}]";
    ciJobs = "[{\"id\":21,\"name\":\"typecheck\",\"conclusion\":\"failure\"},{\"id\":22,\"name\":\"test\",\"conclusion\":\"failure\"}]";
    jobLogs.put("/repos/o/r/actions/jobs/21/logs", "src/url.ts(12,3): error TS2322: Type 'number' is not assignable to type 'string'.\nProcess completed with exit code 2.\n");
    String log = java.util.stream.IntStream.rangeClosed(1, 40).mapToObj(i -> "\u001b[31mFAIL src/case" + i + ".test.ts > case " + i + "\u001b[39m\nAssertionError: expected " + i + " to be 100\n ❯ src/case" + i + ".test.ts:42:8\n").collect(java.util.stream.Collectors.joining("\n"));
    jobLogs.put("/repos/o/r/actions/jobs/22/logs", log);
    var ci = tools.ci(line, "complete-log:ci", "agent/x", prHead, null, false, null);
    assertEquals("failed", ci.state());
    assertTrue(ci.log().contains("error TS2322"), ci.log());
    for (int i = 1; i <= 40; i++) assertTrue(ci.log().contains("src/case" + i + ".test.ts:42:8"), "丢失第 " + i + " 个失败的位置");
    assertFalse(ci.log().contains("\u001b["));
    assertEquals(41, com.x7x24.engine.CiFailure.classify(ci.log()).files().size());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"skipped", "neutral", ""})
  void incompleteJobsCannotPassEvenIfTheWorkflowConclusionIsSuccess(String conclusion) {
    ciRuns = "[{\"id\":20,\"head_sha\":\"" + prHead + "\",\"status\":\"completed\",\"conclusion\":\"success\"}]";
    ciJobs = com.x7x24.util.Json.str(List.of(Map.of("id", 21, "name", "required-tests", "conclusion", conclusion)));
    assertNotEquals("passed", tools.ci(line, "incomplete-job:ci", "agent/x", prHead, null, false, null).state());
  }

  @Test void anExistingWorkflowWithNoRunsWaitsWithoutPassing() {
    ciRuns = "[]";
    var ci = tools.ci(line, "pending-ci:ci", "agent/x", prHead, null, false, null);
    assertEquals("pending", ci.state()); assertEquals(0, ci.total());
  }

  @Test void unavailableFailureLogsAreExplicitAndCannotDriveBlindRepair() {
    ciRuns = "[{\"id\":20,\"head_sha\":\"" + prHead + "\",\"status\":\"completed\",\"conclusion\":\"failure\"}]";
    ciJobs = "[{\"id\":21,\"name\":\"test\",\"conclusion\":\"failure\"}]"; jobLogStatus = 404;
    var ci = tools.ci(line, "expired-log:ci", "agent/x", prHead, null, false, null);
    assertEquals("failed", ci.state()); assertFalse(ci.logComplete());
    assertTrue(ci.log().contains("日志无法读取"));
  }

  @Test void aFailedCheckStepCannotHideBehindASuccessfulJobConclusion() {
    ciRuns = "[{\"id\":20,\"head_sha\":\"" + prHead + "\",\"status\":\"completed\",\"conclusion\":\"success\"}]";
    ciJobs = "[{\"id\":21,\"name\":\"ci\",\"conclusion\":\"success\",\"steps\":[{\"name\":\"Run npm run typecheck\",\"conclusion\":\"failure\"}]}]";
    jobLogs.put("/repos/o/r/actions/jobs/21/logs", "src/url.ts(12,3): error TS2322: wrong type\n");
    var ci = tools.ci(line, "failed-step:ci", "agent/x", prHead, null, false, null);
    assertEquals("failed", ci.state()); assertEquals(1, ci.failed()); assertTrue(ci.logComplete());
    assertTrue(ci.log().contains("Run npm run typecheck")); assertTrue(ci.log().contains("TS2322"));
  }

  @Test void pinnedPatchRejectsAChangedBranchBeforeWritingAnyGitObjects() {
    seen.clear();
    var error = assertThrows(DeliveryTools.GateBlocked.class, () -> tools.commit(line, "pinned-mismatch:commit", "agent/x", "fix", List.of(DeliveryTools.FileChange.put("src/a.js", "old patch")), "b".repeat(40)));
    assertEquals("head_changed", error.category);
    assertTrue(seen.stream().allMatch(r -> "GET".equals(r.getMethod())), "基线不匹配不得写 tree、commit 或分支");
  }

  @Test void branchCreationCheckpointDoesNotFreezeTheHeadForLaterRepairs() {
    String key = "current-head:" + UUID.randomUUID();
    assertEquals(branchHead, tools.ensureBranch(line, key, "agent/x"));
    branchHead = "f".repeat(40);
    assertEquals(branchHead, tools.ensureBranch(line, key, "agent/x"));
  }

  @Test void manualDispatchUsesTheBusinessBranchAndRequiresDefaultBranchSetupAndWritePermission() {
    seen.clear();
    var pending = tools.ci(line, "dispatch-ci:ci", "agent/x", prHead, null, true, "dispatch-ci");
    assertEquals("pending", pending.state()); assertTrue(pending.dispatched());
    assertTrue(reqs("POST", "/repos/o/r/actions/workflows/ci.yml/dispatches").getFirst().getBody().clone().readUtf8().contains("agent/x"));
    workflowFile = false; seen.clear();
    assertEquals("ci_broken", assertThrows(DeliveryTools.GateBlocked.class, () -> tools.ci(line, "missing-dispatch-ci:ci", "agent/x", prHead, null, true, null)).category);
    assertTrue(seen.stream().allMatch(r -> "GET".equals(r.getMethod())));
    workflowFile = true; dispatchStatus = 403;
    assertEquals("ci_permission", assertThrows(DeliveryTools.GateBlocked.class, () -> tools.ci(line, "denied-dispatch-ci:ci", "agent/x", prHead, null, true, null)).category);
  }

  @Test void secondPageGovernanceFileIsNeverOmitted() {
    secondPage=true;prFiles=List.of("src/a.js");prHead="1".repeat(40);
    assertEquals(2,tools.prFiles(line,5).size());assertEquals(2,tools.prPatches(line,5).size());
    assertEquals("governance_file",assertThrows(DeliveryTools.GateBlocked.class,()->tools.merge(line,"task-page:merge",5,prHead,"fixture")).category);
  }
  @Test void ambiguousCommentReconcilesWithoutRepeatingWrite() {
    lostComment=true;String key="task-unknown:comment:"+System.nanoTime();int before=reqs("POST","/repos/o/r/issues/7/comments").size();
    assertThrows(com.x7x24.adapters.GitHubClient.OutcomeUnknown.class,()->tools.comment(line,key,7,"synthetic lost response"));
    assertEquals("OUTCOME_UNKNOWN",db.row("SELECT state FROM tool_ops WHERE op_key=?",key).get("state"));
    tools.comment(line,key,7,"synthetic lost response");assertEquals("SUCCEEDED",db.row("SELECT state FROM tool_ops WHERE op_key=?",key).get("state"));
    assertEquals(before+1,reqs("POST","/repos/o/r/issues/7/comments").size());
    assertEquals("operation_conflict",assertThrows(DeliveryTools.GateBlocked.class,()->tools.comment(line,key,7,"different request")).category);
  }
  @Test void missingRemoteProofKeepsUnknownAndNeverResends() {
    lostComment=true;String key="task-missing-proof:comment:"+System.nanoTime();int before=reqs("POST","/repos/o/r/issues/7/comments").size();
    assertThrows(com.x7x24.adapters.GitHubClient.OutcomeUnknown.class,()->tools.comment(line,key,7,"synthetic unknown"));remoteComment=null;
    assertEquals("blocked_reconciliation",assertThrows(DeliveryTools.GateBlocked.class,()->tools.comment(line,key,7,"synthetic unknown")).category);
    assertEquals("blocked_reconciliation",assertThrows(DeliveryTools.GateBlocked.class,()->tools.comment(line,key+":new-attempt",7,"synthetic unknown")).category);
    assertEquals("OUTCOME_UNKNOWN",db.row("SELECT state FROM tool_ops WHERE op_key=?",key).get("state"));assertEquals(before+1,reqs("POST","/repos/o/r/issues/7/comments").size());
  }
  @Test void aFailedCheckOnSecondPageBlocksMerge(){
    secondPageChecks=true;prHead="1".repeat(40);prFiles=List.of("src/a.js");checkConclusion="success";
    assertEquals("checks_not_green",assertThrows(DeliveryTools.GateBlocked.class,()->tools.merge(line,"task-check-pages:merge",5,prHead,"fixture")).category);
  }
  @Test void sourceFilesResolvesBranchNameToCommitSha() {
    seen.clear();
    var files = tools.sourceFiles(line, "main");
    assertTrue(files.contains("src/a.js"), files.toString());
    assertEquals(1, reqs("GET", "/repos/o/r/git/ref/heads/main").size(), "分支名先解析成 SHA");
    assertEquals(1, reqs("GET", "/repos/o/r/git/commits/" + "a".repeat(40)).size());
    assertTrue(reqs("GET", "/repos/o/r/git/commits/main").isEmpty(), "不能把分支名直接传给 /git/commits/");
    seen.clear(); String sha = "f".repeat(40);
    tools.sourceFiles(line, sha); assertTrue(reqs("GET", "/repos/o/r/git/ref/").isEmpty(), "已是 SHA 不再解析");
    var ex = assertThrows(RuntimeException.class, () -> tools.sourceFiles(line, "nope"));
    assertTrue(ex.getMessage().contains("分支 nope 不存在"), ex.getMessage());
  }

  @Test void emptyRepositoryIsAnActionableGateAndNeverStartsABranchWrite() {
    seen.clear(); refStatus = 409;
    var error = assertThrows(DeliveryTools.GateBlocked.class, () -> tools.sourceFiles(line, "main"));
    assertEquals("repository_not_initialized", error.category);
    assertTrue(error.getMessage().contains("首个提交"));
    assertThrows(DeliveryTools.GateBlocked.class, () -> tools.repositoryHead(line));
    String key = "empty-repo:branch";
    assertThrows(DeliveryTools.GateBlocked.class, () -> tools.ensureBranch(line, key, "agent/empty"));
    assertEquals(0, db.count("SELECT COUNT(*) FROM tool_ops WHERE op_key=?", key));
    assertTrue(seen.stream().allMatch(r -> r.getMethod().equals("GET")), "空仓库不能触发远端写入");
    refStatus = 200;
    assertTrue(tools.sourceFiles(line, "main").contains("src/a.js"), "初始化后重新读取，不能缓存为空");
  }

  @Test void permissionMissingBranchAndOtherConflictsAreNeverTreatedAsEmpty() {
    for (int status : List.of(401, 403, 404, 409)) {
      refStatus = status; refError = status == 409 ? "Reference update conflict" : "Not Found";
      var error = assertThrows(com.x7x24.adapters.GitHubClient.GitHubException.class, () -> tools.sourceFiles(line, "main"));
      assertEquals(status, error.status);
      assertEquals(status, assertThrows(com.x7x24.adapters.GitHubClient.GitHubException.class, () -> tools.initializeRepository(line, "init-read-error")).status);
    }
    assertTrue(reqs("PUT", "/repos/o/r/contents/").isEmpty());
  }

  @Test void initializationCreatesOnlyTheFirstReadmeAndDeduplicatesAcrossTasks() {
    seen.clear(); refStatus = 409;
    assertEquals("a".repeat(40), tools.initializeRepository(line, "task-init:repository-init"));
    assertEquals("a".repeat(40), tools.initializeRepository(line, "other-task:repository-init"));
    var requests = reqs("PUT", "/repos/o/r/contents/"); assertEquals(1, requests.size());
    var body = com.x7x24.util.Json.tree(requests.getFirst().getBody().clone().readUtf8());
    assertEquals("main", body.path("branch").asText()); assertFalse(body.has("sha"), "只能创建，不能覆盖文件");
    assertEquals(DeliveryTools.repositoryInit(line).content(), new String(Base64.getDecoder().decode(body.path("content").asText()), java.nio.charset.StandardCharsets.UTF_8));
    assertTrue(reqs("POST", "/repos/o/r/git/").isEmpty());
    assertFalse(Risk.evaluateAction(com.x7x24.gov.Governance.defaults(), "git.push", "main").allowed());
  }
  @Test void initializedRepositoriesAndMissingDefaultBranchNeverReceiveInitializationWrites() {
    seen.clear(); tools.initializeRepository(line, "already-initialized");
    assertTrue(reqs("PUT", "/repos/o/r/contents/").isEmpty());
    refStatus = 409; defaultBranch = "master";
    assertTrue(assertThrows(DeliveryTools.GateBlocked.class, () -> tools.initializeRepository(line, "branch-mismatch")).getMessage().contains("不一致"));
    assertTrue(reqs("PUT", "/repos/o/r/contents/").isEmpty());
    defaultBranch = "main"; tools.initializeRepository(line, "branch-aligned");
    assertEquals(1, reqs("PUT", "/repos/o/r/contents/").size());
  }
  @Test void initializationChecksPathPolicyAndKnownPermissionFailuresCanBeRetried() {
    seen.clear(); refStatus = 409; var restricted = line(); restricted.allowedExtensions = List.of("js");
    assertThrows(DeliveryTools.GateBlocked.class, () -> tools.initializeRepository(restricted, "path-policy"));
    assertTrue(reqs("PUT", "/repos/o/r/contents/").isEmpty());
    initializationStatus = 403;
    assertTrue(assertThrows(DeliveryTools.GateBlocked.class, () -> tools.initializeRepository(line, "permissions")).getMessage().contains("Contents"));
    assertEquals("FAILED_KNOWN", db.row("SELECT state FROM tool_ops WHERE op_key LIKE 'repository-init-%'").get("state"));
    initializationStatus = 201; tools.initializeRepository(line, "permissions-fixed");
    assertEquals(2, reqs("PUT", "/repos/o/r/contents/").size());
  }
  @Test void initializationCannotBypassKillSwitchOrGovernanceFiles() {
    seen.clear(); refStatus = 409;
    var sw = kill.activate("global", "READ_ONLY", "manual", "fixture", null);
    try { assertThrows(com.x7x24.gov.KillSwitch.Blocked.class, () -> tools.initializeRepository(line, "stopped")); }
    finally { kill.release(com.x7x24.db.Db.str(sw.get("id")), "test"); }
    var before = com.x7x24.util.Json.M.valueToTree(config.get()); var protectedConfig = config.get(); protectedConfig.governanceFiles.add("README.md");
    try {
      config.update(com.x7x24.util.Json.M.valueToTree(protectedConfig));
      assertTrue(assertThrows(DeliveryTools.GateBlocked.class, () -> tools.initializeRepository(line, "governance")).getMessage().contains("治理"));
      assertTrue(reqs("PUT", "/repos/o/r/contents/").isEmpty());
    } finally { config.update(before); }
  }
  @Test void lostInitializationResponseIsReconciledFromTheImmutableRootCommit() {
    seen.clear(); refStatus = 409; initializationStatus = 503;
    assertThrows(com.x7x24.adapters.GitHubClient.OutcomeUnknown.class, () -> tools.initializeRepository(line, "lost-response"));
    assertEquals("OUTCOME_UNKNOWN", db.row("SELECT state FROM tool_ops WHERE op_key LIKE 'repository-init-%'").get("state"));
    assertEquals("a".repeat(40), tools.initializeRepository(line, "another-task-reconciles"));
    assertEquals(1, reqs("PUT", "/repos/o/r/contents/").size());
    assertEquals("SUCCEEDED", db.row("SELECT state FROM tool_ops WHERE op_key LIKE 'repository-init-%'").get("state"));
  }
  @Test void missingProofOrAnExternalInitializationRaceNeverResends() {
    seen.clear(); refStatus = 409; initializationRace = true;
    assertThrows(com.x7x24.adapters.GitHubClient.OutcomeUnknown.class, () -> tools.initializeRepository(line, "external-race"));
    assertEquals("blocked_reconciliation", assertThrows(DeliveryTools.GateBlocked.class, () -> tools.initializeRepository(line, "retry-external-race")).category);
    initializationMessage = null; refStatus = 409;
    assertEquals("blocked_reconciliation", assertThrows(DeliveryTools.GateBlocked.class, () -> tools.initializeRepository(line, "still-no-proof")).category);
    assertEquals(1, reqs("PUT", "/repos/o/r/contents/").size());
  }
  @Test void concurrentTasksCreateExactlyOneInitializationCommit() throws Exception {
    seen.clear(); refStatus = 409;
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> tools.initializeRepository(line, "concurrent-a"));
      var b = pool.submit(() -> tools.initializeRepository(line, "concurrent-b"));
      assertEquals(a.get(10, java.util.concurrent.TimeUnit.SECONDS), b.get(10, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertEquals(1, reqs("PUT", "/repos/o/r/contents/").size());
  }

  @Test void planningReachesTheModelAfterGitHubReportsAnEmptyRepository() {
    refStatus = 409;
    var provider = org.mockito.Mockito.mock(com.x7x24.llm.LlmProvider.class);
    org.mockito.Mockito.when(provider.model()).thenReturn("fixture-planner");
    String output = "{\"path\":[\"SPEC\",\"DEV\"],\"files\":[\"src/news/feed.js\",\"tests/news/feed.test.js\"]}";
    org.mockito.Mockito.when(provider.chat(org.mockito.Mockito.any(), org.mockito.Mockito.any())).thenAnswer(call -> {
      com.x7x24.llm.LlmProvider.Request request = call.getArgument(0);
      com.x7x24.llm.LlmProvider.Validator validator = call.getArgument(1);
      assertTrue(request.prompt().contains("从零规划"));
      assertNull(validator.validate(com.x7x24.util.Json.tree(output)));
      return new com.x7x24.llm.LlmProvider.Response(output, "fixture-planner", "v1", 10, 0);
    });
    var cfg = org.mockito.Mockito.mock(com.x7x24.gov.ConfigStore.class);
    org.mockito.Mockito.when(cfg.get()).thenReturn(com.x7x24.gov.Governance.defaults());
    var task = new com.x7x24.engine.Task(); task.id = "empty-repository-plan"; task.title = "新增资讯采集"; task.problem = "从零搭建资讯采集功能";
    var prd = new com.x7x24.agents.Models.Prd(1, task.title, task.problem, "", List.of("返回资讯列表"), List.of(), 0.1, List.of(), "other", "low", null, List.of(), false);
    var context = new com.x7x24.agents.Models.Ctx(task, line, null, (agent, response) -> {}, tools, prd, null, null, false);
    var plan = new com.x7x24.agents.LlmAgents(provider, provider, cfg).plan(context);
    assertEquals(List.of("SPEC", "DEV"), plan.path());
    assertEquals(List.of("src/news/feed.js", "tests/news/feed.test.js"), plan.files());
    org.mockito.Mockito.verify(provider).chat(org.mockito.Mockito.any(), org.mockito.Mockito.any());
  }

  @Test void listsSignalIssuesWithAuthAndSkipsPullRequestsAndRetriesOnRateLimit() {
    var issues = tools.listSignalIssues(line);
    assertEquals(1, issues.size()); assertEquals(7, issues.get(0).number()); assertEquals(List.of("7x24:signal"), issues.get(0).labels());
    var gets = reqs("GET", "/repos/o/r/issues?");
    assertEquals(2, gets.size(), "429 → 退避后重试一次");
    assertEquals("Bearer " + TOKEN, gets.get(0).getHeader("Authorization"));
    assertTrue(gets.get(0).getPath().contains("labels=7x24%3Asignal"));
    var rate = (Map<?, ?>) ((GitHubTools) tools).lineStatus(line).get("rate");
    assertEquals(4990, rate.get("remaining")); assertTrue((int) rate.get("retries") >= 1);
  }

  @Test void commentsAreIdempotentPerKey() {
    int before = reqs("POST", "/repos/o/r/issues/7/comments").size();
    tools.comment(line, "task-1:issue:c:x", 7, "hello"); tools.comment(line, "task-1:issue:c:x", 7, "hello");
    assertEquals(before + 1, reqs("POST", "/repos/o/r/issues/7/comments").size());
  }

  @Test void multiFileCommitUsesGitDataApiWithoutForce() throws Exception {
    String sha = tools.commit(line, "task-2:commit", "agent/issue-9", "feat: x", List.of(DeliveryTools.FileChange.put("src/a.js", "a"), DeliveryTools.FileChange.put("tests/a.test.js", "t"), DeliveryTools.FileChange.put("docs/a.md", "d")));
    assertEquals("d".repeat(40), sha);
    var tree = reqs("POST", "/repos/o/r/git/trees").get(0).getBody().readUtf8();
    assertEquals(3, com.x7x24.util.Json.tree(tree).path("tree").size());
    var patch = reqs("PATCH", "/repos/o/r/git/refs/heads/agent").get(0).getBody().readUtf8();
    assertTrue(patch.contains("\"force\":false"));
  }

  @Test void commitTouchingWorkflowFilesIsDeniedLocallyWithoutAnyRequest() {
    int before = seen.size();
    assertThrows(Risk.PolicyDenied.class, () -> tools.commit(line, "task-3:commit", "agent/issue-10", "evil", List.of(DeliveryTools.FileChange.put(".github/workflows/ci.yml", "on: push"))));
    assertEquals(before, seen.size());
    assertTrue(db.count("SELECT COUNT(*) FROM task_event WHERE event_type='ACTION_DENIED'") >= 1);
  }

  @Test void mergeGateBlocksHeadChangeGovernanceFilesAndRedChecks() {
    prHead = "1".repeat(40); prFiles = List.of("src/a.js"); checkConclusion = "success";
    var e1 = assertThrows(DeliveryTools.GateBlocked.class, () -> tools.merge(line, "task-4:m1", 5, "2".repeat(40), "t"));
    assertEquals("head_changed", e1.category);
    prFiles = List.of("src/a.js", ".github/workflows/ci.yml");
    assertEquals("governance_file", assertThrows(DeliveryTools.GateBlocked.class, () -> tools.merge(line, "task-4:m2", 5, prHead, "t")).category);
    prFiles = List.of("src/a.js"); checkConclusion = "failure";
    assertEquals("checks_not_green", assertThrows(DeliveryTools.GateBlocked.class, () -> tools.merge(line, "task-4:m3", 5, prHead, "t")).category);
    assertTrue(reqs("PUT", "/repos/o/r/pulls/5/merge").isEmpty(), "闸门拒绝时绝不发出合并请求");
    checkConclusion = "success";
    var ok = tools.merge(line, "task-4:m4", 5, prHead, "t");
    assertEquals("e".repeat(40), ok.sha());
    assertEquals(1, reqs("PUT", "/repos/o/r/pulls/5/merge").size());
    assertTrue(reqs("PUT", "/repos/o/r/pulls/5/merge").get(0).getBody().readUtf8().contains("\"merge_method\":\"squash\""));
  }

  @Test void tokenNeverLandsInDatabaseOrCallLog() {
    tools.listSignalIssues(line);
    for (String t : List.of("adapter_call", "task_event", "tool_ops")) {
      String col = t.equals("adapter_call") ? "path || COALESCE(note,'')" : t.equals("task_event") ? "COALESCE(payload,'')" : "result";
      assertEquals(0, db.count("SELECT COUNT(*) FROM " + t + " WHERE " + col + " LIKE ?", "%" + TOKEN.substring(4, 20) + "%"), t);
    }
  }
}
