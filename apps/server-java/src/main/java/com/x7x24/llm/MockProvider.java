package com.x7x24.llm;

import java.util.function.Function;

/** 仅用于测试：按 role 返回预置 JSON。 */
public class MockProvider implements LlmProvider {
  private final String model; private final Function<Request, String> fn;
  public MockProvider(String model, Function<Request, String> fn) { this.model = model; this.fn = fn; }
  @Override public String model() { return model; }
  @Override public Response chat(Request r) { String t = fn.apply(r); return new Response(t, model, r.promptVersion() == null ? "v1" : r.promptVersion(), (r.prompt().length() + t.length()) / 4, 0.0001); }
}
