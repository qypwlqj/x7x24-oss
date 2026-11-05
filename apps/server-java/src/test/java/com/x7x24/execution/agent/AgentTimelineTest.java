package com.x7x24.execution.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AgentTimelineTest {
  final AtomicLong clock = new AtomicLong(1_000_000);
  final AgentTimeline tl = new AgentTimeline(null, null, clock::get);
  AgentRuntime.LogEvent ev(long seq, String kind, String text) { return new AgentRuntime.LogEvent(seq, "2026-10-24T22:00:00+0900", kind, text); }

  @Test @SuppressWarnings("unchecked") void mapsToolBatchesAndRedactsAgain() {
    // fake test key — synthetic, for redaction coverage
    String leak = "ghp_" + "abcdefghijklmnopqrstuvwxyz12";
    var p = tl.map("j1", ev(3, "tools", "{\"items\":[{\"name\":\"Bash\",\"args\":\"curl -H 'Authorization: Bearer " + leak + "'\",\"ms\":1200,\"ok\":false,\"out\":\"FAIL x\"},{\"name\":\"Read\",\"args\":\"a.ts\",\"ms\":5,\"ok\":true,\"count\":4}],\"dropped\":2}"));
    assertEquals("AGENT_TOOLS", p.get("_type")); assertEquals(1, p.get("failed")); assertEquals(2, p.get("dropped"));
    var items = (List<Map<String, Object>>) p.get("items"); assertFalse(String.valueOf(items).contains("ghp_abc"), "后端再脱敏一次");
    assertEquals(4, items.get(1).get("count")); assertEquals(false, items.get(0).get("ok"));
  }
  @Test void browserItemsBecomeBrowserCheck() {
    assertEquals("AGENT_BROWSER_CHECK", tl.map("j", ev(1, "tools", "{\"items\":[{\"name\":\"mcp__playwright__browser_navigate\",\"args\":\"http://localhost:5173\",\"ok\":true,\"browser\":true}]}")).get("_type"));
  }
  @Test void notesAreRateLimitedAndOtherKindsStayInJobLog() {
    assertNotNull(tl.map("j", ev(1, "text", "先看看测试")));
    assertNull(tl.map("j", ev(2, "text", "继续")), "20 秒内只写一条说明");
    clock.addAndGet(21_000); var p = tl.map("j", ev(3, "text", "改好了")); assertEquals(1, p.get("skipped"));
    assertNull(tl.map("j", ev(4, "system", "clone"))); assertNull(tl.map("j", ev(5, "stderr", "x"))); assertNull(tl.map("j", ev(6, "tools", "not json")));
    assertEquals("AGENT_RESULT", tl.map("j", ev(7, "result", "success，5 轮")).get("_type"));
    assertEquals("AGENT_TOOLS", tl.map("j", ev(8, "tool", "Bash: npm test")).get("_type"), "兼容旧 runner");
  }
  @Test void pruneRemovesOldScreenshotDirsOnly() throws Exception {
    var root = Files.createTempDirectory("ua"); var old = Files.createDirectories(root.resolve("ui-acceptance/old")); Files.write(old.resolve("a.png"), new byte[]{1});
    var fresh = Files.createDirectories(root.resolve("ui-acceptance/new"));
    Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(0));
    assertEquals(1, UiAcceptanceService.prune(root, 30, System.currentTimeMillis()));
    assertFalse(Files.exists(old)); assertTrue(Files.exists(fresh));
    assertEquals(0, UiAcceptanceService.prune(root.resolve("nope"), 30, System.currentTimeMillis()));
  }
}
