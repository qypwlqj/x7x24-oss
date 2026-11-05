package com.x7x24.workflow;
import java.util.*;
import static com.x7x24.workflow.Workflow.*;
/** Small versioned registry. Registering a contract does not grant tools or permission. */
public final class Definitions {
  private Definitions() {}
  static Port port(String name, ArtifactType t) { return new Port(name,t,1,Requirement.REQUIRED,null,false,1); }
  public static Map<String,Definition> standard() {
    var m=new LinkedHashMap<String,Definition>();
    m.put("document.requirements@1",new Definition("document.requirements@1",ExecutorKind.HUMAN,true,List.of(),List.of(port("requirements",ArtifactType.RequirementSpec)),Set.of()));
    m.put("document.design@1",new Definition("document.design@1",ExecutorKind.HUMAN,true,List.of(port("requirements",ArtifactType.RequirementSpec)),List.of(port("design",ArtifactType.DesignSpec)),Set.of()));
    m.put("code.implement@1",new Definition("code.implement@1",ExecutorKind.AGENT,true,List.of(port("requirements",ArtifactType.RequirementSpec),port("acceptance",ArtifactType.AcceptanceCriteria),port("repository",ArtifactType.RepoSnapshot),port("scopeEvidence",ArtifactType.RepositoryProfile),
      new Port("design",ArtifactType.DesignSpec,1,Requirement.CONDITIONAL,"requiresUiChange",true,1),
      new Port("apiContract",ArtifactType.APIContract,1,Requirement.CONDITIONAL,"requiresApiChange",false,1),
      new Port("failureEvidence",ArtifactType.TestReport,1,Requirement.OPTIONAL,null,false,10)),List.of(port("patch",ArtifactType.PatchSet)),Set.of("legacy-json-patch")));
    var implementation=m.get("code.implement@1");
    var repairInputs=implementation.inputs().stream().map(p->p.name().equals("failureEvidence")?new Port(p.name(),p.type(),p.schema(),Requirement.REQUIRED,null,false,10):p).toList();
    m.put("code.repair@1",new Definition("code.repair@1",ExecutorKind.AGENT,true,repairInputs,implementation.outputs(),implementation.capabilities()));
    m.put("local.file-check@1",new Definition("local.file-check@1",ExecutorKind.SYSTEM,true,List.of(port("patch",ArtifactType.PatchSet),port("repository",ArtifactType.RepoSnapshot),port("acceptance",ArtifactType.AcceptanceCriteria)),List.of(port("report",ArtifactType.TestReport)),Set.of("controlled-file-checks")));
    // No runner has been integrated yet. A plan cannot claim real verification through this catalogue entry.
    m.put("test.verify@1",new Definition("test.verify@1",ExecutorKind.SYSTEM,false,List.of(port("patch",ArtifactType.PatchSet),port("acceptance",ArtifactType.AcceptanceCriteria)),List.of(port("report",ArtifactType.TestReport)),Set.of("isolated-test-runner")));
    return Collections.unmodifiableMap(m);
  }
}
