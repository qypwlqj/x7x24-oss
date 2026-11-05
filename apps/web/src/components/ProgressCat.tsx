import { useEffect, useLayoutEffect, useRef, type RefObject } from 'react';
import type { ProgressBehavior, ProgressMode } from '../lib/taskData';

interface Props {
  taskId: string;
  nodeId: string;
  mode: ProgressMode;
  behavior: ProgressBehavior;
  successKey: string;
  anchorRef: RefObject<HTMLSpanElement | null>;
}

/** 持续挂载的进度猫：阶段决定动作，执行状态覆盖动作，查看节点不参与。 */
export function ProgressCat({ taskId, nodeId, mode, behavior, successKey, anchorRef }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const last = useRef<{ taskId: string; nodeId: string; x: number; y: number } | null>(null);
  const move = useRef<Animation | null>(null);
  const celebration = useRef<Animation | null>(null);
  const feedback = useRef<Animation[]>([]);
  const previous = useRef({ taskId, mode });
  const successes = useRef({ taskId, keys: successKey });

  useLayoutEffect(() => {
    // layout effect 早于父元素 ref 赋值时，DOM 父元素也已经存在。
    const el = ref.current, anchor = anchorRef.current, track = el?.parentElement;
    if (!el || !anchor || !track) return;
    const place = () => {
      const box = track.getBoundingClientRect(), slot = anchor.getBoundingClientRect();
      if (!slot.width || !box.width) return;
      const scaleX = box.width / track.offsetWidth || 1, scaleY = box.height / track.offsetHeight || 1;
      const x = (slot.left - box.left) / scaleX + (slot.width / scaleX - el.offsetWidth) / 2;
      const y = (slot.top - box.top) / scaleY + (slot.height / scaleY - el.offsetHeight) / 2;
      if (last.current?.taskId === taskId && last.current.nodeId === nodeId && Math.abs(last.current.x - x) < .25 && Math.abs(last.current.y - y) < .25) return;
      const before = el.getBoundingClientRect();
      const changed = last.current?.taskId === taskId && last.current.nodeId !== nodeId;
      const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      move.current?.cancel();
      el.dataset.moving = 'false';
      el.style.left = `${x}px`; el.style.top = `${y}px`;
      if (changed) el.dataset.direction = x < last.current!.x ? 'backward' : 'forward';
      else if (last.current?.taskId !== taskId) el.dataset.direction = 'forward';
      el.dataset.positioned = 'true';
      if (changed && !reduced && !document.hidden) {
        const dx = (before.left - box.left) / scaleX - x, dy = (before.top - box.top) / scaleY - y;
        el.dataset.moving = 'true';
        const animation = el.animate([
          { transform: `translate(${dx}px, ${dy}px) scale(1, 1)` },
          { transform: `translate(${dx}px, ${dy + 2}px) scale(1.08, .86)`, offset: .15 },
          { transform: `translate(${dx * .45}px, ${dy * .45 - 7}px) scale(.96, 1.04)`, offset: .55 },
          { transform: 'translate(0, 1px) scale(1.06, .9)', offset: .88 },
          { transform: 'translate(0, 0) scale(1, 1)' },
        ], { duration: 420, easing: 'ease-in-out' });
        animation.onfinish = () => { el.dataset.moving = 'false'; };
        move.current = animation;
      }
      last.current = { taskId, nodeId, x, y };
    };
    place();
    const observer = new ResizeObserver(place);
    observer.observe(track); observer.observe(anchor);
    return () => { observer.disconnect(); move.current?.cancel(); el.dataset.moving = 'false'; };
  }, [taskId, nodeId, anchorRef]);

  useEffect(() => {
    if (mode !== 'running' && mode !== 'done') {
      move.current?.cancel();
      if (ref.current) ref.current.dataset.moving = 'false';
    }
    if (previous.current.taskId === taskId && previous.current.mode !== 'done' && mode === 'done' && !document.hidden && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      celebration.current = ref.current?.querySelector('.progress-cat-hop')?.animate([
        { transform: 'translateY(0)' }, { transform: 'translateY(-7px)', offset: .4 },
        { transform: 'translateY(1px) scale(1.08, .9)', offset: .85 }, { transform: 'translateY(0)' },
      ], { duration: 450, easing: 'ease-out' }) ?? null;
    }
    previous.current = { taskId, mode };
    return () => celebration.current?.cancel();
  }, [taskId, mode]);

  useEffect(() => {
    const oldKeys = successes.current.keys;
    const sameTask = successes.current.taskId === taskId;
    successes.current = { taskId, keys: successKey };
    // 初次异步载入流程时仅建立历史基线，不重播过去的通过反馈。
    if (!oldKeys || !successKey) return;
    const old = new Set<string>(JSON.parse(oldKeys));
    const fresh = (JSON.parse(successKey) as string[]).some(key => !old.has(key));
    if (!sameTask || !fresh || !['running', 'done'].includes(mode) || document.hidden || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const head = ref.current?.querySelector('.progress-cat-head');
    const mark = ref.current?.querySelector('.progress-cat-confirm');
    if (head && mark) {
      feedback.current = [
        head.animate([{ transform: 'rotate(0)' }, { transform: 'rotate(5deg)', offset: .4 }, { transform: 'rotate(0)' }], { duration: 500 }),
        mark.animate([{ opacity: 0 }, { opacity: 1, offset: .2 }, { opacity: 1, offset: .75 }, { opacity: 0 }], { duration: 850 }),
      ];
    }
    return () => { feedback.current.forEach(a => a.cancel()); };
  }, [taskId, successKey, mode]);

  useEffect(() => {
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
    const stop = () => {
      move.current?.cancel(); celebration.current?.cancel(); feedback.current.forEach(a => a.cancel());
      if (ref.current) ref.current.dataset.moving = 'false';
    };
    const visibility = () => {
      if (ref.current) ref.current.dataset.hidden = String(document.hidden);
      if (document.hidden) stop();
    };
    visibility();
    document.addEventListener('visibilitychange', visibility);
    reduced.addEventListener('change', stop);
    return () => { document.removeEventListener('visibilitychange', visibility); reduced.removeEventListener('change', stop); stop(); };
  }, []);

  return <div ref={ref} className="progress-cat" data-testid="progress-cat" data-execution-node={nodeId} data-mode={mode} data-behavior={behavior} aria-hidden="true">
    <span className="progress-cat-hop"><span className="progress-cat-facing"><span className="progress-cat-breathe">
      <svg width="48" height="40" viewBox="0 0 48 40" fill="none">
        <g className="progress-cat-figure">
          <path className="progress-cat-tail" d="M33 29c6 4 11 1 11-4" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
          <g className="progress-cat-head">
            <path className="progress-cat-ear progress-cat-ear-left" d="m12 19 1-13c.1-1.5 1-1.8 2-.5l8 9" fill="currentColor" />
            <path className="progress-cat-ear progress-cat-ear-right" d="m23 15 8-9.5c1-1.2 2-.9 2.2.7L35 20" fill="currentColor" />
            <path d="M9 25c0-8 5-13 13-13s14 5 14 13c0 7-6 9-14 9S9 32 9 25Z" fill="currentColor" />
            <path className="progress-cat-inner-ear" d="m15 9 1 7 4-2-5-5Zm16 0-5 5 6 2-1-7Z" />
            <g className="progress-cat-eyes"><rect x="17" y="20" width="3" height="6" rx="1.5" /><rect x="26" y="20" width="3" height="6" rx="1.5" /></g>
          </g>
        </g>
        <g className="progress-cat-tools" data-tool={behavior} stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round">
          {behavior === 'type' && <g className="progress-cat-keyboard"><rect className="progress-cat-paper" x="8" y="32" width="31" height="6" rx="1.5" /><path d="M13 34h2m3 0h2m3 0h2m3 0h2m3 0h2m-18 2h13" /></g>}
          {behavior === 'think' && <g className="progress-cat-note"><path className="progress-cat-paper" d="M29 25h12v13H29z" /><path d="M32 29h6m-6 3h6m-6 3h3" /></g>}
          {behavior === 'draw' && <g><rect className="progress-cat-paper" x="7" y="29" width="31" height="9" rx="1" /><path className="progress-cat-grid" d="M13 30v7m7-7v7m7-7v7M8 33h29" /><path className="progress-cat-pencil" d="m29 25 7 8-3-1-5-6z" fill="currentColor" /></g>}
          {behavior === 'test' && <g className="progress-cat-panel"><rect className="progress-cat-paper" x="32" y="20" width="14" height="14" rx="2" /><circle className="progress-cat-test-dot" cx="35" cy="25" r="1" fill="currentColor" /><circle className="progress-cat-test-dot" cx="39" cy="25" r="1" fill="currentColor" /><circle className="progress-cat-test-dot" cx="43" cy="25" r="1" fill="currentColor" /><path d="M35 30h8" /></g>}
          {behavior === 'review' && <g><rect className="progress-cat-paper" x="30" y="25" width="13" height="13" rx="1" /><path d="M33 29h7m-7 3h7m-7 3h4" /><g className="progress-cat-loupe"><circle className="progress-cat-lens" cx="29" cy="22" r="5" /><path d="m32.5 25.5 5 5" strokeWidth="2" /></g></g>}
          {behavior === 'accept' && <g className="progress-cat-checklist"><rect className="progress-cat-paper" x="29" y="23" width="15" height="15" rx="1" /><path d="M32 27h2v2h-2zM32 32h2v2h-2zm5-4h4m-4 5h4" /></g>}
          {behavior === 'deliver' && <g className="progress-cat-package"><path className="progress-cat-paper" d="m9 30 7-5h15l7 5v8H9z" /><path d="M9 30h29m-15-5v13m-6-9 6 1 7-1" /></g>}
        </g>
        <g className="progress-cat-paws" fill="currentColor" stroke="rgb(var(--c-sunken))" strokeWidth=".7"><ellipse className="progress-cat-paw-left" cx="17" cy="31" rx="3" ry="2.2" /><ellipse className="progress-cat-paw-right" cx="28" cy="31" rx="3" ry="2.2" /></g>
        <path className="progress-cat-confirm" d="m36 9 3 3 6-7" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    </span></span></span>
  </div>;
}
