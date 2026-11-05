package com.x7x24.it;

import com.x7x24.adapters.DeliveryTools;
import com.x7x24.engine.ProjectCfg;
import com.x7x24.engine.TaskCtx;
import com.x7x24.util.U;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 内存版 GitHub：用于对引擎做确定性集成测试（真实 GitHub 联调见 scripts/live-e2e.sh）。 */
public class FakeTools implements DeliveryTools {
  public final Map<String, String> main = new TreeMap<>();
  public final Map<String, Map<String, String>> branches = new HashMap<>();
  public final Map<Integer, IssueInfo> issues = new LinkedHashMap<>();
  public final Map<Integer, List<String>> comments = new HashMap<>(), labelsAdded = new HashMap<>();
  public final Set<Integer> closed = new HashSet<>();
  public final Map<Integer, PrInfo> prs = new LinkedHashMap<>();
  public final List<String> merged = new ArrayList<>(), releases = new ArrayList<>(), reverts = new ArrayList<>();
  public final AtomicInteger seq = new AtomicInteger(100), prOpened = new AtomicInteger(), commits = new AtomicInteger();
  public volatile boolean ciAlwaysFail = false, mainUnhealthy = false;
  public volatile boolean repositoryEmpty = false;
  public final AtomicInteger repositoryInitializations = new AtomicInteger();
  public volatile RuntimeException initializationFailure;
  public volatile RuntimeException ciFailure;
  public volatile CiState ciResultOverride;
  public volatile RuntimeException developmentFailure, branchUpdateFailure;
  /** 模拟 CI 安装步骤就失败（如仓库根目录没有 package.json）；baseCiFailing = 主干 CI 也是同样的失败 */
  public volatile boolean ciSetupBroken = false, baseCiFailing = false;
  /** 逐行审查用：非空时 prPatches 返回它（否则返回空，引擎退回读整文件） */
  public volatile List<FilePatch> patches = null;
  /** 逐行审查读 diff 时回调一次（测试用：模拟审查途中任务被暂停 / 版本号被并发改掉） */
  public volatile Runnable onPrPatches = null;
  private final Map<String, String> idem = new HashMap<>();

  public void reset() { ciResultOverride = null; repositoryInitializations.set(0); developmentFailure = null; branchUpdateFailure = null; ciFailure = null; initializationFailure = null; repositoryEmpty = false; patches = null; onPrPatches = null; main.clear(); branches.clear(); issues.clear(); comments.clear(); labelsAdded.clear(); closed.clear(); prs.clear(); merged.clear(); releases.clear(); reverts.clear(); prOpened.set(0); commits.set(0); ciAlwaysFail = false; mainUnhealthy = false; ciSetupBroken = false; baseCiFailing = false; idem.clear(); }
  public int addIssue(String title, String body) { int n = seq.incrementAndGet(); issues.put(n, new IssueInfo(n, title, body, "https://example.test/issues/" + n, "tester", List.of("7x24:signal"), "2026-10-11T00:00:00.000Z")); return n; }

