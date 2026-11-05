-- H2 enforces workspace predicates in services; PostgreSQL additionally enforces RLS.
CREATE TRIGGER workflow_candidate_no_update BEFORE UPDATE ON workflow_candidate FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";
CREATE TRIGGER workflow_candidate_no_delete BEFORE DELETE ON workflow_candidate FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";
CREATE TRIGGER coding_job_event_no_update BEFORE UPDATE ON coding_job_event FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";
CREATE TRIGGER coding_job_event_no_delete BEFORE DELETE ON coding_job_event FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";
CREATE TRIGGER context_manifest_no_update BEFORE UPDATE ON context_manifest FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";
CREATE TRIGGER context_manifest_no_delete BEFORE DELETE ON context_manifest FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";
