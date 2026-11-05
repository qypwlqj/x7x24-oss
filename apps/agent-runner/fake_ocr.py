#!/usr/bin/env python3
"""测试用假 ocr：按 FAKE_OCR_MODE（ok|fail|nofile）输出录制好的 ocr v1.13.5 JSON（testdata/ocr-*.json）。
把参数和「是否拿到模型密钥」写到 FAKE_OCR_ARGS（不写密钥本身）。"""
import json, os, sys
here = os.path.dirname(os.path.abspath(__file__))
args = sys.argv[1:]
if os.environ.get("FAKE_OCR_ARGS"):
    with open(os.environ["FAKE_OCR_ARGS"], "w") as f:
        json.dump({"args": args, "cwd": os.getcwd(), "home": os.environ.get("HOME"), "hasToken": bool(os.environ.get("OCR_LLM_TOKEN")),
                   "model": os.environ.get("OCR_LLM_MODEL"), "url": os.environ.get("OCR_LLM_URL"), "protocol": os.environ.get("OCR_LLM_PROTOCOL")}, f)
mode = os.environ.get("FAKE_OCR_MODE", "ok")
out = args[args.index("--output") + 1]
if mode == "nofile":
    print("Error: no valid LLM endpoint configured", file=sys.stderr); sys.exit(1)
name = {"ok": "ocr-complete.json", "fail": "ocr-failed.json"}[mode]
if "--resume" in args and mode == "ok":
    name = "ocr-resumed.json"
with open(os.path.join(here, "testdata", name)) as f:
    doc = json.load(f)
doc["llm"]["model"] = os.environ.get("OCR_LLM_MODEL", "x")
os.makedirs(os.path.join(os.environ["HOME"], ".opencodereview", "sessions"), exist_ok=True)
with open(out, "w") as f:
    json.dump(doc, f)
print("[ocr] Results written to " + out, file=sys.stderr)
sys.exit(0 if mode == "ok" else 1)