  @Override public boolean live() { return true; }
  @Override public void assertDevelopmentReady(ProjectCfg project, String ref) { if (developmentFailure != null) throw developmentFailure; }
  @Override public void assertCiReady(ProjectCfg project) { if (ciFailure != null) throw ciFailure; }
  @Override public String prHead(ProjectCfg project, int number) {
    var pr = prs.get(number); return pr == null ? null : head(branches.get(pr.branch()));
  }
  @Override public String updateBranch(ProjectCfg project, String key, int number, String branch, String oldHead) {
    if (branchUpdateFailure != null) throw branchUpdateFailure;
    var tree = branches.get(branch); main.forEach(tree::putIfAbsent); return head(tree);
  }
  @Override public String repositoryHead(ProjectCfg line) {
    if (repositoryEmpty) throw new GateBlocked("repository_not_initialized", "仓库尚未初始化，请创建首个提交");
    return head(main);
  }
  @Override public synchronized String initializeRepository(ProjectCfg line, String key) {
    if (initializationFailure != null) throw initializationFailure;
    if (repositoryEmpty) {
      var spec = DeliveryTools.repositoryInit(line); main.put(spec.path(), spec.content());
      repositoryInitializations.incrementAndGet(); repositoryEmpty = false;
    }
    return repositoryHead(line);
  }
  @Override public List<FilePatch> prPatches(ProjectCfg line, int pr) { var h = onPrPatches; if (h != null) { onPrPatches = null; h.run(); } var p = patches; return p == null ? List.of() : p; }
  @Override public List<IssueInfo> listSignalIssues(ProjectCfg line) { return issues.values().stream().filter(i -> !closed.contains(i.number())).toList(); }
  @Override public IssueInfo createIssue(ProjectCfg line, String key, String title, String body, List<String> labels) {
    int n = addIssue(title, body); return issues.get(n);
  }
  @Override public synchronized void comment(ProjectCfg line, String key, int issue, String body) { if (idem.putIfAbsent(key, "1") == null) comments.computeIfAbsent(issue, k -> new ArrayList<>()).add(body); }
  @Override public synchronized void labels(ProjectCfg line, String key, int issue, List<String> add, List<String> remove) { if (idem.putIfAbsent(key, "1") == null) labelsAdded.computeIfAbsent(issue, k -> new ArrayList<>()).addAll(add); }
  @Override public synchronized void closeIssue(ProjectCfg line, String key, int issue, boolean completed) { closed.add(issue); }
  @Override public synchronized void reopenIssue(ProjectCfg line,String key,int issue) { closed.remove(issue); }
  @Override public String readFile(ProjectCfg line, String ref, String path) { return "main".equals(ref) || ref == null ? main.get(path) : branches.getOrDefault(ref, main).get(path); }
  @Override public List<String> listDir(ProjectCfg line, String ref, String dir) { var src = "main".equals(ref) || ref == null ? main : branches.getOrDefault(ref, main); return src.keySet().stream().filter(p -> p.startsWith(dir)).toList(); }
  private String head(Map<String, String> tree) { return U.sha256(new TreeMap<>(tree).toString()).substring(0, 40); }
  @Override public synchronized String ensureBranch(ProjectCfg line, String key, String branch) { branches.computeIfAbsent(branch, b -> new TreeMap<>(main)); return head(branches.get(branch)); }
  @Override public synchronized String commit(ProjectCfg line, String key, String branch, String message, List<FileChange> files) {
    var t = branches.computeIfAbsent(branch, b -> new TreeMap<>(main));
    for (var f : files) { if (f.delete()) t.remove(f.path()); else t.put(f.path(), f.content()); }
    commits.incrementAndGet(); return head(t);
  }
  @Override public synchronized String commit(ProjectCfg line, String key, String branch, String message, List<FileChange> files, String expectedHead) {
    if (!expectedHead.equals(head(branches.get(branch)))) throw new GateBlocked("head_changed", "PR 分支已变化，不能提交旧补丁");
    return commit(line, key, branch, message, files);
  }
  @Override public synchronized PrInfo openPr(ProjectCfg line, String key, String branch, String title, String body) {
    for (var p : prs.values()) if (p.branch().equals(branch)) return new PrInfo(p.number(), p.url(), branch, p.headSha(), true);
    int n = seq.incrementAndGet(); prOpened.incrementAndGet();
    var pr = new PrInfo(n, "https://example.test/pull/" + n, branch, head(branches.get(branch)), false); prs.put(n, pr); return pr;
  }
  private PrInfo prOf(int n) { return prs.get(n); }
  @Override public List<PrFile> prFiles(ProjectCfg line, int pr) {
    var t = branches.get(prOf(pr).branch()); List<PrFile> out = new ArrayList<>();
    for (var e : t.entrySet()) if (!e.getValue().equals(main.get(e.getKey()))) out.add(new PrFile(e.getKey(), e.getValue().split("\n", -1).length, 0, main.containsKey(e.getKey()) ? "modified" : "added"));
    return out;
  }
  @Override public CiState ci(ProjectCfg line, String key, String branch, String headSha, TaskCtx.Ci prev, boolean force, String taskId) {
    if (ciFailure != null) throw ciFailure;
    if (ciResultOverride != null) return ciResultOverride;
    var t = branches.get(branch); boolean naive = t != null && t.values().stream().anyMatch(v -> v.contains("return Math.min(max, n);") || v.contains("replace(/ /g, '-')"));
    if (ciSetupBroken) return new CiState("failed", 4243L, "https://example.test/runs/4243", 0.5, 1, 1, 0, SETUP_LOG.replace("#RUN#", String.valueOf(4243 + commits.get())), true, List.of(4243L + commits.get()), "failure");
    boolean fail = ciAlwaysFail || naive;
    return new CiState(fail ? "failed" : "passed", 4242L, "https://example.test/runs/4242", 1.5, 1, fail ? 1 : 0, 0.91,
        fail ? "[ci] 步骤失败：Run ./scripts/test.sh\nnot ok 1 - clamp(-1, 0, 10) => 0\n# fail 1" : "ok", true, List.of(4242L), fail ? "failure" : "success");
  }
  @Override public synchronized MergeResult merge(ProjectCfg line, String key, int pr, String expectedSha, String title) {
    var p = prOf(pr); if (merged.contains("#" + pr)) return new MergeResult("m" + pr, true);
    if (!expectedSha.equals(head(branches.get(p.branch())))) throw new GateBlocked("head_changed", "head 已变化");
    for (var f : prFiles(line, pr)) if (f.path().startsWith(".github/")) throw new GateBlocked("governance_file", "PR 修改了治理文件 " + f.path());
    main.putAll(branches.get(p.branch())); merged.add("#" + pr); return new MergeResult(U.sha256("m" + pr).substring(0, 40), false);
  }
  public static final String SETUP_LOG = "run ##RUN# failure：0/1 个 job 成功（共 1 个 run）\n[test] 步骤失败：Install\nnpm error code EUSAGE\nnpm error The `npm ci` command can only install with an existing package-lock.json or\nnpm error enoent Could not read package.json: Error: ENOENT: no such file or directory, open '/home/runner/work/lms/lms/package.json'\n##[error]Process completed with exit code 254.";
  @Override public BaseCi baseCi(ProjectCfg line) { return baseCiFailing ? new BaseCi("failed", "https://example.test/runs/main", "m1", SETUP_LOG.replace("#RUN#", "1")) : new BaseCi("passed", "https://example.test/runs/main", "m1", ""); }
  @Override public Health health(ProjectCfg line, String mergeSha) {
    double bad = mainUnhealthy ? 100 : 0;
    return new Health(Map.of("error_rate_delta_pp", List.of(bad, bad, bad), "error_rate_abs_pct", List.of(bad, bad, bad), "p95_regression_pct", List.of(0.0, 0.0, 0.0), "restart_count", List.of(0.0, 0.0, 0.0)), "fake", List.of(), mainUnhealthy ? "failed" : "passed", "https://example.test/runs/main", 1L);
  }
  @Override public synchronized ReleaseInfo release(ProjectCfg line, String key, String tag, String sha, String name, String notes) { if (!releases.contains(tag)) releases.add(tag); return new ReleaseInfo(releases.indexOf(tag) + 1, tag, "https://example.test/releases/" + tag, sha); }
  @Override public boolean verifyRelease(ProjectCfg line, String tag, String sha) { return releases.contains(tag); }
  @Override public synchronized String revert(ProjectCfg line, String key, String mergeSha, String branch, String title) { reverts.add(branch); return "https://example.test/pull/revert-" + branch; }
  @Override public Map<String, Object> status() { return Map.of("mode", "fake", "dryRun", false, "ciDryRun", false, "tokenConfigured", true, "apiHost", "example.test", "prometheusConfigured", false); }
}
