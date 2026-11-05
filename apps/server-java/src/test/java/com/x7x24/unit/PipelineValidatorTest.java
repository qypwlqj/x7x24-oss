package com.x7x24.unit;

import com.x7x24.pipeline.Catalog;
import com.x7x24.pipeline.Pipeline.*;
import com.x7x24.pipeline.Validator;
import com.x7x24.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PipelineValidatorTest {
  Template copy(String key) { return Json.convert(Catalog.TEMPLATES.get(key), Template.class); }

  @Test void builtinTemplatesAreValid() {
    for (var t : Catalog.TEMPLATES.values()) assertEquals(List.of(), Validator.template(t, Catalog.ROLES), t.key());
  }
  @Test void defaultTemplateEqualsLegacyBehavior() {
    var d = Catalog.TEMPLATES.get("default@1");
    assertEquals(Catalog.defaultHeal(), d.heal, "自愈计划与改造前写死的值一致");
    assertNull(d.maxHeals);
    for (var s : d.stages) { assertNull(s.enabled, s.type); assertNull(s.gate, s.type); assertNull(s.maxLoops, s.type); assertNull(s.checks, s.type); }
  }
  @Test void lockedStagesCannotBeDisabledOrRemoved() {
    var t = copy("default@1"); t.id = "x1"; t.stage("QA").enabled = false;
    assertTrue(Validator.template(t, Catalog.ROLES).stream().anyMatch(e -> e.contains("QA")));
    var t2 = copy("default@1"); t2.id = "x2"; t2.stages.removeIf(s -> s.type.equals("ACCEPTANCE"));
    assertTrue(Validator.template(t2, Catalog.ROLES).stream().anyMatch(e -> e.contains("ACCEPTANCE")));
    var t3 = copy("default@1"); t3.id = "x3"; t3.stage("SPEC").enabled = false; t3.stage("DESIGN").enabled = false;
    assertEquals(List.of(), Validator.template(t3, Catalog.ROLES), "SPEC / DESIGN 可关闭");
  }
  @Test void rejectsCustomStageTypesWrongOrderAndBadSlots() {
    var t = copy("default@1"); t.id = "x4"; t.stages.add(new StageCfg("SECURITY_SCAN"));
    assertTrue(Validator.template(t, Catalog.ROLES).stream().anyMatch(e -> e.contains("SECURITY_SCAN")));
    var t2 = copy("default@1"); t2.id = "x5"; var dev = t2.stages.remove(5); t2.stages.add(1, dev);
    assertTrue(Validator.template(t2, Catalog.ROLES).stream().anyMatch(e -> e.contains("顺序")));
    var t3 = copy("default@1"); t3.id = "x6"; t3.stage("DEV").maker = "review.accept";
    assertTrue(Validator.template(t3, Catalog.ROLES).stream().anyMatch(e -> e.contains("maker")));
  }
  @Test void enforcesGlobalCeilingsAndRequiredEvidence() {
    var t = copy("default@1"); t.id = "x7"; t.stage("DEV").maxChangeLines = 3001;
    assertTrue(Validator.template(t, Catalog.ROLES).stream().anyMatch(e -> e.contains("3000")));
    var t2 = copy("default@1"); t2.id = "x8"; t2.stage("QA").checks = List.of("markdown-lint");
    assertTrue(Validator.template(t2, Catalog.ROLES).stream().anyMatch(e -> e.contains("ci")));
    var t3 = copy("default@1"); t3.id = "x9"; t3.stage("ACCEPTANCE").judgeSeats = 1;
    assertTrue(Validator.template(t3, Catalog.ROLES).stream().anyMatch(e -> e.contains("席")));
    var t4 = copy("default@1"); t4.id = "y1"; t4.heal = java.util.Map.of("default", List.of("delete_repo"));
    assertTrue(Validator.template(t4, Catalog.ROLES).stream().anyMatch(e -> e.contains("delete_repo")));
  }
  @Test void derivedRoleCannotChangeContract() {
    var r = new Role(); r.id = "lms.dev.code"; r.extendsRole = "dev.code"; r.skills = List.of("i18n-json@1");
    assertEquals(List.of(), Validator.role(r, Catalog.SKILLS));
    r.schema = "Prd"; assertFalse(Validator.role(r, Catalog.SKILLS).isEmpty());
    var r2 = new Role(); r2.id = "dev.code"; r2.extendsRole = "dev.code"; assertFalse(Validator.role(r2, Catalog.SKILLS).isEmpty(), "不能与内置角色重名");
    var r3 = new Role(); r3.id = "a.b"; r3.extendsRole = "dev.code"; r3.skills = List.of("nope@1"); assertFalse(Validator.role(r3, Catalog.SKILLS).isEmpty());
  }
}
