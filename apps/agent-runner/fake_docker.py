#!/usr/bin/env python3
"""测试用假 docker：模拟 agent 修改仓库并输出 stream-json。FAKE_MODE=ok|slow|fail。"""
import json, os, subprocess, sys, time
args = sys.argv[1:]
if args and args[0] == "kill":
    open(os.environ["FAKE_KILLED"], "w").write(args[1]); sys.exit(0)
if "/opt/x7/ocr" in args:
    # 逐行审查：把容器路径换成宿主路径，直接跑挂进来的（假）ocr
    vols = {}
    for i, a in enumerate(args):
        if a == "-v":
            parts = args[i + 1].split(":"); vols[parts[1]] = parts[0]
    i = args.index("/opt/x7/ocr")
    rest = [vols["/out"] + a[4:] if a.startswith("/out/") else a for a in args[i + 1:]]
    env = dict(os.environ, HOME=vols["/home/ocr"])
    sys.exit(subprocess.run([vols["/opt/x7/ocr"], *rest], cwd=vols["/work"], env=env).returncode)
repo = next(a.split(":")[0] for a in args if a.endswith(":/work"))
if os.environ.get("FAKE_ARGS"):
    open(os.environ["FAKE_ARGS"], "w").write(json.dumps(args))
if "/opt/x7/acceptance.sh" in args:
    out = next(a.split(":")[0] for a in args if a.endswith(":/out"))
    acc = os.environ.get("FAKE_ACC", "pass")
    os.makedirs(out + "/shots", exist_ok=True); os.makedirs(out + "/a11y", exist_ok=True)
    print("app ready", flush=True)
    if acc == "nostart":
        open(out + "/status.json", "w").write('{"phase":"start","code":1}'); sys.exit(4)
    bad = acc == "fail"
    rep = {"stats": {"expected": 0 if bad else 1, "unexpected": 1 if bad else 0, "flaky": 0},
           "suites": [{"title": "a.spec.mjs", "file": "a.spec.mjs", "specs": [{"title": "AC-1 显示登录按钮", "tests": [{"results": [{"status": "failed" if bad else "passed", "error": {"message": "expect failed " + ("ghp_" + "abcdefghijklmnopqrstuvwxyz0123")} if bad else None}]}]}]}]}
    open(out + "/report.json", "w").write(json.dumps(rep))
    open(out + "/shots/00-root.png", "wb").write(b"\x89PNG\r\n\x1a\nfake")
    open(out + "/a11y/00-root.txt", "w").write('- button "登录"')
    open(out + "/app.log", "w").write("listening on 5173")
    open(out + "/status.json", "w").write('{"phase":"tests","code":%d}' % (1 if bad else 0))
    sys.exit(0)
mode = os.environ.get("FAKE_MODE", "ok")
log = lambda o: print(json.dumps(o), flush=True)
log({"type": "assistant", "message": {"content": [{"type": "tool_use", "name": "Bash", "input": {"command": "npm test"}}]}})
log({"type": "assistant", "message": {"content": [{"type": "text", "text": "leaked " + ("sk-" + "abcdefghijklmnopqrstuvwxyz") + " should be hidden"}]}})
if mode == "slow":
    time.sleep(30)
if mode == "fail":
    sys.exit(3)
with open(os.path.join(repo, "math.js"), "w") as f:
    f.write("export const add = (a, b) => a + b;\n")
os.remove(os.path.join(repo, "old.js"))
open(os.path.join(repo, "scratch.log"), "w").write("tmp")
g = ["git", "-C", repo]
subprocess.run(g + ["add", "math.js", "old.js"], check=True)
subprocess.run(g + ["commit", "-qm", "fix"], check=True)
log({"type": "result", "subtype": "success", "num_turns": 5, "result": "fixed add", "usage": {"input_tokens": 100, "output_tokens": 20, "cache_read_input_tokens": 300}, "total_cost_usd": 0.01})
