package com.x7x24.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.agents.LlmAgents;
import com.x7x24.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EditApplyTest {
  JsonNode edits(String... pairs) {
    var a = Json.M.createArrayNode();
    for (int i = 0; i < pairs.length; i += 2) a.addObject().put("search", pairs[i]).put("replace", pairs[i + 1]);
    return a;
  }
  @Test void appliesUniqueSearchReplace() {
    var r = LlmAgents.applyEdits("hello world\nfoo", edits("world", "地球", "foo", "bar"), "a.ts");
    assertNull(r.err()); assertEquals("hello 地球\nbar", r.content());
  }
  @Test void rejectsMissingOrAmbiguousSearch() {
    assertTrue(LlmAgents.applyEdits("aa aa", edits("aa", "b"), "a.ts").err().contains("出现多次"));
    assertTrue(LlmAgents.applyEdits("abc", edits("zzz", "b"), "a.ts").err().contains("找不到"));
    assertTrue(LlmAgents.applyEdits(null, edits("a", "b"), "x.js").err().contains("不在"));
  }
}
