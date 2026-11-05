package com.x7x24.execution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static com.x7x24.execution.CodingExecutor.*;
import static org.junit.jupiter.api.Assertions.*;
class LocalControlledWorkspaceTest {
 @TempDir Path temp;
 JobSpec spec(List<Step> steps){return new JobSpec(1,"ws","project","task","run","owner",1,"semantic-key","base","inputs",Map.of("src/value.txt","old"),steps,List.of("src/"),Set.of("AC-1"),new Limits(10,10000,20,System.currentTimeMillis()+10000),"DENY");}
 Result execute(JobSpec s){try(var runner=new LocalControlledWorkspace(temp.resolve(UUID.randomUUID().toString()),s,()->{},()->false)){return runner.execute(event->{});}}
 @Test void boundedReadSearchFailEditAndRetestPreserveCurrentTreeEvidence(){
  var result=execute(spec(List.of(new Step(Action.READ,"src/value.txt",null,null,null),new Step(Action.SEARCH,"src/value.txt","old",null,null),new Step(Action.ASSERT_CONTAINS,"src/value.txt","new",null,"AC-1"),new Step(Action.EDIT,"src/value.txt","old","new",null),new Step(Action.ASSERT_CONTAINS,"src/value.txt","new",null,"AC-1"))));
  assertEquals(State.SUCCEEDED,result.state());assertEquals(List.of("FAIL","PASS"),result.checks().stream().map(Check::result).toList());assertEquals(result.treeHash(),result.checks().getLast().subjectTreeHash());assertEquals("new",result.files().get("src/value.txt"));
 }
 @Test void missingChecksStaleChecksAndConflictingChecksCannotPass(){
  assertEquals(State.FAILED,execute(spec(List.of())).state());
  assertEquals(State.FAILED,execute(spec(List.of(new Step(Action.ASSERT_CONTAINS,"src/value.txt","old",null,"AC-1"),new Step(Action.EDIT,"src/value.txt","old","new",null)))).state());
  assertEquals(State.FAILED,execute(spec(List.of(new Step(Action.ASSERT_CONTAINS,"src/value.txt","old",null,"AC-1"),new Step(Action.ASSERT_CONTAINS,"src/value.txt","missing",null,"AC-1")))).state());
 }
 @Test void existingDirectoriesCannotBeClaimedOrCleaned() throws Exception {
  Path owned=temp.resolve("existing");Files.createDirectories(owned);Files.writeString(owned.resolve("business.txt"),"preserve");assertThrows(IllegalArgumentException.class,()->new LocalControlledWorkspace(owned,spec(List.of()),()->{},()->false));assertEquals("preserve",Files.readString(owned.resolve("business.txt")));
 }
 @Test void escapesHiddenFilesBudgetAndCancelledWorkAreRejected(){
  for(String path:List.of("../outside","/tmp/outside",".github/workflows/ci.yml",".env","scripts/x"))assertEquals(State.FAILED,execute(spec(List.of(new Step(Action.PUT,path,null,"x",null)))).state());
  assertEquals(State.FAILED,execute(spec(List.of(new Step(Action.PUT,"other/file",null,"x",null)))).state());
  try(var runner=new LocalControlledWorkspace(temp.resolve("cancel"),spec(List.of()),()->{},()->true)){assertEquals(State.CANCELLED,runner.execute(event->{}).state());}
 }
}
