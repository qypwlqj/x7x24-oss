package com.x7x24.workflow;
import com.x7x24.engine.TaskRepo;
import com.x7x24.pipeline.*;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.x7x24.workflow.Workflow.*;
/** M1 locks existing instruction skills only; no skill creates a capability or validation PASS. */
@Component
public class RoleSnapshotResolver {
  final PipelineService pipes; final TaskRepo tasks; final com.x7x24.llm.ModelRouter router; final com.x7x24.gov.ConfigStore governance; final com.x7x24.skills.SkillReleases releases;
  public RoleSnapshotResolver(PipelineService pipes,TaskRepo tasks,com.x7x24.llm.ModelRouter router,com.x7x24.gov.ConfigStore governance,com.x7x24.skills.SkillReleases releases){this.pipes=pipes;this.tasks=tasks;this.router=router;this.governance=governance;this.releases=releases;}
  public Plan lock(Plan p){
    var t=java.util.Objects.requireNonNull(tasks.get(p.taskId()));var effective=pipes.snapshot(t.context.configSnapshot,t.productLine);
    List<Node> nodes=new ArrayList<>();
    for(var n:p.nodes()){
      Map<String,RoleSnapshot> roles=new TreeMap<>();Map<String,Object> effectiveLocks=new TreeMap<>();
      if(Set.of("code.implement@1","code.repair@1").contains(n.definitionRef()))for(String slot:List.of("maker","fixer")){
        var role=RolePlans.resolve(effective,"DEV",slot,slot.equals("maker")?"dev.code":"dev.fix");
        var configured=effective.roles.get(role.roleId());List<SkillSnapshot> skills=new ArrayList<>();
        var refs=new LinkedHashSet<String>();if(configured!=null && configured.skills!=null)refs.addAll(configured.skills);var stage=effective.stage("DEV");if(stage!=null && stage.skills!=null)refs.addAll(stage.skills);
        for(String ref:refs){var skill=effective.skillVersions==null?null:effective.skillVersions.get(ref);if(skill==null)throw new WorkflowStore.Blocked("LEGACY_SKILL_UNVERIFIED","固定的技能版本不可读取："+ref);skills.add(new SkillSnapshot(skill.id,skill.version,Canonical.digest(skill),skill.instructions));}
        var catalogue=new TreeMap<String,com.x7x24.skills.SkillResolver.Version>();
        effective.skillVersions.forEach((ref,skill)->catalogue.put(skill.id+"@"+skill.version+".0.0",new com.x7x24.skills.SkillResolver.Version(skill.id,skill.version+".0.0",skill.instructions,Canonical.digest(skill),List.of(),Map.of(),Set.of("instruction-load"))));
        var bindings=new ArrayList<com.x7x24.skills.SkillResolver.Binding>();
        for(String ref:refs){var skill=effective.skillVersions.get(ref);bindings.add(new com.x7x24.skills.SkillResolver.Binding("legacy-"+slot+"-"+ref,p.workspaceId(),p.projectId(),com.x7x24.skills.SkillResolver.Scope.PROJECT,null,slot,skill.id,skill.version+".0.0",com.x7x24.skills.SkillResolver.Enforcement.OPTIONAL,null,Map.of()));}
        if(n.config()!=null && n.config().get("skillBindings")!=null)bindings.addAll(Arrays.asList(com.x7x24.util.Json.convert(n.config().get("skillBindings"),com.x7x24.skills.SkillResolver.Binding[].class)));
        var resolver=new com.x7x24.skills.SkillResolver();var lock=resolver.resolve(new com.x7x24.skills.SkillResolver.Domain(p.workspaceId(),p.projectId(),n.id(),role.digest(),slot,role.roleId()),bindings,catalogue,releases.revoked());
        String additional=lock.skills().stream().filter(skill->skills.stream().noneMatch(existing->existing.id().equals(skill.id()))).map(skill->skill.instructions()).collect(java.util.stream.Collectors.joining("\n"));
        roles.put(slot,new RoleSnapshot(slot,role.roleId(),role.modelProfile(),List.copyOf(skills),role.instructions()+"\n"+additional,Canonical.digest(List.of(role.digest(),lock.digest())),Canonical.digest(router.config())));
        effectiveLocks.put(slot,lock);

      }
      var config=new TreeMap<String,Object>(n.config()==null?Map.of():n.config());if(!effectiveLocks.isEmpty())config.put("_effectiveSkillLocks",effectiveLocks);
      nodes.add(new Node(n.id(),n.logicalKey(),n.definitionRef(),Map.copyOf(config),n.bindings(),Map.copyOf(roles)));
    }
    return new Plan(p.schema(),p.revisionId(),p.workspaceId(),p.projectId(),p.taskId(),p.revisionNo(),p.parentRevisionId(),p.baseSha(),Canonical.digest(governance.get()),p.budgetUsd(),List.copyOf(nodes),p.controls(),p.facts());
  }
}
