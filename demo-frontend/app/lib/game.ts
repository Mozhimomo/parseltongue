export type Agent = { id: string; name: string; description: string };
export type Snake = { id: string; body: number[][]; direction: string; hunger: number; hunger_max: number; alive: boolean; death_reason: string | null; death_tick: number | null };
export type Frame = { state: { tick: number; food: number[][]; snakes: Snake[] }; events: { snake_id: string; type: string; reason?: string }[] };
export type MatchResult = { reason: string; winner_id: string | null; ticks: number; outcomes: Record<string, string> };
export type Replay = { seed: number; rules: { width: number; height: number; hunger_max: number; max_ticks: number }; agents: Record<string, string>; agent_names?: Record<string, string>; frames: Frame[]; result: MatchResult };
export type Trial = { id: string; status: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED"; error?: string; result?: MatchResult; createdAt: string };
export const COLORS = ["#b7f86b", "#8bafff", "#f6af73", "#cc9bfa"];
export const AGENTS: Agent[] = [
  { id: "straight", name: "直行蛇", description: "保持方向，观察碰撞与淘汰。" }, { id: "cautious", name: "避碰蛇", description: "安全优先，遇到障碍及时转向。" },
  { id: "greedy", name: "贪食蛇", description: "追寻最近食物，同时避开蛇身。" }, { id: "forager", name: "寻路蛇", description: "搜索食物路径，兼顾空间与对手。" },
];
export const REASONS: Record<string, string> = { WALL: "撞墙", BODY: "碰撞蛇身", HEAD_ON: "蛇头相撞", HEAD_SWAP: "蛇头交换位置", STARVATION: "饥饿耗尽", REVERSE: "非法反向", INVALID_ACTION: "动作无效", MISSING_ACTION: "未返回动作", AI_ERROR: "策略执行错误" };
export const agentName = (id: string) => AGENTS.find(a => a.id === id)?.name ?? (id.startsWith("snake:") ? "自建蛇" : id);
export function resultLabel(result?: MatchResult) {
  if (!result) return "等待结果";
  if (result.winner_id) return `${result.winner_id.replace("s", "")} 号蛇获胜`;
  return result.reason === "NO_CONTEST" ? "达到上限 · 无结果" : "最终存活蛇同时淘汰 · 平局";
}
