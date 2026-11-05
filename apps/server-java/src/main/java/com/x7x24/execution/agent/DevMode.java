package com.x7x24.execution.agent;

import com.x7x24.engine.ProjectCfg;
import com.x7x24.engine.Task;
import java.util.Set;

/**
 * 开发方式：sandbox（沙盒，默认）或 legacy（快速方式：模型直接写补丁）。
 * 优先级：任务覆盖 > 项目设置 > 默认沙盒。项目设置每轮读当前值，不进任务快照。
 */
public final class DevMode {
  private DevMode() { }
  public static final String SANDBOX = "sandbox", LEGACY = "legacy";
  public static final Set<String> ALL = Set.of(SANDBOX, LEGACY);
  public static boolean valid(String v) { return v != null && ALL.contains(v); }
  public static final int LEGACY_MAX_FILES = 10;
  public record Choice(String mode, String source) { public boolean legacy() { return LEGACY.equals(mode); } }

  public static Choice resolve(Task t, ProjectCfg p) {
    String o = t == null || t.context == null ? null : t.context.devModeOverride;
    if (valid(o)) return new Choice(o, "task");
    String m = p == null ? null : p.devMode;
    if (valid(m)) return new Choice(m, "project");
    return new Choice(SANDBOX, "default");
  }
  /** null / 空 = 清除（跟随项目）。 */
  public static String normalize(String v) {
    if (v == null || v.isBlank() || "inherit".equals(v)) return null;
    if (!valid(v)) throw new IllegalArgumentException("开发方式只能是 sandbox（沙盒）或 legacy（快速方式）");
    return v;
  }
  /** 快速方式遇到偏大的需求（规划超过 10 个文件或分多段）：只提示，不自动切换。 */
  public static String largeHint(String mode, com.x7x24.agents.Models.Plan plan) {
    if (!LEGACY.equals(mode) || plan == null) return null;
    boolean big = (plan.files() != null && plan.files().size() > LEGACY_MAX_FILES) || (plan.subtasks() != null && plan.subtasks().size() > 1);
    return big ? "这个需求可能超出快速方式能力，建议改用沙盒" : null;
  }
  public static String zh(String mode) { return LEGACY.equals(mode) ? "快速方式" : "沙盒"; }
}
