package com.x7x24.pipeline;

import com.x7x24.pipeline.Pipeline.*;
import java.util.*;

/** Resolves one actual consumer from a pinned configuration; never reads the live skill catalogue. */
public final class RolePlans {
  private RolePlans() {}
  public record EffectiveRolePlan(String snapshotId, String nodeId, String slot, String roleId,
                                  String modelProfile, String instructions, String digest) {}
  public static EffectiveRolePlan resolve(Effective e, String node, String slot, String fallback) {
    StageCfg s = e == null ? null : e.stage(node);
    String id = s == null ? null : switch (slot) { case "maker" -> s.maker; case "fixer" -> s.fixer; case "checker" -> s.checker; default -> throw new IllegalArgumentException("INVALID_ROLE_CONSUMER"); };
    if (id == null) id = fallback;
    Role r = e == null ? null : e.roles.get(id);
    String text = e == null ? "" : e.skillText.getOrDefault(id, "") + "\n" + e.skillText.getOrDefault("stage:" + node, "");
    String profile = e == null ? null : e.models.getOrDefault(id, r == null || r.builtin ? null : r.profile);
    String digest = com.x7x24.util.U.sha256(com.x7x24.util.Json.str(Arrays.asList(e == null ? null : e.id, node, slot, id, profile, text)));
    return new EffectiveRolePlan(e == null ? null : e.id, node, slot, id, profile, text, digest);
  }
}
