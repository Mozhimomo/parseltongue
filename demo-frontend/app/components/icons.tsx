import type { CSSProperties, ReactNode } from "react";
export type IconName = "arena" | "spark" | "history" | "user" | "arrow" | "play" | "pause" | "step" | "back" | "download" | "refresh" | "close" | "check" | "shield" | "logout" | "copy" | "book" | "eye" | "shuffle";
const paths: Record<IconName, ReactNode> = {
  arena: <><rect x="3" y="3" width="18" height="18" rx="4"/><path d="M8 8h8v8H8zM3 12h5m8 0h5M12 3v5m0 8v5"/></>,
  spark: <path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3Z"/>,
  history: <><path d="M3 11a9 9 0 1 1 2.6 7M3 4v7h7"/><path d="M12 7v5l3 2"/></>,
  user: <><circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/></>,
  arrow: <path d="M5 12h14m-6-6 6 6-6 6"/>, play: <path d="m8 5 11 7-11 7Z"/>, pause: <path d="M8 5v14M16 5v14"/>,
  step: <><path d="m5 5 10 7-10 7Z"/><path d="M19 5v14"/></>, back: <><path d="m19 5-10 7 10 7Z"/><path d="M5 5v14"/></>,
  download: <path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/>,
  refresh: <path d="M20 8a8 8 0 0 0-14-3L3 8m0-6v6h6m-5 8a8 8 0 0 0 14 3l3-3m0 6v-6h-6"/>,
  close: <path d="m6 6 12 12M6 18 18 6"/>, check: <path d="m5 12 4 4L19 6"/>,
  shield: <><path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3Z"/><path d="m8 12 3 3 5-6"/></>,
  logout: <path d="M10 4H4v16h6m4-12 4 4-4 4m-5-4h12"/>,
  copy: <><rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V4H4v12h4"/></>,
  book: <path d="M12 6c-3-3-7-3-10-2v15c4-1 7-1 10 2 3-3 6-3 10-2V4c-3-1-7-1-10 2Zm0 0v15"/>,
  eye: <><path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/></>,
  shuffle: <path d="M3 6h3l12 12h3m-4-4 4 4-4 4M3 18h3L18 6h3m-4-4 4 4-4 4"/>,
};
export function Icon({ name, size = 20, style }: { name: IconName; size?: number; style?: CSSProperties }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={style}>{paths[name]}</svg>;
}
