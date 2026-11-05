package com.x7x24.llm;

import java.util.List;

/** exec：执行组；cheap：产品线 soft 预算后的降级路由；judges：评审委员会 3 位互相隔离的评审员；arbiter：仲裁。 */
public record Providers(String mode, LlmProvider exec, LlmProvider cheap, List<LlmProvider> judges, LlmProvider arbiter) {
  public boolean llm() { return "llm".equals(mode); }
}
