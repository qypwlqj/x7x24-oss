package com.x7x24.adapters;

import com.x7x24.engine.ProjectCfg;
import com.x7x24.engine.TaskCtx;

import java.util.*;

/**
 * 交付工具抽象（Issue / Git / CI / Release / 健康度）。生产实现 GitHubTools（GitHub REST + Actions）；测试用内存实现。
 * 所有写操作带幂等键 key（以任务 ID 开头），并受 kill switch / 禁区策略 / dry-run 约束。
 */
public interface DeliveryTools {
  record IssueInfo(int number, String title, String body, String url, String author, List<String> labels, String createdAt) {}
  record FileChange(String path, String content, boolean delete, int rawLines) {
    /** rawLines：相对原文的真实 diff 行数；-1 = 未知（引擎读取原文后计算） */
    public FileChange(String path, String content, boolean delete) { this(path, content, delete, -1); }
    public static FileChange put(String p, String c) { return new FileChange(p, c, false); }
  }
  record PrInfo(int number, String url, String branch, String headSha, boolean existed) {}
  record PrFile(String path, int additions, int deletions, String status) {}
  /** PR 里一个文件的 unified diff（GitHub pulls/files 的 patch 字段；超大文件 GitHub 不给 patch 时为 null） */
  record FilePatch(String path, String status, int additions, int deletions, String patch) {}
  record CiState(String state, Long runId, String url, double minutes, int total, int failed, double coverage, String log, boolean dispatched, List<Long> runIds, String conclusion, boolean logComplete) {
    public CiState(String state, Long runId, String url, double minutes, int total, int failed, double coverage, String log, boolean dispatched, List<Long> runIds, String conclusion) {
      this(state, runId, url, minutes, total, failed, coverage, log, dispatched, runIds, conclusion, true);
    }
  }
  record MergeResult(String sha, boolean alreadyMerged) {}
  record ReleaseInfo(long id, String tag, String url, String sha) {}
  record Health(Map<String, List<Double>> samples, String source, List<String> notes, String mainRunState, String mainRunUrl, Long mainRunId) {}
  /** 目标分支（主干）上同一个 CI workflow 最近一次完成的结果：用来判断失败是不是本来就有、和这次改动无关 */
  record BaseCi(String state, String url, String headSha, String log) {}
  record Diagnostic(String sourceRef, String title, String url, String sha, String log) {}
  /** 固定的首次提交；由服务端生成，模型和前端不能指定任意文件。 */
  record RepositoryInit(String repo, String branch, String path, String content, String message) {}
  static RepositoryInit repositoryInit(ProjectCfg project) {
    return new RepositoryInit(project.repo, project.baseBranch, "README.md", "# " + project.repoName() + "\n\nInitialized by x7x24.\n", "chore: initialize repository");
  }

  /** dry-run：写操作只被记录、不发送 */
  class DryRunPlanned extends RuntimeException { public DryRunPlanned(String op) { super("dry-run: 计划调用 " + op + "（未发送）"); } }
  /** 治理闸门拒绝（PR 改了治理文件 / head 已变化 / 必需检查未过 / 不可合并） */
  class GateBlocked extends RuntimeException { public final String category; public GateBlocked(String category, String msg) { super(msg); this.category = category; } }
  class MergeConflict extends RuntimeException { public MergeConflict(String m) { super(m); } }

  boolean live();
  /** 主干 CI 最近一次结果；拿不到时返回 null（不影响主流程） */
  default BaseCi baseCi(ProjectCfg line) { return null; }
  /** 把目标分支最新内容合进 PR 分支（GitHub「Update branch」），返回新的头提交；不支持 / 失败返回 null */
  default String updateBranch(ProjectCfg line, String key, int pr, String branch, String oldHead) { return null; }
  List<IssueInfo> listSignalIssues(ProjectCfg line);
  IssueInfo createIssue(ProjectCfg line, String key, String title, String body, List<String> labels);
  void comment(ProjectCfg line, String key, int issue, String body);
  void labels(ProjectCfg line, String key, int issue, List<String> add, List<String> remove);
  void closeIssue(ProjectCfg line, String key, int issue, boolean completed);
  default void reopenIssue(ProjectCfg line, String key, int issue) { throw new GateBlocked("issue_reopen_unavailable", "当前适配器不支持重新打开 Issue"); }
  String readFile(ProjectCfg line, String ref, String path);
  List<String> listDir(ProjectCfg line, String ref, String dir);
  default String repositoryHead(ProjectCfg project) { return project.baseBranch; }
  /** 开发前核验受控工程骨架；ref 指向本次实际开发的分支或提交。 */
  default void assertDevelopmentReady(ProjectCfg project, String ref) {}
  /** 核验配置的 CI 工作流存在、启用且可读；缺少运行记录仍属于正常等待。 */
  default void assertCiReady(ProjectCfg project) {}
  /** 初始化已有的空仓库；仓库已初始化时只返回基线。首次提交使用仓库级操作日志以跨任务去重。 */
  default String initializeRepository(ProjectCfg project, String key) { throw new GateBlocked("repository_not_initialized", "当前交付工具不支持初始化仓库"); }
  default List<String> sourceFiles(ProjectCfg project, String ref) {
    List<String> files = new ArrayList<>(); for (String root : project.discovery.roots) files.addAll(listDir(project, ref, root)); return files;
  }
  default List<Diagnostic> testFailures(ProjectCfg project) { return List.of(); }
  String ensureBranch(ProjectCfg line, String key, String branch);
  /** 多文件提交（git data API）。无变更时返回当前 head（no-op）。 */
  String commit(ProjectCfg line, String key, String branch, String message, List<FileChange> files);
  /** SHA 固定的补丁只能应用于原提交；适配器必须在写入处再次核对。 */
  default String commit(ProjectCfg line, String key, String branch, String message, List<FileChange> files, String expectedHead) {
    throw new GateBlocked("head_changed", "当前适配器不支持核对补丁基线，不能提交固定基线的变更");
  }
  PrInfo openPr(ProjectCfg line, String key, String branch, String title, String body);
  List<PrFile> prFiles(ProjectCfg line, int pr);
  /** 逐行审查用：每个变更文件的 diff 片段。默认不提供（调用方退回读整文件）。 */
  default List<FilePatch> prPatches(ProjectCfg line, int pr) { return List.of(); }
  /** PR 当前 head 提交（试跑逐行审查时读文件用）；不支持返回 null */
  default String prHead(ProjectCfg line, int pr) { return null; }
  CiState ci(ProjectCfg line, String key, String branch, String headSha, TaskCtx.Ci prev, boolean forceDispatch, String taskId);
  MergeResult merge(ProjectCfg line, String key, int pr, String expectedSha, String title);
  /** 合并后 base 分支上该提交触发的 CI + 近期健康度 */
  Health health(ProjectCfg line, String mergeSha);
  ReleaseInfo release(ProjectCfg line, String key, String tag, String sha, String name, String notes);
  boolean verifyRelease(ProjectCfg line, String tag, String sha);
  /** 回滚：开一个还原 PR（不直推、不强推、不删 tag）。返回 PR URL。 */
  String revert(ProjectCfg line, String key, String mergeSha, String branch, String title);
  Map<String, Object> status();
}
