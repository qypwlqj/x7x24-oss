package com.x7x24.engine;

import com.x7x24.core.Status;

import java.util.function.Consumer;

/** 一步处理的结果：停留 / 迁移 / 废弃。mut 在事务内应用到任务副本上。 */
public sealed interface Decision {
  Consumer<Task> NOOP = t -> {};
  record Stay(Double waitMin, Consumer<Task> mut) implements Decision {}
  /** facts：给界面生成人话说明的结构化事实（可为 null） */
  record Anomaly(String type, String detail, java.util.Map<String, Object> facts) { public Anomaly(String type, String detail) { this(type, detail, null); } }
  record Move(Status to, String edge, Double waitMin, Consumer<Task> mut, Object payload, Anomaly anomaly, String iterTrigger) implements Decision {}
  record Discard(String reason, String edge, Object detail, Consumer<Task> mut) implements Decision {}
  record Block(String code, Object detail, Consumer<Task> mut) implements Decision {}

  static Stay stay(double waitMin) { return new Stay(waitMin, NOOP); }
  static Stay stay(double waitMin, Consumer<Task> m) { return new Stay(waitMin, m); }
  static Stay never(Consumer<Task> m) { return new Stay(null, m); }
  static Move move(Status to, String edge) { return new Move(to, edge, null, NOOP, null, null, null); }
  static Move move(Status to, String edge, Consumer<Task> m, Object payload) { return new Move(to, edge, null, m, payload, null, null); }
  static Move anomaly(String type, String detail) { return new Move(Status.ANOMALY, "anomaly", null, NOOP, null, new Decision.Anomaly(type, detail), null); }
  static Discard discard(String reason, String edge, Object detail) { return new Discard(reason, edge, detail, NOOP); }
  static Block block(String code, Object detail) { return new Block(code, detail, NOOP); }
}
