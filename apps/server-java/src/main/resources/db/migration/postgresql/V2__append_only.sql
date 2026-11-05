CREATE FUNCTION x7x24_forbid_mutation() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION '% is append-only', TG_TABLE_NAME; END; $$ LANGUAGE plpgsql;
CREATE TRIGGER task_event_no_update BEFORE UPDATE OR DELETE ON task_event FOR EACH ROW EXECUTE FUNCTION x7x24_forbid_mutation();
CREATE TRIGGER evidence_no_update BEFORE UPDATE OR DELETE ON evidence FOR EACH ROW EXECUTE FUNCTION x7x24_forbid_mutation();
