package com.x7x24.llm;

import java.util.function.Supplier;

/**
 * 单次业务步骤的调用策略（目前只给逐行审查用）：覆盖 provider 自己的超时和重试次数，并按错误类型分开计数。
 * 用 ThreadLocal 传递，只在 {@link #with} 包住的调用里生效；没有设置时 provider 完全按原来的全局配置走（开发 agent、委员会等不受影响）。
 * - connectRetries：连不上、读超时、IO 异常（ResourceAccessException 等）再试几次
 * - serverRetries：429 / 5xx 再试几次
 * - deadlineAt：整组的截止时间（毫秒时间戳，0 = 不限）；到点不再发起新请求，抛 {@link DeadlineExceeded}
 */
public record CallPolicy(int timeoutSec, int connectRetries, int serverRetries, long deadlineAt, Listener listener) {
  /** 每次发起请求前回调：attempt 从 1 开始；errorKind 是上一次失败的类型（第一次为 null） */
  public interface Listener { void attempt(String model, int attempt, int maxAttempts, String errorKind); }
  public static final String TIMEOUT = "timeout", CONNECT = "connect", RATE_LIMIT = "rate_limit", SERVER = "server";

  public static class DeadlineExceeded extends RuntimeException {
    public DeadlineExceeded(String m) { super(m); }
  }

  private static final ThreadLocal<CallPolicy> CURRENT = new ThreadLocal<>();
  public static CallPolicy current() { return CURRENT.get(); }
  public static <T> T with(CallPolicy p, Supplier<T> action) {
    CallPolicy before = CURRENT.get(); CURRENT.set(p);
    try { return action.get(); } finally { if (before == null) CURRENT.remove(); else CURRENT.set(before); }
  }

  public boolean expired(long now) { return deadlineAt > 0 && now >= deadlineAt; }
  public long remainingMs(long now) { return deadlineAt <= 0 ? Long.MAX_VALUE : Math.max(0, deadlineAt - now); }
  /** 这类错误一共能试几次（含第一次） */
  public int maxAttempts(String kind) { return 1 + (RATE_LIMIT.equals(kind) || SERVER.equals(kind) ? serverRetries : connectRetries); }
  public void notify(String model, int attempt, int maxAttempts, String kind) {
    if (listener == null) return;
    try { listener.attempt(model, attempt, maxAttempts, kind); } catch (RuntimeException ignore) { /* 进度回调失败不影响调用 */ }
  }

  /** HTTP 状态码 → 错误类型；0 表示不是 HTTP 错误（连不上 / 超时 / IO） */
  public static String kind(int status, Throwable e) {
    if (status == 429) return RATE_LIMIT;
    if (status >= 500) return SERVER;
    for (Throwable c = e; c != null; c = c.getCause()) {
      String n = c.getClass().getSimpleName(), m = String.valueOf(c.getMessage()).toLowerCase(java.util.Locale.ROOT);
      if (c instanceof java.net.http.HttpTimeoutException || c instanceof java.net.SocketTimeoutException || n.contains("Timeout") || m.contains("timed out") || m.contains("timeout")) return TIMEOUT;
      if (c.getCause() == c) break;
    }
    return CONNECT;
  }
}
