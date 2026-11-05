package com.x7x24.llm;

import com.x7x24.agents.Models;
import com.x7x24.engine.Task;
import com.x7x24.gov.AuditLog;
import com.x7x24.util.U;
import org.springframework.stereotype.Component;
import java.util.*;

/** 调用级模型证据：只记录已选择/已上报的标识，不从默认配置推测；上下文在派发前固定。 */
@Component
public class ModelCalls {
  public static final String RUNTIME_ID = U.uuid();
  private final AuditLog audit;
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ModelCalls.class);
  public ModelCalls(AuditLog audit) { this.audit = audit; }
  public LlmProvider observe(LlmProvider provider, Task task, String kind, String role) {
    return observe(provider, task, null, kind, role, null, 1);
  }
  public LlmProvider observe(LlmProvider provider, Task task, Models.ExecutionAddress address, String kind, String role, String group, int total) {
    if (task == null || task.id == null) return provider;
    var context = context(task, address, kind, role, group, total, "llm");
    return provider.observing(() -> new Session(context, U.uuid()));
  }
  public LlmProvider observeReview(LlmProvider provider, Task task, String role, String runId, String fileGroupId, int slot) {
    return observeReview(provider, task, role, runId, fileGroupId, slot, null);
  }
  /** node 非空时调用记录挂到该节点（逐行审查借验收的席位选模型，但记录要落在「逐行审查」节点下） */
  public LlmProvider observeReview(LlmProvider provider, Task task, String role, String runId, String fileGroupId, int slot, String node) {
    if (task == null || task.id == null) return provider;
    var context = context(task, null, "review", role, null, 1, "llm");
    if (node != null && !node.isBlank()) context.put("node", node);
    context.put("runId", runId); context.put("fileGroupId", fileGroupId); context.put("slot", slot);
    return provider.observing(() -> new Session(context, U.uuid()));
  }
  /** 逐行审查（ocr 在 runner 里调模型）：一次审查一条记录，挂到逐行审查节点；换备用模型时同一条记录里记下切换 */
  public Session reviewRun(Task task, String runId, String node) {
    var context = context(task, null, "review", "review.code", null, 1, "ocr");
    if (node != null && !node.isBlank()) context.put("node", node);
    context.put("runId", runId);
    return new Session(context, U.uuid());
  }
  public Session remote(Task task, Models.ExecutionAddress address, String jobId) {
    return remote(task, address, jobId, "dev.code");
  }
  public Session remote(Task task, Models.ExecutionAddress address, String jobId, String role) {
    return new Session(context(task, address, "execution", role, null, 1, "agent"), jobId);
  }
  private Map<String, Object> context(Task t, Models.ExecutionAddress address, String kind, String role, String group, int total, String source) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("taskId", t.id); p.put("iteration", t.currentIteration);
    p.put("node", address != null ? address.nodeInstanceId() : t.currentNode != null ? t.currentNode : t.currentStage != null ? t.currentStage : t.status);
    p.put("runId", address != null ? address.attemptId() : t.id + ":v" + t.version + ":i" + t.currentIteration + ":s" + t.spentIterations);
    p.put("kind", kind); p.put("role", role); p.put("total", total); p.put("source", source);
    p.put("runtimeId", RUNTIME_ID);
    if (group != null) p.put("groupId", group);
    return p;
  }
  public final class Session implements LlmProvider.CallListener {
    private final Map<String, Object> context; private final String callId;
    private String model, state; private int attempt, sequence;
    Session(Map<String, Object> context, String callId) { this.context = context; this.callId = callId; emit("QUEUED", null); }
    public void started(String next) {
      next = clean(next); if (next == null) return;
      String previous = model;
      boolean switched = "FAILED".equals(state) && previous != null && !previous.equals(next);
      model = next; attempt++;
      emit("RUNNING", switched ? previous : null);
    }
    public void remoteRunning() {
      if ("QUEUED".equals(state)) emit("RUNNING", null);
    }
    public boolean hasModel() { return model != null; }
    /** runner 的重复模型上报不重复写事件；变更模型保留切换轨迹。 */
    public void reported(String next) {
      next = clean(next); if (next == null || next.equals(model) && "RUNNING".equals(state)) return;
      String previous = model; model = next; attempt++; emit("RUNNING", previous);
    }
    public void finished(String actual, String result) {
      actual = clean(actual); if (actual != null) model = actual;
      if (!result.equals(state)) emit(result, null);
    }
    public void finish(String result) { finished(null, result); }
    private String clean(String value) { return value == null || value.isBlank() ? null : U.ellipsis(com.x7x24.adapters.Redactor.redact(value).strip(), 512); }
    private void emit(String next, String previous) {
      state = next;
      Map<String, Object> p = new LinkedHashMap<>(context);
      String taskId = (String) p.remove("taskId"); int iteration = (Integer) p.remove("iteration");
      p.put("callId", callId); p.putIfAbsent("groupId", callId); p.put("state", state); p.put("attempt", attempt);
      if (model != null) p.put("model", model);
      if (previous != null) p.put("previousModel", previous);
      try {
        audit.append(new AuditLog.Input(taskId, iteration, "MODEL_CALL", null, null, null, U.ellipsis(String.valueOf(p.get("role")), 99), model == null ? null : U.ellipsis(model, 99), null, p, "model-call:" + callId + ":" + ++sequence, audit.now()));
      } catch (RuntimeException e) { log.warn("模型调用状态记录失败 taskId={} callId={} state={} error={}", taskId, callId, state, e.getClass().getSimpleName()); }
    }
  }
}
