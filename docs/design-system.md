# 7x24 设计系统 · 纸面

方向：与首页同一套纸面。底色 `#e7e7e7`，墨色 `#050505`，主按钮墨色填充、字色 `#dcdcda`。拉丁字母和数字用 IBM Plex Mono，中文回落到 PingFang SC / Hiragino Sans GB / Noto Sans CJK SC / 微软雅黑。只有这一套主题。旧的 `localStorage` 键 `x7x24-theme-v2` 不再切换配色：`:root` 与 `.light` 写在同一组变量上。

首页的像素标题、扫描线视频和气泡只属于首页。控制台页标题是 IBM Plex Mono 文本。

## Tokens

唯一取值来源：`apps/web/src/styles/tokens.css`。`tailwind.config.js` 把它们映射成 Tailwind 类。组件写语义类（`bg-surface`、`text-ink-2`、`border-line`），不写色值。`shadow-glow-*` 仍映射到变量，变量值是 `none`。

| 语义 | 值 | 用途 |
|---|---|---|
| `canvas` / `surface` / `raised` / `sunken` | #e7e7e7 / #f3f3f1 / #f3f3f1 / #dcdcd8 | 页面 / 面板 / 浮层 / 下沉底 |
| `line` / `line-strong` | #c4c4c0 / #5c5c58 | 分隔线 / 控件边框 |
| `ink` / `ink-2` / `ink-3` | #050505 / #3a3a38 / #5c5c58 | 主文字 / 次要 / 辅助 |
| `primary` | #050505，悬停 #2a2a2a，字色 `#dcdcda` | 主操作、当前导航 |
| `accent` | #6e4420，浅底 #ece2d6 | 回路 / 返修，不是品红 |
| `success` / `warn` / `danger` | #126432 / #8c5a0a / #b02030，各有浅底 | 成功 / 警告 / 失败。保持可区分，不收成纯黑 |
| `info` | #282828，浅底 #dcdcd8 | 信息，接近墨色，不用蓝 |
| 流转 / 返修 / 异常 | 黑实线 / 深棕虚线 / 暗红点线 | `--c-neon-cyan` / `--c-neon-magenta` / `--c-neon-red` 已改成这三色，旧引用跟着走 |

**对比度（按 sRGB 相对亮度核算）**：`ink` 在 canvas 上 16.5:1，在 surface 上 18.3:1；`ink-2` 在 canvas 上 9.2:1；`ink-3` 在 canvas 上 5.4:1，在 sunken 上 4.9:1。主按钮字色在墨色填充上 14.8:1。状态字在对应浅底上：成功 5.7:1，警告 4.8:1，失败 5.2:1，信息 10.7:1，返修棕 6.5:1。控件边框 `line-strong` 在 canvas 上 5.4:1。状态不单靠颜色：徽标带文字，流程图靠实线 / 虚线 / 点线区分。

成功绿从初稿的 `#167840` 加深到 `#126432`，否则浅底上的成功字只有 4.3:1。

**字体**（自托管，`@fontsource/ibm-plex-mono` 的 latin 400 与 500，不走外链 CDN）：`--font-sans`、`--font-display`、`--font-mono` 都是 IBM Plex Mono，中文回落同上。数字用 `.num` / `.tabular`。

**圆角** 2px。`.cut` 类名还在，便于现有按钮测试，但 `--cut` 为 0，不再使用 `clip-path`，切口斜线 `content: none`。

**发光**：`--glow-cyan / magenta / lime / red` 为 `none`，`--glow-a` 为 0。

**氛围层**：`--fx-grid / --fx-scan / --fx-noise` 均为 0。网格、扫描线和噪点不绘制。

**间距与动效**：沿用 `--space-page`、`--sidebar-w`、`--dur-fast/base/slow`。

## 组件库（`apps/web/src/ui`）

`Button`（primary 墨色实心 / secondary 墨线 / ghost / danger 暗红）`IconButton` · `Badge`（浅底 + 同色细边 + 文字）· `Card`（纸片，标题栏左侧双道墨线）· `Tabs` · `Table` · `Drawer` `Modal` · `Toggle` · `StatTile`（纸片，标签下一条状态色铅笔短线）· `Sparkline` · `Input` `Select` `Textarea` `Field` · `OptionCard` · `Progress` · `Callout` · `Empty` · `PageHeader`（`//` 为辅助墨色，标题不发光）· `KV` · `Icon`。侧栏没有主题切换。`useTheme` 固定返回 `paper`，并清掉 `html.light`。

当前导航项是墨色底、`#dcdcda` 字。左上角标记与首页同一枚 22px 星形。

## 稿纸笔触

面板、指标卡、输入、下拉和选项用 `.sheet`。直边 1px 墨线，硬投影 `--sheet-shadow`（卡片 `2px 3px`，控件 `.sheet-sm` 为 `1px 2px`），纸纹 `--paper-grain` 只铺在纸片上。页面 `--fx-noise` 仍为 0。

流水线节点用 `sheetPath()`：固定铅笔矩形，选中时在外面再偏一圈铅笔线。泳道是下沉底加横格，左侧双道墨线。箭头是开口折线，连线次数是小纸片。任务节点进行中或卡住时，页边一个 3px 方块闪烁，不用投影。页边双线：实线是正常，虚线是退回或跳过，点线是卡住。

指标卡的状态色画在标签下的短铅笔线上。选项未选是浅墨边；选中是墨线、浅墨底、右上角铅笔勾。会改仓库的危险项用暗红边和浅红底。复选框里是一笔勾。开关滑槽是短横格，滑块是小纸片。

## 交付流水线与脉冲动画

`components/flowLayout.ts` · `components/PipelineFlow.tsx` · `pulse.ts`。

- **三类连线**：正常流转 = 黑色实线；回路 / 返修 / 回滚 = 深棕虚线；异常 / 废弃 = 暗红点线。实时状态是 3px 墨色方块，不再用发光圆点。
- **持续脉冲** `.pulse-ambient`：`stroke-dashoffset` 动画，无投影。密度和速度仍由 `ambientPulse` 决定。
- **事件脉冲**：`.pulse-burst` 与 `.node-hit` 只改描边，不再加 `drop-shadow`。选中节点在原描边外再偏一圈铅笔线。
- **降级**：`prefers-reduced-motion: reduce` 下持续脉冲变为静态半透明线，事件脉冲在 TTL 内显示为静态高亮。
- 任务流程图 `TaskFlow` 仍读取 `--c-neon-*`。这三项变量已改成墨色 / 深棕 / 暗红，所以线型颜色与总览一致。

## 无障碍

- 全局 `:focus-visible` 为 1px 墨色描边，偏移 2px。按钮和输入框不再用发光阴影盖住这条描边。
- 跳转链接「跳到主要内容」、`<nav aria-label>`、`aria-current="page"`。
- 流程图节点保留 `aria-label`（驻留 / 流量）。图例写线型，不写已经不用的颜色名。
- 浮层有 `role=dialog aria-modal`。
- 氛围层强度为 0。

## 截图与演示

截图只针对本地固定数据，并在分享前逐张审查。
