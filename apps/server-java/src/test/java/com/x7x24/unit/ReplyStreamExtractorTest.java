package com.x7x24.unit;

import com.x7x24.chat.ReplyStreamExtractor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 流式 JSON 里取 reply：任意切分、转义、中文 / emoji、tool_calls 顺序 */
class ReplyStreamExtractorTest {
  static List<String> feed(String json, int size) {
    List<String> out = new ArrayList<>(); var ex = new ReplyStreamExtractor(out::add);
    for (int i = 0; i < json.length(); i += size) ex.accept(json.substring(i, Math.min(json.length(), i + size)));
    return out;
  }
  static final String FINAL = "{\"tool_calls\": [], \"actions\": [], \"guide\": null, \"reply\": \"好的\\n1. **导出** \\\"CSV\\\" \\u4e2d😀 \\\\ 完\", \"draft\": {\"title\": \"reply 不是这个\"}}";

  @Test void streamsReplyAtAnyChunkSize() {
    for (int size : new int[]{1, 2, 3, 5, 7, 13, 1000}) {
      var parts = feed(FINAL, size);
      assertEquals("好的\n1. **导出** \"CSV\" 中😀 \\ 完", String.join("", parts), "size=" + size);
      if (size <= 3) assertTrue(parts.size() > 5, "逐段输出：size=" + size);
    }
  }
  @Test void toolStepIsNotStreamed() {
    assertTrue(feed("{\"tool_calls\": [{\"name\": \"repo_tree\", \"args\": {\"reply\": \"x\"}}], \"reply\": \"我查一下\"}", 4).isEmpty());
  }
  @Test void replyBeforeToolCallsIsHeldUntilDecided() {
    List<String> out = new ArrayList<>(); var ex = new ReplyStreamExtractor(out::add);
    ex.accept("{\"reply\": \"先说话\", \"draft\": {}, ");
    assertTrue(out.isEmpty(), "还不知道是不是最终步骤"); assertEquals("", ex.sent());
    ex.accept("\"tool_calls\": []}");
    assertEquals("先说话", String.join("", out)); assertTrue(ex.finalStep());
  }
  @Test void noToolCallsKeyMeansNothingStreamedAndResetClears() {
    List<String> out = new ArrayList<>(); var ex = new ReplyStreamExtractor(out::add);
    ex.accept("```json\n{\"reply\": \"hi\"}"); assertTrue(out.isEmpty());
    ex.reset(); ex.accept("{\"tool_calls\":null,\"reply\":\"ok\"}"); assertEquals("ok", ex.sent());
  }
  @Test void nestedReplyKeysIgnored() {
    assertEquals("真", String.join("", feed("{\"tool_calls\":[],\"actions\":[{\"kind\":\"x\",\"params\":{\"reply\":\"假\"}}],\"reply\":\"真\"}", 2)));
  }
}
