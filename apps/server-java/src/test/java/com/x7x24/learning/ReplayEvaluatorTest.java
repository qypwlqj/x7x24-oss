package com.x7x24.learning;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayEvaluatorTest {
  @Test void assertionsAreScopedAndARealRunnerIsRequiredForProjectTests(){
    var c=new Learning.Case("id","root","family",Learning.Split.TEST,"base","goal","failure",Map.of("src/value.json","{}"),List.of("src/value.json"),List.of(new Learning.Check("src/value.json","JSON_EQUALS","{\"ok\":true}")));
    var evaluator=new ReplayEvaluator();
    assertFalse(evaluator.evaluate(c,c.files(),"FILE_ASSERTIONS").passed());
    var result=evaluator.evaluate(c,Map.of("src/value.json","{\"ok\":true}"),"FILE_ASSERTIONS");assertTrue(result.passed());assertEquals("FILE_ASSERTIONS",result.scope());
    assertThrows(IllegalArgumentException.class,()->evaluator.evaluate(c,c.files(),"PROJECT_TESTS"));
    assertThrows(IllegalArgumentException.class,()->ReplayEvaluator.path("../escape"));
    for(String p:List.of("test/unit.js","src/x.test.ts","src/x_test.go","scripts/test.sh","package-lock.json"))assertTrue(ReplayEvaluator.protectedPath(p),p);
  }
}
