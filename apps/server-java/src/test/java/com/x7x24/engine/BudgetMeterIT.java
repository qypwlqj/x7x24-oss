package com.x7x24.engine;
import com.x7x24.it.Harness;
import com.x7x24.llm.LlmProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BudgetMeterIT extends Harness {
 @Test void repeatedCallsInOneAttemptAreBothAccounted(){
  String id=ingestIssue("meter fixture","synthetic usage fixture");var t=repo.get(id);var meter=engine.meter(t,"fixture");
  meter.accept("dev",new LlmProvider.Response("failed output","fixture-model","v1",10,0.01));
  meter.accept("dev",new LlmProvider.Response("success output","fixture-model","v1",20,0.02));
  assertEquals(2,db.count("SELECT COUNT(*) FROM budget_ledger WHERE task_id=?",id));assertEquals(30,db.count("SELECT SUM(tokens) FROM budget_ledger WHERE task_id=?",id));
 }
}
