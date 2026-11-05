package com.x7x24.chat;

import java.util.function.Consumer;

/**
 * 从模型流式输出的 JSON（{"tool_calls":[…],"actions":[…],"guide":…,"reply":"…","draft":{…}}）里边收边取出顶层 reply 的文字。
 * - 只在确认这一步是最终回复（顶层 tool_calls 为空数组）后才把 reply 片段交给 onDelta；有工具调用的中间步骤不输出。
 * - reply 出现在 tool_calls 之前（模型没按顺序）时先缓存，等看到 tool_calls 再决定；看不到就留给调用方在结束时补发。
 * - 处理字符串转义和 \\uXXXX（含代理对）；容忍前面夹带 ```json 围栏或说明文字（从第一个 '{' 开始解析）。
 */
public final class ReplyStreamExtractor {
  enum Calls { UNKNOWN, EMPTY, NON_EMPTY }
  private final Consumer<String> onDelta;
  private int depth; private boolean inStr, esc, started; private int uLeft; private final StringBuilder u = new StringBuilder(); private char hiSurrogate;
  private final StringBuilder key = new StringBuilder(); private String lastKey; private boolean readingKey, expectValue, inReply, awaitCallsValue, awaitCallsFirst;
  private Calls calls = Calls.UNKNOWN; private final StringBuilder reply = new StringBuilder(); private int sent;

  public ReplyStreamExtractor(Consumer<String> onDelta) { this.onDelta = onDelta; }

  public void reset() { depth = 0; inStr = esc = started = false; uLeft = 0; u.setLength(0); hiSurrogate = 0; key.setLength(0); lastKey = null; readingKey = expectValue = inReply = awaitCallsValue = awaitCallsFirst = false; calls = Calls.UNKNOWN; reply.setLength(0); sent = 0; }
  /** 已经通过 onDelta 发出去的文字 */
  public String sent() { return reply.substring(0, sent); }
  public boolean finalStep() { return calls == Calls.EMPTY; }

  public void accept(String chunk) {
    for (int i = 0; i < chunk.length(); i++) step(chunk.charAt(i));
    flush();
  }

  private void flush() { if (calls == Calls.EMPTY && sent < reply.length()) { onDelta.accept(reply.substring(sent)); sent = reply.length(); } }

  private void step(char c) {
    if (!started) { if (c == '{') { started = true; depth = 1; expectKey(); } return; }
    if (inStr) { str(c); return; }
    if (awaitCallsFirst) { if (Character.isWhitespace(c)) return; calls = c == ']' ? Calls.EMPTY : Calls.NON_EMPTY; awaitCallsFirst = false; }
    if (awaitCallsValue) { if (Character.isWhitespace(c) || c == ':') return; awaitCallsValue = false; if (c == '[') awaitCallsFirst = true; else if (c == 'n') calls = Calls.EMPTY; /* null */ }
    switch (c) {
      case '"' -> { inStr = true; if (depth == 1 && readingKey) key.setLength(0); else if (depth == 1 && expectValue && "reply".equals(lastKey)) inReply = true; }
      case '{', '[' -> { depth++; if (depth == 2) expectValue = false; }
      case '}', ']' -> { depth--; if (depth == 1) { expectValue = false; } }
      case ':' -> { if (depth == 1) { expectValue = true; if ("tool_calls".equals(lastKey)) awaitCallsValue = true; } }
      case ',' -> { if (depth == 1) expectKey(); }
      default -> { }
    }
  }
  private void expectKey() { readingKey = true; expectValue = false; }

  private void str(char c) {
    boolean collectKey = depth == 1 && readingKey, collectReply = inReply;
    if (uLeft > 0) {
      u.append(c); if (--uLeft == 0) { char ch = (char) Integer.parseInt(u.toString(), 16); u.setLength(0); emitChar(ch, collectKey, collectReply); }
      return;
    }
    if (esc) {
      esc = false;
      switch (c) { case 'n' -> emitChar('\n', collectKey, collectReply); case 't' -> emitChar('\t', collectKey, collectReply); case 'r' -> emitChar('\r', collectKey, collectReply);
        case 'b' -> emitChar('\b', collectKey, collectReply); case 'f' -> emitChar('\f', collectKey, collectReply); case 'u' -> uLeft = 4; default -> emitChar(c, collectKey, collectReply); }
      return;
    }
    if (c == '\\') { esc = true; return; }
    if (c == '"') {
      inStr = false;
      if (collectKey) { lastKey = key.toString(); readingKey = false; }
      else if (collectReply) inReply = false;
      if (depth == 1 && !collectKey) expectValue = false;
      return;
    }
    emitChar(c, collectKey, collectReply);
  }
  private void emitChar(char ch, boolean collectKey, boolean collectReply) {
    if (collectKey) { key.append(ch); return; }
    if (!collectReply) return;
    if (Character.isHighSurrogate(ch)) { hiSurrogate = ch; return; }
    if (hiSurrogate != 0) { reply.append(hiSurrogate); hiSurrogate = 0; }
    reply.append(ch);
  }
}
