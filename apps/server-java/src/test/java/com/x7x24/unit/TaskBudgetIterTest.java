package com.x7x24.unit;

import com.x7x24.engine.Task;
import com.x7x24.gov.BudgetService;
import com.x7x24.util.Json;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 多段任务：开发轮次上限随段数放宽，避免金额 / Token 充足时因轮次误触「任务预算用完」 */
class TaskBudgetIterTest {
  @Test void iterLimitScalesWithSegments() throws Exception {
    var t = new Task(); t.budgetIterations = 50;
    assertEquals(50, BudgetService.iterLimit(t));
    t.plan = Json.M.readTree("{\"subtasks\":[{},{},{},{},{},{},{},{}]}");
    assertEquals(120, BudgetService.iterLimit(t));
  }
}
