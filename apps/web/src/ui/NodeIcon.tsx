/* 指挥台 v2 节点图例（docs/command-center-v2/icons，稿纸铅笔风，24×24，stroke 1.8）。颜色用 currentColor，由 CSS 决定。 */
import type { SVGProps } from 'react';

const P: Record<string, string> = {
  'agent-think': "<circle cx=\"12\" cy=\"11\" r=\"6.5\"/><circle cx=\"9.6\" cy=\"10.4\" r=\".6\" fill=\"currentColor\"/><circle cx=\"14.4\" cy=\"10.4\" r=\".6\" fill=\"currentColor\"/><path d=\"M9.8 14c1.3.9 3.1.9 4.4 0\"/><path d=\"M12 2.5v2\"/><path d=\"M8.5 20.8l1.6-3.6M15.5 20.8l-1.6-3.6\"/>",
  'await-approval': "<path d=\"M6 3.5h12M6 20.5h12\"/><path d=\"M7.5 3.5c0 4.5 4.5 5.5 4.5 8.5S7.5 16 7.5 20.5M16.5 3.5c0 4.5-4.5 5.5-4.5 8.5s4.5 4 4.5 8.5\"/><path d=\"M10 18.3h4\"/>",
  'blocked': "<path d=\"M8.2 3.5h7.6l5.4 5.4v7.6l-5.4 5.4H8.2l-5.4-5.4V8.9z\"/><path d=\"M7.8 12h8.4\"/>",
  'budget': "<ellipse cx=\"12\" cy=\"6.5\" rx=\"7\" ry=\"2.8\"/><path d=\"M5 6.5v5.5c0 1.5 3.1 2.8 7 2.8s7-1.3 7-2.8V6.5\"/><path d=\"M5 12v5.5c0 1.5 3.1 2.8 7 2.8s7-1.3 7-2.8V12\"/>",
  'ci-fail': "<circle cx=\"12\" cy=\"12\" r=\"8.2\"/><path d=\"M9 9l6 6M15 9l-6 6\"/>",
  'ci-pass': "<circle cx=\"12\" cy=\"12\" r=\"8.2\"/><path d=\"M8 12.3l2.8 2.8 5.3-5.6\"/>",
  'ci-running': "<circle cx=\"12\" cy=\"12\" r=\"8.2\" stroke-dasharray=\"3.2 2.4\"/><path d=\"M12 7.5V12l3 2\"/>",
  'direction': "<circle cx=\"12\" cy=\"12\" r=\"8.5\"/><path d=\"M15.5 8.5l-2.2 4.8-4.8 2.2 2.2-4.8z\"/>",
  'done': "<path d=\"M5 20.5V3.5\"/><path d=\"M5 4.5h12.5l-2.6 4 2.6 4H5\"/>",
  'loop-back': "<path d=\"M19.5 12a7.5 7.5 0 1 1-2.2-5.3\"/><path d=\"M19.8 3.8v3.6h-3.6\"/>",
  'node-accept': "<rect x=\"3\" y=\"4.5\" width=\"18\" height=\"13\" rx=\"1.4\"/><path d=\"M3 8h18\"/><circle cx=\"5.3\" cy=\"6.3\" r=\".3\"/><path d=\"M8.8 13l2.2 2.2 4.4-4.5\"/><path d=\"M8 20.5h8\"/>",
  'node-audit': "<path d=\"M12 3.2l7.5 3v5.6c0 4.4-3.1 7.7-7.5 9-4.4-1.3-7.5-4.6-7.5-9V6.2z\"/><path d=\"M8.8 12.2l2.3 2.3 4.2-4.4\"/>",
  'node-code': "<path d=\"M8.5 7.5L3.8 12l4.7 4.5\"/><path d=\"M15.5 7.5l4.7 4.5-4.7 4.5\"/><path d=\"M13.3 5l-2.6 14\"/>",
  'node-design': "<rect x=\"3.5\" y=\"3.5\" width=\"7\" height=\"7\" rx=\"1\"/><circle cx=\"17\" cy=\"7\" r=\"3.5\"/><path d=\"M4 20.5l3.5-6 3.5 6z\"/><path d=\"M14 14h6.5v6.5H14z\" stroke-dasharray=\"2 1.6\"/>",
  'node-enter': "<path d=\"M3.2 12.1h10.6\"/><path d=\"M10.2 8.3l3.8 3.8-3.8 3.9\"/><rect x=\"15.6\" y=\"5.2\" width=\"5.4\" height=\"13.6\" rx=\"1.2\" fill=\"none\"/>",
  'node-intake': "<path d=\"M5 3.5h10l4 4v13H5z\"/><path d=\"M15 3.5v4h4\"/><path d=\"M8.2 12h7.4M8.2 15.6h5\"/>",
  'node-plan': "<path d=\"M4 5.5h2M4 12h2M4 18.5h2\"/><path d=\"M9 5.5h11M9 12h8M9 18.5h10\"/>",
  'node-spec': "<path d=\"M4.5 19.5l2-5.2L16.8 4a1.9 1.9 0 0 1 2.7 2.7L9.2 17z\"/><path d=\"M14.8 6l2.7 2.7\"/><path d=\"M4.5 19.5h15\"/>",
  'node-test': "<path d=\"M9 3.5h6M10 3.5v5.2L4.8 18a1.8 1.8 0 0 0 1.6 2.6h11.2a1.8 1.8 0 0 0 1.6-2.6L14 8.7V3.5\"/><path d=\"M7.3 14.5h9.4\"/>",
  'pr-merged': "<circle cx=\"6\" cy=\"5.5\" r=\"2.2\"/><circle cx=\"6\" cy=\"18.5\" r=\"2.2\"/><circle cx=\"18\" cy=\"12\" r=\"2.2\"/><path d=\"M6 7.7v8.6\"/><path d=\"M6 7.7c0 3.2 3.6 4.3 9.8 4.3\"/>",
  'pr-open': "<circle cx=\"6\" cy=\"5.5\" r=\"2.2\"/><circle cx=\"6\" cy=\"18.5\" r=\"2.2\"/><circle cx=\"18\" cy=\"18.5\" r=\"2.2\"/><path d=\"M6 7.7v8.6\"/><path d=\"M18 16.3V9.5a2.6 2.6 0 0 0-2.6-2.6H11\"/><path d=\"M13.3 4.6L11 6.9l2.3 2.3\"/>",
  'screenshot': "<path d=\"M4 8V5.5A1.5 1.5 0 0 1 5.5 4H8M16 4h2.5A1.5 1.5 0 0 1 20 5.5V8M20 16v2.5a1.5 1.5 0 0 1-1.5 1.5H16M8 20H5.5A1.5 1.5 0 0 1 4 18.5V16\"/><circle cx=\"12\" cy=\"12\" r=\"3.2\"/>",
  'skipped': "<path d=\"M5 6l6 6-6 6\"/><path d=\"M12 6l6 6-6 6\"/><path d=\"M3 21L21 3\" stroke-dasharray=\"2 2\"/>",
  'tool-run': "<rect x=\"3.5\" y=\"4.5\" width=\"17\" height=\"15\" rx=\"1.6\"/><path d=\"M7 9.5l3 2.5-3 2.5\"/><path d=\"M12.5 15h4.5\"/>",
  'user-input': "<path d=\"M4.5 5h15v10.5h-8L7 19.5v-4H4.5z\"/><path d=\"M8.5 10.3h.01M12 10.3h.01M15.5 10.3h.01\" stroke-width=\"2.4\"/>",
};
export type NodeIconName = 'agent-think' | 'await-approval' | 'blocked' | 'budget' | 'ci-fail' | 'ci-pass' | 'ci-running' | 'direction' | 'done' | 'loop-back' | 'node-accept' | 'node-audit' | 'node-code' | 'node-design' | 'node-enter' | 'node-intake' | 'node-plan' | 'node-spec' | 'node-test' | 'pr-merged' | 'pr-open' | 'screenshot' | 'skipped' | 'tool-run' | 'user-input';
export const NODE_ICON_NAMES = Object.keys(P) as NodeIconName[];
export function NodeIcon({ name, size = 18, ...rest }: { name: NodeIconName; size?: number } & Omit<SVGProps<SVGSVGElement>, 'name'>) {
  return <svg viewBox="0 0 24 24" width={size} height={size} fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" data-icon={name} {...rest} dangerouslySetInnerHTML={{ __html: P[name] }} />;
}
