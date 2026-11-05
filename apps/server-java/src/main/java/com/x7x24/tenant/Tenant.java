package com.x7x24.tenant;

import org.springframework.web.context.request.RequestContextHolder;

import java.util.function.Supplier;

/**
 * 当前工作区上下文（租户边界）。
 * <ul>
 *   <li>Web 请求：由 {@link WorkspaceFilter} 设置；请求线程里没有设置时 {@link #id()} 直接抛异常（fail closed，不回退到默认工作区）。</li>
 *   <li>后台线程（引擎 tick、巡查、定时任务）：没有显式上下文时为「系统模式」，仓储层不过滤；需要按工作区执行时用 {@link #run}。</li>
 * </ul>
 */
public final class Tenant {
  private Tenant() {}
  /** 中性、无默认所有者的遗留工作区（新安装快照） */
  public static final String LEGACY = "ws_legacy";
  public static final String PLATFORM = "_platform";
  private static final ThreadLocal<String> CUR = new ThreadLocal<>();
  private static final ThreadLocal<Boolean> UNSCOPED = new ThreadLocal<>();

  public static class Missing extends IllegalStateException { public Missing() { super("缺少工作区上下文"); } }

  /** 当前工作区；系统模式返回 null；Web 请求线程缺上下文时抛异常 */
  public static String current() {
    String v = CUR.get(); if (v != null) return v;
    if (RequestContextHolder.getRequestAttributes() != null) throw new Missing();
    return null;
  }
  /** 必须有工作区（写入租户数据时） */
  public static String id() { String v = current(); if (v == null) throw new Missing(); return v; }
  public static boolean system() { return CUR.get() == null && RequestContextHolder.getRequestAttributes() == null; }
  /** 系统模式下写入根实体（产品线）时的归属：遗留工作区（启动种子数据） */
  public static String forInsert() { String v = current(); return v == null ? LEGACY : v; }

  /** 原样取出当前上下文（不抛异常；用于把上下文传给子线程） */
  public static String raw() { return CUR.get(); }
  /** 把当前上下文包进子线程任务 */
  public static <T> java.util.concurrent.Callable<T> wrap(java.util.concurrent.Callable<T> c) { String ws = CUR.get(); return () -> { String prev = CUR.get(); if (ws == null) CUR.remove(); else CUR.set(ws); try { return c.call(); } finally { if (prev == null) CUR.remove(); else CUR.set(prev); } }; }
  /** 工作区 scope 键：系统模式 / 平台 → null */
  public static String scope() { String v = current(); return v == null || PLATFORM.equals(v) ? null : v; }
  /**
   * 配置存储范围：遗留工作区与平台共用原来的全局配置（治理、流程、模型、GitHub 凭据、运行模式），保证迁移后行为完全不变；
   * 其他工作区返回自己的 id。系统 / 平台上下文返回 null。
   */
  public static String storeScope() { String v = scope(); return v == null || LEGACY.equals(v) ? null : v; }
  /** 写运行时数据时的归属：当前工作区；系统 / 平台上下文 = _platform */
  public static String writeId() { String v = current(); return v == null ? PLATFORM : v; }
  /** 读运行时数据时的过滤：工作区上下文 → 该工作区；系统 / 平台上下文 → 不过滤 */
  public static String readFilter() { String v = current(); return v == null || PLATFORM.equals(v) ? null : v; }
  /** 数据库行级安全的会话值：工作区 id；系统 / 平台 / 无上下文 / {@link #unscoped} 内 = 空串（不限制） */
  public static String dbScope() {
    if (Boolean.TRUE.equals(UNSCOPED.get())) return "";
    String v = CUR.get(); return v == null || PLATFORM.equals(v) ? "" : v;
  }
  /** 平台级操作（审计哈希链：链头、追加、校验跨所有工作区）在这里面新取的数据库连接不受行级安全限制 */
  public static <T> T unscoped(Supplier<T> f) {
    Boolean prev = UNSCOPED.get(); UNSCOPED.set(true);
    try { return f.get(); } finally { if (prev == null) UNSCOPED.remove(); else UNSCOPED.set(prev); }
  }
  public static void set(String ws) { if (ws == null) CUR.remove(); else CUR.set(ws); }
  public static void clear() { CUR.remove(); }
  public static <T> T run(String ws, Supplier<T> f) { String prev = CUR.get(); CUR.set(ws); try { return f.get(); } finally { if (prev == null) CUR.remove(); else CUR.set(prev); } }
  public static void run(String ws, Runnable f) { run(ws, () -> { f.run(); return null; }); }

  /** SQL 片段：`AND <col>=?` 与参数；系统模式不过滤 */
  public static String and(String col) { return current() == null ? "" : " AND " + col + "=?"; }
  public static Object[] args(Object... a) {
    String ws = current(); if (ws == null) return a;
    Object[] r = java.util.Arrays.copyOf(a, a.length + 1); r[a.length] = ws; return r;
  }
}
