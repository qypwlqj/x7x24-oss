package com.x7x24.review;

import com.x7x24.adapters.DeliveryTools.FilePatch;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 跑一次 open-code-review（官方 skill「open-code-review」：ocr 自己调模型）。
 * 生产：{@link com.x7x24.execution.agent.RemoteOcrRunner}（runner 沙箱里跑钉死版本的 ocr）；离线 / 测试：{@link RuleOcrRunner}。
 */
public interface OcrRunner {
  /** 模型端点。token 只在内存里转交给 runner，再经环境变量交给 ocr；toString 不含密钥。 */
  record Llm(String model, String url, String token, String protocol, double priceInPer1m, double priceOutPer1m) {
    @Override public String toString() { return "Llm[" + model + "]"; }
  }
  /** reviewKey：同一任务同一节点固定，runner 用它复用仓库目录和 ocr 会话（--resume 依赖）。patches 只给离线规则模式用。 */
  record Request(String jobKey, String reviewKey, String repo, String baseRef, String headSha, String background, Llm llm, String resume,
                 long maxTokensBudget, int timeoutMin, int concurrency, int wallClockMin, List<FilePatch> patches) {
    @Override public String toString() { return "OcrRequest[" + reviewKey + "@" + headSha + "," + llm + (resume == null ? "" : ",resume") + "]"; }
  }
  /** state：SUCCEEDED（拿到了 ocr 的 JSON，不代表全部审完）/ FAILED / TIMED_OUT / CANCELLED / BUSY */
  record Outcome(String state, String error, OcrResult result) {
    public static Outcome failed(String state, String error) { return new Outcome(state, error, null); }
  }
  Outcome review(Request r, BooleanSupplier cancelled, Consumer<String> log);
  /** 是否真的调模型（离线规则模式不调，没有密钥要求） */
  default boolean usesModels() { return true; }
}
