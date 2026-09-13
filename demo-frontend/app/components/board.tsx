"use client";
import { useEffect, useRef } from "react";
import { COLORS, type Frame, type Replay } from "../lib/game";
export function Board({ replay, frame }: { replay?: Replay | null; frame?: Frame }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const ctx = canvas.current?.getContext("2d"); if (!ctx) return;
    const width = replay?.rules.width ?? 20, height = replay?.rules.height ?? 20, cw = 800 / width, ch = 800 / height;
    ctx.fillStyle = "#121918"; ctx.fillRect(0, 0, 800, 800); ctx.strokeStyle = "#26302d"; ctx.lineWidth = 1;
    for (let i = 0; i <= width; i++) { ctx.beginPath(); ctx.moveTo(i * cw, 0); ctx.lineTo(i * cw, 800); ctx.stroke(); }
    for (let i = 0; i <= height; i++) { ctx.beginPath(); ctx.moveTo(0, i * ch); ctx.lineTo(800, i * ch); ctx.stroke(); }
    for (const [x, y] of frame?.state.food ?? []) { ctx.fillStyle = "#fa726f"; ctx.beginPath(); ctx.arc((x + .5) * cw, (y + .5) * ch, cw * .17, 0, Math.PI * 2); ctx.fill(); }
    frame?.state.snakes.forEach((snake, index) => { if (!snake.alive) return; snake.body.forEach(([x, y], part) => {
      ctx.globalAlpha = part === 0 ? 1 : .66; ctx.fillStyle = COLORS[index]; ctx.beginPath(); ctx.roundRect(x * cw + 2, y * ch + 2, cw - 4, ch - 4, 5); ctx.fill();
      if (part === 0) { ctx.fillStyle = "#142015"; ctx.globalAlpha = 1; ctx.font = `700 ${cw * .48}px sans-serif`; ctx.textAlign = "center"; ctx.textBaseline = "middle"; ctx.fillText(String(index + 1), (x + .5) * cw, (y + .53) * ch); }
    }); ctx.globalAlpha = 1; });
  }, [replay, frame]);
  return <canvas ref={canvas} width={800} height={800} className="game-board" role="img" aria-label={frame ? `棋盘：第 ${frame.state.tick} 回合，${frame.state.snakes.filter(s => s.alive).length} 条蛇存活` : "20 乘 20 棋盘，等待开赛"}/>;
}
