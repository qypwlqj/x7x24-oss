package com.x7x24.unit;

import com.x7x24.pipeline.Checks;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ChecksTest {
  final Map<String, String> fs = new HashMap<>();
  List<Checks.Result> run(String check, String... files) {
    return Checks.run(List.of("ci", check), List.of(files), fs::get, dir -> fs.keySet().stream().filter(k -> k.startsWith(dir + "/")).map(k -> k.substring(dir.length() + 1).split("/")[0]).distinct().toList());
  }
  @Test void i18nDirLayoutReportsMissingKeys() {
    fs.put("src/locales/en/common.json", "{\"a\":\"A\",\"nav\":{\"home\":\"Home\",\"about\":\"About\"}}");
    fs.put("src/locales/ja/common.json", "{\"a\":\"エー\",\"nav\":{\"home\":\"ホーム\"}}");
    var r = run("i18n-keys-complete", "src/locales/ja/common.json").get(0);
    assertFalse(r.ok()); assertTrue(r.problems().get(0).contains("nav.about"));
    fs.put("src/locales/ja/common.json", "{\"a\":\"エー\",\"nav\":{\"home\":\"ホーム\",\"about\":\"概要\"}}");
    assertTrue(run("i18n-keys-complete", "src/locales/ja/common.json").get(0).ok());
  }
  @Test void i18nSingleFileLayoutAndInvalidJson() {
    fs.put("i18n/en.json", "{\"x\":1,\"y\":2}"); fs.put("i18n/ja.json", "{\"x\":1,");
    var r = run("i18n-keys-complete", "i18n/ja.json").get(0); assertFalse(r.ok()); assertTrue(r.problems().get(0).contains("JSON"));
    fs.put("i18n/ja.json", "{\"x\":1}"); assertTrue(run("i18n-keys-complete", "i18n/ja.json").get(0).problems().get(0).contains("y"));
    assertEquals(Checks.State.SKIPPED, run("i18n-keys-complete", "src/app.ts").get(0).state(), "非翻译文件有明确不适用原因");
  }
  @Test void unknownAndMissingEvidenceNeverPass() {
    assertFalse(run("not-registered", "a.md").get(0).ok());
    assertFalse(run("sql-safety-lint", "db/change.sql").get(0).ok());
    var r = Checks.run(List.of("markdown-lint"), List.of("a.md"), path -> { throw new IllegalStateException("offline"); }, dir -> List.of()).get(0);
    assertFalse(r.ok());
  }
  @Test void missingOrMalformedLocaleBaselineNeverPasses() {
    fs.put("i18n/ja.json", "{}"); fs.put("i18n/en.json", "broken");
    assertFalse(run("i18n-keys-complete", "i18n/ja.json").get(0).ok());
  }
  @Test void markdownLint() {
    fs.put("docs/a.md", "# T\n\n### skip\n\n[](http://x) ok\n```\n# not heading\n#### x\n```\n");
    var r = run("markdown-lint", "docs/a.md").get(0); assertFalse(r.ok()); assertEquals(2, r.problems().size(), r.problems().toString());
    fs.put("docs/b.md", "# T\n## S\n[link](http://x)\n"); assertTrue(run("markdown-lint", "docs/b.md").get(0).ok());
  }
  @Test void sqlSafetyLint() {
    fs.put("db/migrations/001.sql", "-- drop table users;\nALTER TABLE orders ADD COLUMN note VARCHAR(64);\nDROP TABLE legacy_orders;\nDELETE FROM audit_log;\n");
    var r = run("sql-safety-lint", "db/migrations/001.sql").get(0);
    assertFalse(r.ok()); assertEquals(2, r.problems().size(), r.problems().toString());
    fs.put("db/migrations/002.sql", "ALTER TABLE orders ADD COLUMN status VARCHAR(32) DEFAULT 'open';\nDELETE FROM temp_items WHERE expired = 1;\n");
    assertTrue(run("sql-safety-lint", "db/migrations/002.sql").get(0).ok());
  }
}
