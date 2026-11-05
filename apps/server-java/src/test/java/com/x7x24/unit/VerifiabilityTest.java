package com.x7x24.unit;

import com.x7x24.gov.Verifiability;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VerifiabilityTest {
  @Test void nonNumericButObservableCriteriaAreVerifiable() {
    for (String c : List.of("在语言下拉框选择「日本語」后，导航栏「课程」显示为「コース」", "刷新页面后仍保持日语界面", "未登录访问 /admin 时返回 403",
        "传入非数字时抛出 TypeError", "`npm test` 全部通过", "ja 语言包缺失键为 0", "settings are persisted after reload"))
      assertNull(Verifiability.why(c), c);
  }
  @Test void vagueCriteriaRejectedWithReason() {
    assertNotNull(Verifiability.why("用户体验良好"));
    assertNotNull(Verifiability.why("代码整洁优雅"));
    assertEquals(1, Verifiability.check(List.of("返回 403", "界面更美观")).size());
    assertEquals(2, Verifiability.check(List.of("返回 403", "界面更美观")).get(0).index());
  }
  @Test void parsesNumberedList() {
    assertEquals(List.of("a 返回 1", "b 显示 x"), Verifiability.parseList("1. a 返回 1\n- b 显示 x\n\n"));
  }
}
