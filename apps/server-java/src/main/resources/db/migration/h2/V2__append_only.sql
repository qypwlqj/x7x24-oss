CREATE TRIGGER task_event_no_update BEFORE UPDATE ON task_event FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";
CREATE TRIGGER task_event_no_delete BEFORE DELETE ON task_event FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";
CREATE TRIGGER evidence_no_update BEFORE UPDATE ON evidence FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";
CREATE TRIGGER evidence_no_delete BEFORE DELETE ON evidence FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";
