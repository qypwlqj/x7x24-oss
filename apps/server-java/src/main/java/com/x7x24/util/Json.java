package com.x7x24.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.Map;

public final class Json {
  public static final ObjectMapper M = JsonMapper.builder().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build();
  private static final ObjectMapper CANON = JsonMapper.builder()
      .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true).configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true).build();
  private Json() {}

  public static String str(Object o) { try { return M.writeValueAsString(o); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
  public static String canonical(Object o) { try { return CANON.writeValueAsString(o); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
  public static JsonNode tree(String s) { try { return s == null || s.isBlank() ? M.nullNode() : M.readTree(s); } catch (JsonProcessingException e) { throw new IllegalArgumentException(e); } }
  public static <T> T parse(String s, Class<T> c) { try { return M.readValue(s, c); } catch (JsonProcessingException e) { throw new IllegalArgumentException(e); } }
  public static <T> T parse(String s, TypeReference<T> t) { try { return M.readValue(s, t); } catch (JsonProcessingException e) { throw new IllegalArgumentException(e); } }
  public static <T> T convert(Object o, Class<T> c) { return M.convertValue(o, c); }
  public static Map<String, Object> map(Object o) { return M.convertValue(o, new TypeReference<>() {}); }
  /** 容错解析：先整体解析，失败则抓第一个 {...} 块（LLM 常在 JSON 外加说明） */
  public static JsonNode lenient(String text) {
    if (text == null) return null;
    try { return M.readTree(text); } catch (Exception ignore) { /* fallthrough */ }
    int a = text.indexOf('{'), b = text.lastIndexOf('}');
    if (a >= 0 && b > a) { try { return M.readTree(text.substring(a, b + 1)); } catch (Exception ignore) { /* ignore */ } }
    return null;
  }
}
