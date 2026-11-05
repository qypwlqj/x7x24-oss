package com.x7x24.llm;

import java.util.concurrent.Semaphore;

/** 客户端限流：并发上限 + 相邻请求最小间隔，所有 provider 共享一个 gate，避免评审委员会并行时触发服务端 429。 */
public class LlmGate {
  private final Semaphore sem; private final long minIntervalMs; private long next = 0;
  public LlmGate(int maxConcurrent, long minIntervalMs) { this.sem = new Semaphore(Math.max(1, maxConcurrent)); this.minIntervalMs = Math.max(0, minIntervalMs); }
  public static LlmGate unlimited() { return new LlmGate(64, 0); }
  public void acquire() throws InterruptedException {
    sem.acquire();
    if (minIntervalMs <= 0) return;
    long wait; synchronized (this) { long now = System.currentTimeMillis(); long at = Math.max(now, next); next = at + minIntervalMs; wait = at - now; }
    if (wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException e) { sem.release(); throw e; } }
  }
  public void release() { sem.release(); }
}
