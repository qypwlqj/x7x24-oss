package com.x7x24.review;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 一次逐行审查：按顺序试模型（主模型 → 备用模型 deepseek-chat 等），每次失败都带上一次的 ocr 会话 --resume，
 * 已审完的文件不重审。全部审完、或只因 Token 预算没审完（按部分审查继续）就停；模型都试过仍没审完，交给调用方停下等人处理。
 */
public final class CodeReviewRun {
  private CodeReviewRun() {}
  /** 记录模型调用（挂到逐行审查节点）：同一次审查里换模型合成一条 */
  public interface Listener { void started(String model); void finished(String model, String state); }
  public record Attempt(String model, String state, String error, String sessionId, long tokens, double usd) {
    public Map<String, Object> map() { Map<String, Object> m = new LinkedHashMap<>(); m.put("model", model); m.put("state", state); if (error != null) m.put("error", error); if (sessionId != null) m.put("sessionId", sessionId); m.put("tokens", tokens); m.put("usd", usd); return m; }
  }
  /** result：最后一次拿到的结果（可能为空）；sessionId：可以 --resume 的会话；cancelled：被暂停 / 取消 */
  public record Outcome(OcrResult result, String sessionId, List<Attempt> attempts, boolean cancelled, String error) {
    public boolean done() { return result != null && (result.complete() || result.onlyBudget()); }
    public long tokens() { return attempts.stream().mapToLong(Attempt::tokens).sum(); }
    public double usd() { return Math.round(attempts.stream().mapToDouble(Attempt::usd).sum() * 1e6) / 1e6; }
    public List<String> models() { return attempts.stream().map(Attempt::model).filter(Objects::nonNull).distinct().toList(); }
  }

  public static Outcome run(OcrRunner runner, OcrRunner.Request base, List<OcrRunner.Llm> models, String resume, BooleanSupplier cancelled, Listener listener, Consumer<String> log) {
    List<Attempt> attempts = new ArrayList<>(); OcrResult last = null; String session = resume; String error = null;
    List<OcrRunner.Llm> chain = models.isEmpty() ? Collections.singletonList(null) : models;
    for (int i = 0; i < chain.size(); i++) {
      if (cancelled.getAsBoolean()) return new Outcome(last, session, attempts, true, error);
      var llm = chain.get(i); String model = llm == null ? null : llm.model();
      if (listener != null && model != null) listener.started(model);
      var req = new OcrRunner.Request(base.jobKey() + ":" + i, base.reviewKey(), base.repo(), base.baseRef(), base.headSha(), base.background(), llm, session,
          base.maxTokensBudget(), base.timeoutMin(), base.concurrency(), base.wallClockMin(), base.patches());
      OcrRunner.Outcome o;
      try { o = runner.review(req, cancelled, log); } catch (RuntimeException e) { o = OcrRunner.Outcome.failed("FAILED", com.x7x24.adapters.Redactor.redact(e)); }
      var r = o.result();
      String used = r != null && r.model() != null ? r.model() : model;
      long tok = r == null ? 0 : r.totalTokens() > 0 ? r.totalTokens() : r.inputTokens() + r.outputTokens();
      double usd = r == null || llm == null ? 0 : (r.inputTokens() * llm.priceInPer1m() + r.outputTokens() * llm.priceOutPer1m()) / 1_000_000d;
      if (r != null) { last = r; if (r.sessionId() != null) session = r.sessionId(); }
      boolean ok = r != null && (r.complete() || r.onlyBudget());
      String err = ok ? null : o.error() != null ? o.error() : r == null ? "ocr 没有返回结果（" + o.state() + "）" : describe(r);
      attempts.add(new Attempt(used, ok ? "SUCCEEDED" : o.state().equals("SUCCEEDED") ? "INCOMPLETE" : o.state(), err, r == null ? null : r.sessionId(), tok, usd));
      if (listener != null && model != null) listener.finished(used, ok ? "SUCCEEDED" : "CANCELLED".equals(o.state()) ? "CANCELLED" : "FAILED");
      if (ok) return new Outcome(r, session, attempts, false, null);
      error = err;
      if ("CANCELLED".equals(o.state()) || cancelled.getAsBoolean()) return new Outcome(last, session, attempts, true, error);
      if (i + 1 < chain.size() && log != null) log.accept("模型 " + model + " 没审完（" + err + "），换 " + chain.get(i + 1).model() + " 接着审");
    }
    return new Outcome(last, session, attempts, false, error);
  }
  static String describe(OcrResult r) {
    if (r.failed().isEmpty()) return "ocr 结束状态 " + r.status() + (r.message() == null || r.message().isBlank() ? "" : "：" + r.message());
    var f = r.failed().getFirst();
    return r.failed().size() + " 个文件没审完（" + (f.classification() == null ? "" : f.classification() + "：") + com.x7x24.util.U.ellipsis(f.reason(), 160) + "）";
  }
}
