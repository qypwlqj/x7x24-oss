"""M4：stream-json → 精简工具调用记录（脱敏、合并、限流）"""
import json, unittest
import runnerd


class FakeJob:
    def __init__(self):
        self.logs = []

    def log(self, kind, text, secrets=(), limit=4000):
        self.logs.append({"kind": kind, "text": runnerd.redact(str(text), secrets)[:limit]})


class Clock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t


def use(i, name, inp):
    return {"type": "tool_use", "id": i, "name": name, "input": inp}


def res(i, out, err=False):
    return {"type": "tool_result", "tool_use_id": i, "content": out, "is_error": err}


class RedactTest(unittest.TestCase):
    def test_patterns_and_env_names(self):
        # fake test keys — synthetic fragments joined at runtime for redaction coverage
        aws = "AKIA" + "ABCDEFGHIJKLMNOP"
        pem = ("-----BEGIN " + "RSA PRIVATE KEY-----\n") + "MIIE\n" + ("-----END " + "RSA PRIVATE KEY-----")
        s = ("key sk-" + "abcdefghijklmnopqrstuvwx and ghp_" + "a" * 30 + " ANTHROPIC_API_KEY=zzz123 GITHUB_TOKEN: q1 "
             'DB_PASSWORD="hunter2" Authorization: Bearer abc.def ' + aws + ' eyJhbGciOiJIUzI1.eyJzdWIiOiIxMjM0.c2lnbmF0dXJlMTI '
             + pem)
        r = runnerd.redact(s, ["extra-secret-value"])
        for leak in ("sk-abc", "ghp_", "zzz123", "q1 ", "hunter2", "abc.def", "AKIA", "eyJhbGci", "MIIE"):
            self.assertNotIn(leak, r, leak)
        self.assertIn("ANTHROPIC_API_KEY=***", r)
        self.assertIn("Authorization: Bearer ***", r)
        self.assertEqual(runnerd.redact("x extra-secret-value y", ["extra-secret-value"]), "x *** y")

    def test_plain_text_untouched(self):
        self.assertEqual(runnerd.redact("npm test -- --run grades"), "npm test -- --run grades")


class TrackerTest(unittest.TestCase):
    def test_reports_observed_model_and_changes_without_guessing_from_config(self):
        runner = runnerd.Runner.__new__(runnerd.Runner)
        job = runnerd.Job({"jobKey": "job-test", "model": "configured-model"})
        self.assertIsNone(job.view()["result"])
        runner.event(job, json.dumps({"type": "system", "subtype": "init", "model": "gateway-alias"}), [])
        runner.event(job, json.dumps({"type": "assistant", "message": {"model": "gateway-alias", "content": []}}), [])
        runner.event(job, json.dumps({"type": "assistant", "message": {"model": "runtime-resolved", "content": []}}), [])
        runner.event(job, json.dumps({"type": "system", "subtype": "other", "model": "ignore"}), [])
        self.assertEqual([json.loads(e["text"])["model"] for e in job.logs], ["gateway-alias", "runtime-resolved"])
        self.assertIsNone(job.view()["result"])
        job.state, job.result = "SUCCEEDED", {"summary": "done"}
        self.assertEqual(job.view()["result"], {"summary": "done", "observedModel": "runtime-resolved"})

    def test_terminal_model_is_redacted_and_optional(self):
        runner = runnerd.Runner.__new__(runnerd.Runner)
        job = runnerd.Job({"jobKey": "job-test", "model": "configured-model"})
        job.state = "FAILED"
        self.assertIsNone(job.view()["result"])
        runner.event(job, json.dumps({"type": "system", "subtype": "init", "model": "gateway/secret-key"}), ["secret-key"])
        self.assertEqual(job.view()["result"], {"observedModel": "gateway/***"})
        self.assertEqual(json.loads(job.logs[0]["text"]), {"model": "gateway/***"})

    def setUp(self):
        self.job, self.clk = FakeJob(), Clock()
        self.t = runnerd.ToolTracker(self.job, ["s3cr3t-value"], clock=self.clk, flush_sec=3.0, max_items=5, out_max=50)

    def batches(self):
        return [json.loads(l["text"]) for l in self.job.logs if l["kind"] == "tools"]

    def test_pairs_use_and_result_with_duration_and_summary(self):
        self.t.use(use("a", "Bash", {"command": "npm   test  --  --run", "description": "x"}))
        self.clk.t = 1.25
        self.t.result(res("a", [{"type": "text", "text": "PASS 41 tests token=s3cr3t-value"}]))
        self.t.flush()
        it = self.batches()[0]["items"][0]
        self.assertEqual(it["name"], "Bash"); self.assertEqual(it["args"], "npm test -- --run"); self.assertEqual(it["ms"], 1250); self.assertTrue(it["ok"])
        self.assertNotIn("s3cr3t-value", self.job.logs[0]["text"])

    def test_edit_args_never_include_file_content(self):
        self.assertEqual(runnerd.args_summary("Edit", {"file_path": "src/a.ts", "old_string": "SECRET", "new_string": "x"}), "src/a.ts")
        self.assertNotIn("SECRET", runnerd.args_summary("Write", {"content": "SECRET"}))

    def test_truncates_output_and_flags_failures_immediately(self):
        self.t.use(use("b", "Bash", {"command": "npm test"}))
        self.t.result(res("b", "x" * 200 + "FAIL export.test.ts", err=True))
        b = self.batches(); self.assertEqual(len(b), 1, "失败立即 flush")
        it = b[0]["items"][0]
        self.assertFalse(it["ok"]); self.assertTrue(it["truncated"]); self.assertTrue(it["out"].endswith("FAIL export.test.ts")); self.assertLessEqual(len(it["out"]), 50)

    def test_merges_consecutive_reads_and_rate_limits(self):
        for i in range(4):
            self.t.use(use(f"r{i}", "Read", {"file_path": f"src/f{i}.ts"})); self.t.result(res(f"r{i}", "content"))
        self.assertEqual(self.batches(), [], "3 秒内、不足 5 条不写")
        self.clk.t = 3.5
        self.t.use(use("g", "Grep", {"pattern": "export"})); self.t.result(res("g", "3 matches"))
        items = self.batches()[0]["items"]
        self.assertEqual(len(items), 2); self.assertEqual(items[0]["count"], 4); self.assertEqual(items[1]["name"], "Grep")

    def test_browser_tool_is_marked_and_flushed(self):
        self.t.use(use("p", "mcp__playwright__browser_navigate", {"url": "http://localhost:5173/grades"}))
        self.t.result(res("p", [{"type": "text", "text": "ok"}, {"type": "image"}]))
        it = self.batches()[0]["items"][0]
        self.assertTrue(it["browser"]); self.assertEqual(it["args"], "http://localhost:5173/grades")

    def test_event_mapping_from_stream_json(self):
        r = runnerd.Runner.__new__(runnerd.Runner); job = FakeJob()
        lines = [{"type": "assistant", "message": {"content": [{"type": "text", "text": "先跑测试"}, use("x", "Bash", {"command": "ls"})]}},
                 {"type": "user", "message": {"content": [res("x", "a\nb")]}},
                 {"type": "result", "subtype": "success", "num_turns": 3, "result": "done", "usage": {}}]
        job.result = None
        for l in lines:
            r.event(job, json.dumps(l), [])
        kinds = [l["kind"] for l in job.logs]
        self.assertEqual(kinds, ["text", "tools", "result"])
        self.assertEqual(json.loads(job.logs[1]["text"])["items"][0]["args"], "ls")


if __name__ == "__main__":
    unittest.main()
