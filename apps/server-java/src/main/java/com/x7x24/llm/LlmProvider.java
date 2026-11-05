package com.x7x24.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** LLM 抽象：真实实现是 OpenAI 兼容 HTTP；测试用 MockProvider；无凭据时评审用 RuleJudgeProvider（确定性规则）。 */
public interface LlmProvider {
  record Request(String role, String system, String prompt, String promptVersion, String compactHint) {
    public Request(String role, String system, String prompt, String promptVersion) { this(role, system, prompt, promptVersion, null); }
  }
  record Response(String text, String model, String promptVersion, long tokens, double usd) {}
  /** 结构化输出校验：返回 null 表示通过，否则返回给模型看的错误说明（触发一次自动重试）。 */
  interface Validator { String validate(JsonNode json); }
  /** 重试后输出仍然不是合法 / 不满足结构的 JSON：调用方应降级处理（本类不可恢复）。 */
  class OutputException extends RuntimeException {
    public final long tokens; public final double usd; public final String model;
    public OutputException(String m) { this(m, null, 0, 0); }
    /** 带上失败调用已消耗的用量，调用方据此照常计费（否则失败任务显示 0 Token）。 */
    public OutputException(String m, String model, long tokens, double usd) { super(m); this.model = model; this.tokens = tokens; this.usd = usd; }
  }

  String model();
  Response chat(Request req);
  interface CallListener {
    void started(String model);
    void finished(String model, String state);
  }
  /** 每次逻辑调用创建独立监听器；路由实现可在同一次调用内报告多个实际候选。 */
  default LlmProvider observing(java.util.function.Supplier<CallListener> listeners) {
    var delegate = this;
    return new LlmProvider() {
      public String model() { return delegate.model(); }
      public Response chat(Request req) { return chat(req, null); }
      public Response chat(Request req, Validator v) { return run(() -> delegate.chat(req, v)); }
      public Response chatStream(Request req, Validator v, Stream s) { return run(() -> delegate.chatStream(req, v, s)); }
      private Response run(java.util.function.Supplier<Response> action) {
        var listener = listeners.get(); listener.started(delegate.model());
        try { var r = action.get(); listener.finished(r.model(), "SUCCEEDED"); return r; }
        catch (Cancelled e) { listener.finished(e.model, "CANCELLED"); throw e; }
        catch (RuntimeException e) { listener.finished(delegate.model(), e instanceof com.x7x24.workflow.WorkflowStore.Blocked ? "CANCELLED" : "FAILED"); throw e; }
      }
    };
  }
  /** 带结构化校验的调用；默认实现（规则 / mock 提供者）不做额外校验。 */
  default Response chat(Request req, Validator validator) { return chat(req); }

  /** 流式输出回调（对话逐字显示）。token 按到达顺序给出原始输出片段；reset = 之前给出的片段作废（校验失败重试 / 换模型） */
  interface Stream {
    void token(String text);
    default void reset() { }
    /** 推理模型的思考阶段开始（兼容仅展示状态的调用方）。 */
    default void thinking() { }
    /** 模型接口公开返回的思考片段，与正文 token 分开传递。 */
    default void reasoning(String text) { }
    /** 思考结束，在第一个正文 token 之前通知。 */
    default void reasoningDone() { }
    /** 返回 true 时尽快中止上游请求并抛 {@link Cancelled} */
    default boolean cancelled() { return false; }
  }
  /** 用户停止 / 连接断开：中止了上游请求。partial = 已收到的原始输出，tokens / usd 为估算用量 */
  class Cancelled extends RuntimeException {
    public final String partial; public final long tokens; public final double usd; public final String model;
    public Cancelled(String partial, String model, long tokens, double usd) { super("已停止"); this.partial = partial; this.model = model; this.tokens = tokens; this.usd = usd; }
  }
  /** 流式调用；默认实现（规则 / mock）整段算完后一次性给出 */
  default Response chatStream(Request req, Validator validator, Stream stream) {
    var r = chat(req, validator); if (stream != null) stream.token(r.text()); return r;
  }
}
