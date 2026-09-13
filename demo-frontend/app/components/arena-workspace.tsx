"use client";
import Link from "next/link";
import { useEffect, useState, type FormEvent, type CSSProperties } from "react";
import { Board } from "./board";
import { Icon } from "./icons";
import { useAuth } from "./auth-provider";
import { useGame } from "./game-provider";
import { AGENTS, COLORS, REASONS, agentName, resultLabel, type Agent } from "../lib/game";
import { sessionClient, errorMessage } from "../lib/api-client";
import { downloadText } from "../lib/download";

function ReplayPlayer(){
  const {replay,trial,loading,error,retry}=useGame();
  const [cursor,setCursor]=useState(0),[playing,setPlaying]=useState(false),[speed,setSpeed]=useState(1);
  const last=(replay?.frames.length??1)-1, frame=replay?.frames[Math.min(cursor,last)];
  const isPlaying=playing&&cursor<last;
  useEffect(()=>{if(!isPlaying||!replay)return;const timer=setTimeout(()=>setCursor(n=>Math.min(last,n+1)),160/speed);return()=>clearTimeout(timer);},[isPlaying,replay,cursor,last,speed]);
  const toggle=()=>{if(!replay)return;if(cursor>=last){setCursor(0);setPlaying(true);}else setPlaying(v=>!v);};
  const step=(n:number)=>{setPlaying(false);setCursor(c=>Math.max(0,Math.min(last,c+n)));};
  const events=replay?.frames.slice(0,cursor+1).flatMap(f=>f.events.filter(e=>e.type==="DIED").map(e=>({...e,tick:f.state.tick})))??[];
  return <div><section className="arena-panel" aria-label="对战回放" onKeyDown={e=>{
    const tag=(e.target as HTMLElement).tagName;if(["INPUT","SELECT","BUTTON","TEXTAREA","A"].includes(tag))return;
    if(e.code==="Space"){e.preventDefault();toggle();}if(e.key==="ArrowRight"){e.preventDefault();step(1);}if(e.key==="ArrowLeft"){e.preventDefault();step(-1);}
  }} tabIndex={replay?0:undefined}>
    <div className="panel-top"><span><span className={`live-dot ${!replay?"muted-dot":""}`}/>{loading ? trial?.status==="QUEUED"?"正在排队":"正在计算对局" : replay ? "对战回放" : "等待开赛"}</span><span className="mono muted">20 × 20 <span className="separator">/</span> ROUND {String(frame?.state.tick??0).padStart(4,"0")}</span></div>
    <div className="board-stage"><Board replay={replay} frame={frame}/>{!replay&&<div className="board-empty"><span className={`empty-orbit ${loading?"breathing":""}`}><Icon name="arena" size={33}/></span><h2>{error?"对局暂时无法显示":loading?"四条蛇，正在做出选择":"棋盘已就位"}</h2><p>{error?"你可以重新获取对局状态。":loading?"完成计算后，即可逐回合查看比赛。":"四个席位，四种选择。配置策略，开启第一局。"}</p>{error&&trial&&<button className="button compact" onClick={retry}>重新获取</button>}<div className="four-colors">{COLORS.map((color,i)=><span key={color} style={{background:color}}>{i+1}</span>)}</div></div>}</div>
    <div className="replay-controls"><button className="icon-button" disabled={!replay} aria-label="回到开始" onClick={()=>{setPlaying(false);setCursor(0);}}><Icon name="back"/></button><button className="button play-button" disabled={!replay} onClick={toggle}><Icon name={isPlaying?"pause":"play"} size={17}/>{isPlaying?"暂停":cursor===last&&replay?"重播":"播放"}</button><button className="icon-button" disabled={!replay||cursor===last} aria-label="下一回合" onClick={()=>step(1)}><Icon name="step"/></button><input aria-label="回放进度" aria-valuetext={`第 ${frame?.state.tick??0} 回合，共 ${replay?.result.ticks??0} 回合`} type="range" disabled={!replay} min={0} max={last} value={Math.min(cursor,last)} onChange={e=>{setPlaying(false);setCursor(Number(e.target.value));}}/><select aria-label="播放速度" value={speed} onChange={e=>setSpeed(Number(e.target.value))}>{[.5,1,2,4,8].map(s=><option key={s} value={s}>{s}×</option>)}</select></div>
    <div className="board-legend"><span><i className="food-dot"/>食物 +40 饥饿值</span><span>{replay?`${frame?.state.tick??0} / ${replay.result.ticks} 回合` : "每回合 −1 · 最后存活者获胜"}</span></div>
  </section>
  {replay&&<><div className="replay-actions"><span className="muted small">种子 {replay.seed} · {frame?.state.snakes.filter(s=>s.alive).length} 条存活</span><button className="button compact ghost" onClick={()=>{setPlaying(false);setCursor(last);}}>查看结局</button><button className="button compact ghost" onClick={()=>downloadText(JSON.stringify(replay),`parseltongue-${trial?.id}.json`,"application/json")}><Icon name="download" size={16}/>导出回放</button></div>
    <div className="snake-stats">{frame?.state.snakes.map((snake,i)=><div className={`snake-stat ${snake.alive?"":"eliminated"}`} key={snake.id} style={{"--seat-color":COLORS[i]} as CSSProperties}><div><span className="colored-dot"/><strong>{i+1} 号 · {replay.agent_names?.[snake.id] ?? agentName(replay.agents[snake.id])}</strong></div><p>{snake.alive?`长度 ${snake.body.length}`:REASONS[snake.death_reason??""]??"已淘汰"}</p><progress aria-label={`${i+1}号蛇饥饿值`} max={snake.hunger_max} value={snake.alive?snake.hunger:0}/><small>{snake.alive?`饥饿 ${snake.hunger} / ${snake.hunger_max}`:`第 ${snake.death_tick} 回合淘汰`}</small></div>)}</div>
    <section className="match-result" aria-live="polite"><div className="section-heading"><h2>{cursor===last?resultLabel(replay.result):"关键回合"}</h2><span className="badge">{cursor===last?"对局结束":"回放中"}</span></div>{events.length?<ol className="event-list">{events.map(e=><li key={`${e.tick}-${e.snake_id}`}><span className="mono">{String(e.tick).padStart(4,"0")}</span><span>{e.snake_id.replace("s","")} 号蛇 · {REASONS[e.reason??""]??"淘汰"}</span></li>)}</ol>:<p className="muted small">当前回合尚无淘汰记录。</p>}</section></>}
  </div>;
}

