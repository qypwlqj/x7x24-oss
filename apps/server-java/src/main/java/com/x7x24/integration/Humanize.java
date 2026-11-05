package com.x7x24.integration;

import java.util.Map;

/** 把适配器内部操作名 / GitHub 错误翻译成用户能读懂的话。 */
public final class Humanize {
  private Humanize() {}
  private static final Map<String, String> OPS = Map.ofEntries(
      Map.entry("repository.initialize", "初始化仓库"), Map.entry("repository.initialize.reconcile", "核对首次提交"),
      Map.entry("issues.list", "读取需求 Issue"), Map.entry("issues.create", "创建 Issue"), Map.entry("issues.comment", "评论 Issue"), Map.entry("issues.label", "给 Issue 加标签"), Map.entry("issues.unlabel", "移除 Issue 标签"), Map.entry("issues.close", "关闭 Issue"),
      Map.entry("git.create_ref", "创建分支"), Map.entry("git.get_ref", "读取分支"), Map.entry("git.create_tree", "写入文件树"), Map.entry("git.create_commit", "提交代码"), Map.entry("git.update_ref", "更新分支"), Map.entry("git.read_rulesets", "读取分支保护规则"),
      Map.entry("pulls.create", "创建 PR"), Map.entry("pulls.find", "查找 PR"), Map.entry("pulls.get", "读取 PR"), Map.entry("pulls.files", "读取 PR 文件"), Map.entry("pulls.merge", "合并 PR"),
      Map.entry("ci.dispatch", "触发 CI"), Map.entry("ci.list_runs", "读取 CI 运行"), Map.entry("ci.run", "读取 CI 运行"), Map.entry("ci.jobs", "读取 CI 任务"), Map.entry("ci.job_log", "读取 CI 日志"), Map.entry("checks.list", "读取检查结果"),
      Map.entry("releases.create", "创建发布"), Map.entry("releases.get", "读取发布"), Map.entry("repo.get", "读取仓库信息"), Map.entry("project.branch", "检查分支"), Map.entry("project.workflow", "检查 CI 文件"),
      Map.entry("contents.read", "读取文件"), Map.entry("contents.list", "读取目录"), Map.entry("user.get", "校验令牌"), Map.entry("user.repos", "读取仓库列表"), Map.entry("user.installations", "读取 App 安装"),
      Map.entry("integration.ci_bootstrap", "补充 CI 模板"), Map.entry("discovery.runs", "扫描 CI 失败"), Map.entry("discovery.tree", "扫描仓库目录"));
  public static String op(String op) { return OPS.getOrDefault(op, op); }
  public static String reason(Integer status, String note) {
    String n = note == null ? "" : note.toLowerCase();
    if (status == null || status == 0) return "网络不通或请求超时";
    if (status == 401) return "令牌无效或已过期，请重新连接 GitHub";
    if (status == 403 && n.contains("rate limit")) return "触发了 GitHub 限流，稍后会自动重试";
    if (status == 403 && (n.contains("upgrade") || n.contains("not available"))) return "当前 GitHub 套餐不支持这项功能";
    if (status == 403) return "没有权限（令牌或 App 缺少写入权限）";
    if (status == 404) return "目标不存在，或令牌没有访问权限";
    if (status == 409) return "与现有内容冲突";
    if (status == 422) return "GitHub 拒绝了请求内容（可能已存在或参数不合法）";
    if (status == 429) return "触发了 GitHub 限流";
    if (status >= 500) return "GitHub 服务暂时异常";
    return "请求失败（HTTP " + status + "）";
  }
  /** 一条调用记录 → 一句人话 */
  public static String call(String op, Integer status, boolean ok, boolean dryRun, String note) {
    if (dryRun) return op(op) + "：观察模式，已记录但未发送";
    return ok ? op(op) + "：成功" : op(op) + "失败：" + reason(status, note);
  }
  public static String deviceError(String code, String desc) {
    return switch (code == null ? "" : code) {
      case "device_flow_disabled" -> "这个 GitHub App 还没有开启设备码登录。请到 GitHub App 设置里勾选 “Enable Device Flow”，或先改用「粘贴令牌」。";
      case "incorrect_client_credentials", "Not Found" -> "Client ID 不正确或 App 不存在，请检查 GITHUB_OAUTH_CLIENT_ID。";
      case "expired_token" -> "验证码已过期，请重新获取。";
      case "access_denied" -> "你在 GitHub 上拒绝了授权。";
      case "unsupported_grant_type", "incorrect_device_code" -> "授权会话无效，请重新获取验证码。";
      default -> "GitHub 返回错误：" + (desc == null || desc.isBlank() ? String.valueOf(code) : desc);
    };
  }
}
