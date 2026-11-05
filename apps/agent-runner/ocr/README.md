# open-code-review（ocr）— 第三方组件

- 上游：https://github.com/alibaba/open-code-review
- 版本：v1.13.5（tag commit abb7abcd，2026-11-03 发布）
- 许可证：Apache License 2.0，全文见同目录 `LICENSE`
- `skills/open-code-review/SKILL.md`：上游 `skills/open-code-review/SKILL.md` 原样拷贝，未做任何修改
  （sha256 `be45ee1303e5106cd36f20bd7854ea1a5bb41b0d6ae045953cb643201bcd715e`）。

x7x24 的「逐行审查」节点按这份 skill 的标准用法调用 ocr：
在 runner 沙箱里执行 `ocr review --audience agent --format json …`，由 ocr 自己调用模型、
分组、复核并输出 JSON；后端只解析结果。二进制由 `../install-ocr.sh` 下载并校验 sha256。
