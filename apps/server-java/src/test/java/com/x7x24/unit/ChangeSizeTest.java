package com.x7x24.unit;

import com.x7x24.gov.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChangeSizeTest {
  final Governance.Release rel = Governance.defaults().release;

  @Test void rawLinesByKind() {
    assertEquals(3, ChangeSize.rawLines(null, "a\nb\nc\n", false), "新文件 = 行数");
    assertEquals(1, ChangeSize.rawLines("x", null, true), "删除 = 1");
    String orig = "l1\nl2\nl3\nl4\nl5\n".repeat(200);
    String edited = orig.replaceFirst("l3\n", "L3\n");
    assertEquals(2, ChangeSize.rawLines(orig, edited, false), "整文件改写只计真实 diff 行，而不是 1000 行全文");
    assertEquals(0, ChangeSize.diffLines("a\nb", "a\nb"));
    assertEquals(1, ChangeSize.diffLines("a\nb", "a\nb\nc"));
  }
  @Test void globsAndDefaultWeights() {
    assertTrue(ChangeSize.glob("**/locales/**/*.json", "frontend/src/locales/ja/common.json"));
    assertTrue(ChangeSize.glob("**/locales/**/*.json", "locales/ja.json"));
    assertTrue(ChangeSize.glob("*.md", "docs/a/b.md")); assertFalse(ChangeSize.glob("docs/**", "src/docs.ts"));
    assertEquals(0.1, ChangeSize.weightOf(rel, "frontend/src/locales/ja/common.json"));
    assertEquals(0.25, ChangeSize.weightOf(rel, "docs/i18n.md"));
    assertEquals(0.0, ChangeSize.weightOf(rel, "frontend/package-lock.json"));
    assertEquals(1.0, ChangeSize.weightOf(rel, "frontend/src/App.tsx"));
  }
  @Test void i18nPrIsWithinLimitsButCodeIsNot() {
    var ok = ChangeSize.measure(rel, List.of(new ChangeSize.Item("frontend/src/locales/ja/common.json", 1800), new ChangeSize.Item("frontend/src/i18n.ts", 40), new ChangeSize.Item("docs/i18n.md", 60), new ChangeSize.Item("package-lock.json", 3000)));
    assertTrue(ok.within(), ok.summary()); assertEquals(180 + 40 + 15, ok.weightedLines()); assertEquals(2, ok.files(), "文件数按权重折算：0.1 + 1 + 0.25 → 2；权重 0 的 lock 不计"); assertEquals(3, ok.rawFiles());
    var bad = ChangeSize.measure(rel, List.of(new ChangeSize.Item("frontend/src/App.tsx", 600)));
    assertFalse(bad.within());
  }
  @Test void exemptCapStopsAbuse() {
    var r = new Governance.Release(); r.sizeWeights.put("**/*.json", 0.1); r.maxExemptLines = 1000; r.maxChangeLines = 500;
    var res = ChangeSize.measure(r, List.of(new ChangeSize.Item("a.json", 3000)));
    assertEquals(1000, res.exemptRawLines()); assertEquals(100 + 2000, res.weightedLines()); assertFalse(res.within());
  }
  @Test void configValidation() {
    var bad = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    bad.putObject("release").putObject("sizeWeights").put("**/*.json", 2.0);
    assertThrows(IllegalArgumentException.class, () -> ConfigStore.merge(Governance.defaults(), bad));
    var neg = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(); neg.putObject("release").put("maxExemptLines", -1);
    assertThrows(IllegalArgumentException.class, () -> ConfigStore.merge(Governance.defaults(), neg));
    var good = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(); good.putObject("release").putObject("sizeWeights").put("**/*.po", 0.1);
    assertEquals(0.1, ConfigStore.merge(Governance.defaults(), good).release.sizeWeights.get("**/*.po"));
  }

  @Test void globsMatchAtAnyDepthUnlessAnchored() {
    assertTrue(ChangeSize.glob("locales/**/*.json", "frontend/src/locales/ja/common.json"), "不以 / 开头的模式在任意层级匹配");
    assertTrue(ChangeSize.glob("**/locales/**/*.json", "frontend/src/locales/ja/common.json"));
    assertTrue(ChangeSize.glob("**/locales/**/*.json", "./frontend/src/locales/ja.json"));
    assertTrue(ChangeSize.glob("frontend/src/locales/", "frontend/src/locales/ja/a.json"), "目录模式");
    assertFalse(ChangeSize.glob("/locales/**/*.json", "frontend/src/locales/ja/a.json"), "/ 开头锚定仓库根");
    assertTrue(ChangeSize.glob("/locales/**/*.json", "locales/ja/a.json"));
  }
  @Test void thirtyLocaleFilesCountFractionally() {
    var r = new Governance.Release(); r.sizeWeights.put("**/locales/**/*.json", 0.1); r.maxFiles = 20; r.maxChangeLines = 1500;
    List<ChangeSize.Item> items = new java.util.ArrayList<>(); for (int i = 0; i < 28; i++) items.add(new ChangeSize.Item("frontend/src/locales/ja/ns" + i + ".json", 10)); items.add(new ChangeSize.Item("frontend/src/i18n.ts", 20)); items.add(new ChangeSize.Item("frontend/src/App.tsx", 9));
    var res = ChangeSize.measure(r, items);
    assertEquals(30, res.rawFiles()); assertEquals(5, res.files(), "28×0.1 + 2 → 5"); assertEquals(57, res.weightedLines()); assertTrue(res.within(), res.summary());
    r.weightFileCount = false; assertFalse(ChangeSize.measure(r, items).within(), "关闭折算时 30 个文件超限");
    var over = ChangeSize.measure(r, items); assertTrue(over.overage().contains("超出 10 个文件"), over.overage());
  }

  @Test void tightBudgetRepacksIntoMoreSegmentsEachWithinBudget() {
    var r = new Governance.Release(); r.maxChangeLines = 100; r.maxFiles = 20; r.maxSplitParts = 4;
    Map<String, Integer> est = new java.util.LinkedHashMap<>(); for (int i = 0; i < 13; i++) est.put("frontend/src/locales/ja/n" + i + ".json", 30); est.put("frontend/src/i18n/index.ts", 20);
    var subs = com.x7x24.agents.LlmAgents.repack(r, 0.8, List.of(), est);
    assertTrue(subs.size() > 4, "需要的段数超过 maxSplitParts=4 时自动多拆：" + subs.size());
    assertTrue(subs.size() <= ChangeSize.splitCap(r));
    for (var m : subs) { @SuppressWarnings("unchecked") var fs = (List<String>) m.get("files"); var res = ChangeSize.measure(r, fs.stream().map(f -> new ChangeSize.Item(f, est.get(f))).toList()); assertTrue(res.weightedLines() <= 80, res.summary()); }
    var bins = ChangeSize.pack(r, List.of(new ChangeSize.Item("a.ts", 70), new ChangeSize.Item("b.ts", 60), new ChangeSize.Item("c.ts", 20)), 80, 16);
    assertEquals(2, bins.size());
  }
}