export function ArenaWorkspace(){
  const auth=useAuth(),game=useGame();
  const userId=auth.user?.id;
  const [agents,setAgents]=useState<Agent[]>(AGENTS),[agentError,setAgentError]=useState(""),[readyOwner,setReadyOwner]=useState<number|null>(null),[reload,setReload]=useState(0);
  useEffect(()=>{if(!userId)return;let active=true;void sessionClient.authenticated<Agent[]>("/api/game/agents").then(data=>{if(active){setAgents(data);setReadyOwner(userId);setAgentError("");}}).catch(e=>{if(active)setAgentError(errorMessage(e));});return()=>{active=false;};},[userId,reload]);
  const busy=game.submitting||game.loading||(game.trial?.status==="RUNNING"||game.trial?.status==="QUEUED")&&!game.error;
  const submit=(event:FormEvent)=>{event.preventDefault();void game.start(Object.fromEntries(agents.map(a=>[a.id,a.name])));};
  return <><div className="page-heading"><div><div className="eyebrow">THE ARENA <span>01 / 四蛇竞技</span></div><h1>每一步，都是一次博弈<span className="accent">。</span></h1><p>挑选四种策略，在同一张棋盘上见分晓。</p></div><span className="badge"><span className="live-dot"/>自由试跑 · 不计排位</span></div>
    {(agentError||game.error)&&<div className="error-message inline-error" role="alert">{agentError||game.error}<button className="button compact" onClick={()=>agentError?setReload(n=>n+1):game.retry()}>重试</button></div>}
    <div className="arena-layout"><ReplayPlayer key={game.trial?.id??"empty"}/><aside className="match-settings"><form onSubmit={submit}><div className="section-heading"><h2>布置你的对局</h2><span className="mono muted">4 / 4</span></div><p className="muted small">每个席位独立选择，可重复使用策略。</p>
      <div className="seat-list">{game.selected.map((choice,i)=><div className="seat" key={i} style={{"--seat-color":COLORS[i]} as CSSProperties}><span className="seat-number">0{i+1}</span><label><span className="sr-only">第 {i+1} 个席位策略</span><select value={choice} disabled={busy} onChange={e=>game.setSelected(game.selected.map((v,n)=>n===i?e.target.value:v))}>{(readyOwner===userId?agents:AGENTS).map(a=><option value={a.id} key={a.id}>{a.name}</option>)}</select><small>{(readyOwner===userId?agents:AGENTS).find(a=>a.id===choice)?.description}</small></label></div>)}</div>
      <div className="settings-grid"><label>地图种子<div className="seed-field"><input required type="number" min={0} max={2147483647} step={1} value={game.seed} onChange={e=>game.setSeed(e.target.value)} disabled={busy}/><button type="button" aria-label="随机地图种子" disabled={busy} onClick={()=>game.setSeed(String(crypto.getRandomValues(new Uint32Array(1))[0]%2147483648))}><Icon name="shuffle" size={16}/></button></div></label><label>回合上限<input required type="number" min={1} max={2000} step={1} value={game.maxTicks} onChange={e=>game.setMaxTicks(e.target.value)} disabled={busy}/></label></div>
      {auth.user?<button className="button primary full" disabled={busy||readyOwner!==auth.user.id}><Icon name="play" size={18}/>{busy?"对局计算中…":readyOwner!==auth.user.id?"正在连接竞技场…":"开始四蛇对战"}<Icon name="arrow" size={17}/></button>:<Link href="/login?next=%2Fgame" className="button primary full"><Icon name="play" size={18}/>登录并开始对局<Icon name="arrow" size={17}/></Link>}<p className="settings-note"><Icon name="shield" size={15}/>私有试跑，仅你可见。</p></form>
      <div className="strategy-tip"><Icon name="spark"/><div><strong>让你的蛇上场</strong><p>描述你的想法，创建一条属于你的蛇。</p><Link href="/assistant">创建我的蛇 <span>↗</span></Link></div></div>
      <details className="match-details"><summary>关于这场试跑</summary><p>相同种子、策略与规则可复现结果。达到回合上限仍有多蛇存活，本局记为无结果。</p><p>回放临时保留约 30 分钟，可能因容量回收或服务重启提前失效。重要对局请及时导出。</p></details>
    </aside></div><div className="below-arena"><span className="mono">HOW TO PLAY</span><p><strong>生存是唯一胜负标准。</strong>撞墙、碰到蛇身或饥饿耗尽都会被淘汰。</p><Link href="/rules">阅读完整规则 <Icon name="arrow" size={16}/></Link></div></>;
}
