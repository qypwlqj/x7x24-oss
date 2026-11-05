package com.x7x24.workflow;
import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.util.*;
import java.util.*;
/** Versioned canonical JSON encoding. Arrays retain order, object keys are sorted. */
public final class Canonical {
  private Canonical() {}
  private static JsonNode sorted(JsonNode n) {
    if(n.isObject()) { var o=Json.M.createObjectNode(); var names=new TreeSet<String>(); n.fieldNames().forEachRemaining(names::add); names.forEach(k->o.set(k,sorted(n.get(k)))); return o; }
    if(n.isArray()) { var a=Json.M.createArrayNode(); n.forEach(v->a.add(sorted(v))); return a; }
    return n;
  }
  public static String json(Object value) { return sorted(Json.M.valueToTree(value)).toString(); }
  public static String digest(Object value) { return U.sha256("x7-canonical-v1\n"+json(value)); }
}
