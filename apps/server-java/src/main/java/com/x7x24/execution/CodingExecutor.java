package com.x7x24.execution;
import java.util.*;
/** Versioned control-plane protocol; capability labels describe what the adapter actually implements. */
public interface CodingExecutor {
 enum State { CREATED, RUNNING, CANCEL_REQUESTED, SUCCEEDED, FAILED, CANCELLED, OUTCOME_UNKNOWN }
 enum Action { READ, SEARCH, EDIT, PUT, ASSERT_CONTAINS, ASSERT_JSON }
 record Step(Action action,String path,String search,String replacement,String acceptanceId) {}
 record Limits(int maxFiles,int maxBytes,int maxSteps,long deadlineAt) {}
 record JobSpec(int protocolVersion,String workspaceId,String projectId,String taskId,String nodeRunId,String ownerId,long epoch,String operationKey,String baseSha,String inputFingerprint,Map<String,String> baseFiles,List<Step> steps,List<String> writeScopes,Set<String> acceptanceIds,Limits limits,String networkPolicy) {}
 record Handle(String jobId,State state,long sequence) {}
 record Event(long sequence,String type,String subjectDigest,String detail,String at) {}
 record Check(String acceptanceId,String validator,String result,String subjectTreeHash,String command,String at) {}
 record Result(State state,String baseSha,String treeHash,Map<String,String> files,List<Check> checks,List<Event> events,String checkpointRef,List<String> missingEvidence,double actualUsd) {}
 Handle submit(JobSpec spec);
 Optional<Handle> lookupByKey(String workspaceId,String taskId,String key);
 Handle get(String workspaceId,String taskId,String jobId);
 List<Event> events(String workspaceId,String taskId,String jobId,long after);
 Handle cancel(String workspaceId,String taskId,String jobId,String reason);
 Handle resume(String workspaceId,String taskId,String jobId,String checkpointRef);
 Result exportResult(String workspaceId,String taskId,String jobId);
}
