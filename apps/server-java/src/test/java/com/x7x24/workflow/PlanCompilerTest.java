package com.x7x24.workflow;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.x7x24.workflow.Workflow.*;
import static org.junit.jupiter.api.Assertions.*;
class PlanCompilerTest {
  ArtifactVersion artifact(String id, ArtifactType type) { return new ArtifactVersion(id,"ws","p","task",type,1,"digest-"+id,"{}","USER",null,null,"base",List.of()); }
  Plan plan(List<Node> nodes, Map<String,Boolean> facts) { return new Plan(3,"r1","ws","p","task",1,null,"base","policy",10,nodes,List.of(),facts); }
  Node node(String id, String def, Binding... b) { return new Node(id,id,def,Map.of(),List.of(b),Map.of()); }
  Binding pin(String port,String id) { return new Binding(port,port,0,id,null,null); }
  Binding upstream(String port,String node,String output) { return new Binding(port,port,0,null,node,output); }
  final Map<String,ArtifactVersion> store = new HashMap<>(Map.of("ac",artifact("ac",ArtifactType.AcceptanceCriteria), "repo",artifact("repo",ArtifactType.RepoSnapshot),"prd",artifact("prd",ArtifactType.RequirementSpec),"design",artifact("design",ArtifactType.DesignSpec),"api",artifact("api",ArtifactType.APIContract)));
  Compiled compile(Plan p) {
    store.put("scope",new ArtifactVersion("scope","ws","p","task",ArtifactType.RepositoryProfile,1,"fixture-scope",com.x7x24.util.Json.str(Map.of("features",p.facts())),"FIXTURE",null,null,"base",List.of()));
    var nodes=p.nodes().stream().map(n->{if(!n.definitionRef().equals("code.implement@1"))return n;var bs=new ArrayList<>(n.bindings());bs.add(pin("scopeEvidence","scope"));return new Node(n.id(),n.logicalKey(),n.definitionRef(),n.config(),bs,n.roles());}).toList();
    p=new Plan(p.schema(),p.revisionId(),p.workspaceId(),p.projectId(),p.taskId(),p.revisionNo(),p.parentRevisionId(),p.baseSha(),p.policyDigest(),p.budgetUsd(),nodes,p.controls(),p.facts());return new PlanCompiler(Definitions.standard(), store::get).compile(p); }
  @Test void missingRequiredAndConditionalInputsBlockWithPortDiagnostic() {
    var c=compile(plan(List.of(node("dev","code.implement@1",pin("requirements","prd"),pin("repository","repo"))),Map.of("requiresUiChange",true,"requiresApiChange",true)));
    assertFalse(c.valid()); assertTrue(c.diagnostics().stream().anyMatch(d->d.node().equals("dev")&&d.port().equals("acceptance")));
    assertTrue(c.diagnostics().stream().anyMatch(d->d.port().equals("design")));
  }
  @Test void frontendBackendAndFullstackUseExplicitDifferentBindings() {
    for(var facts: List.of(Map.of("requiresUiChange",true,"requiresApiChange",false),Map.of("requiresUiChange",false,"requiresApiChange",true),Map.of("requiresUiChange",true,"requiresApiChange",true))) {
      var b=new ArrayList<>(List.of(pin("requirements","prd"),pin("acceptance","ac"),pin("repository","repo")));
      if(facts.get("requiresUiChange"))b.add(pin("design","design")); if(facts.get("requiresApiChange"))b.add(pin("apiContract","api"));
      assertTrue(compile(plan(List.of(node("dev","code.implement@1",b.toArray(Binding[]::new))),facts)).valid());
    }
  }
  @Test void unknownConditionsWrongTypeAndCrossTenantNeverCompile() {
    var n=node("dev","code.implement@1",pin("requirements","prd"),pin("acceptance","ac"),pin("repository","repo"));
    assertFalse(compile(plan(List.of(n),Map.of())).valid());
    store.put("repo",artifact("repo",ArtifactType.DesignSpec)); assertFalse(compile(plan(List.of(n),Map.of("requiresUiChange",false,"requiresApiChange",false))).valid());
    store.put("repo",new ArtifactVersion("repo","other","p","task",ArtifactType.RepoSnapshot,1,"h","{}","USER",null,null,"base",List.of()));
    assertTrue(compile(plan(List.of(n),Map.of())).diagnostics().stream().anyMatch(d->d.code().equals("ARTIFACT_SCOPE")));
  }
  @Test void stableTopologicalOrderPreservesNonAdjacentDataAndRejectsCycles() {
    var a=node("a","document.requirements@1"); var b=node("b","document.design@1",upstream("requirements","a","requirements"));
    var c=node("c","code.implement@1",upstream("requirements","a","requirements"),pin("acceptance","ac"),pin("repository","repo"),upstream("design","b","design"));
    var plan=plan(List.of(c,b,a),Map.of("requiresUiChange",true,"requiresApiChange",false));
    var compiled=compile(plan); assertTrue(compiled.valid(),compiled.diagnostics().toString());assertEquals(List.of("a","b","c"),compiled.order());
    var cycle=new Plan(3,"r1","ws","p","task",1,null,"base","policy",10,plan.nodes(),List.of(new Edge("c","a")),plan.facts());assertFalse(compile(cycle).valid());
  }
  @Test void selfReportedConditionsCannotReplaceScopeEvidence() {
    store.put("scope",new ArtifactVersion("scope","ws","p","task",ArtifactType.RepositoryProfile,1,"fixture","{\"features\":{\"requiresUiChange\":false,\"requiresApiChange\":false}}","FIXTURE",null,null,"base",List.of()));
    var node=node("dev","code.implement@1",pin("requirements","prd"),pin("acceptance","ac"),pin("repository","repo"),pin("scopeEvidence","scope"),pin("design","design"));
    var p=plan(List.of(node),Map.of("requiresUiChange",true,"requiresApiChange",false));
    assertTrue(new PlanCompiler(Definitions.standard(),store::get).compile(p).diagnostics().stream().anyMatch(d->d.code().equals("APPLICABILITY_EVIDENCE")));
  }
  @Test void canonicalHashIgnoresObjectKeyOrderButNotAbsentOrPolicy() {
    assertEquals(Canonical.digest(Map.of("a",1,"b",2)),Canonical.digest(new LinkedHashMap<>(Map.of("b",2,"a",1))));
    assertNotEquals(Canonical.digest(Map.of("optional","ABSENT")),Canonical.digest(Map.of("optional","")));
  }
}
