import Link from "next/link";
import { Icon } from "../components/icons";
import { AGENTS } from "../lib/game";
export default function RulesPage(){return <><div className="page-heading"><div><div className="eyebrow">THE RULEBOOK</div><h1>先读懂棋盘，再决定下一步<span className="accent">。</span></h1><p>四条蛇，同一套规则。最后存活者获胜。</p></div><Link className="button primary" href="/game">回到竞技场<Icon name="arrow" size={17}/></Link></div>
  <div className="rules-grid">{[
    ["01","同回合，统一行动","四条蛇从同一局面选择方向，再统一移动和结算。地图为 20 × 20，不穿墙；每条蛇初始长度为 3。"],
    ["02","食物，也是生存时间","场上维持 5 个食物。饥饿值初始和上限都是 100，每回合减少 1；吃到食物恢复 40 并增长 1 格。先移动、进食，再结算饥饿。"],
    ["03","碰撞，即刻淘汰","撞墙、撞到自己或其他蛇的身体都会死亡。两蛇头进入同一格、交换位置时，相关蛇均被淘汰。未进食的蛇会释放尾格。"],
    ["04","生存决定胜负","最后一条存活蛇获胜，其他蛇记败。最后存活的蛇同回合因正常碰撞或饥饿全灭，它们记平局。达到回合上限仍多蛇存活则无结果，不按长度判胜。"],
  ].map(([n,title,body])=><section className="surface rule-card" key={n}><span className="rule-number">{n}</span><h2>{title}</h2><p>{body}</p></section>)}</div>
  <section className="surface strategy-library"><div className="section-heading"><h2>认识四种内置策略</h2><span className="badge">可自由组合</span></div><div className="rules-grid">{AGENTS.map(a=><article key={a.id}><h3>{a.name}</h3><p>{a.description}</p></article>)}</div></section>
  <div className="notice"><Icon name="spark"/><div><strong>用自己的想法创造一条蛇</strong><p>在“我的蛇”为它起名，描述它的行动方式。创建完成后，可以把它与内置策略一起放进竞技场试跑。排位和排行榜尚未开放。</p></div></div>
  </>;}
