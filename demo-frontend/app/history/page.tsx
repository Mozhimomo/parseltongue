"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useGame } from "../components/game-provider";
import { useAuth, LoginNotice } from "../components/auth-provider";
import { Icon } from "../components/icons";
import { COLORS, agentName, resultLabel } from "../lib/game";
export default function HistoryPage(){
  const auth=useAuth(),game=useGame(),router=useRouter();
  return <><div className="page-heading"><div><div className="eyebrow">MATCH ARCHIVE</div><h1>每场对局，都有迹可循<span className="accent">。</span></h1><p>重看关键一步，找到下一次调整的方向。</p></div><Link className="button primary" href="/game"><Icon name="play" size={17}/>开始新对局</Link></div><LoginNotice returnTo="/history"/>
    <div className="history-notice"><Icon name="history" size={18}/><p>这里显示当前浏览器标签页中创建的对局。回放临时保存，请及时导出；清空记录不会删除服务端对局。</p></div>
    {auth.user&&game.records.length>0?<section className="history-panel"><div className="section-heading"><h2>最近对局 <span className="muted small">/ {game.records.length}</span></h2><button className="button compact ghost" onClick={game.clearRecords}>清空本机记录</button></div><div className="history-list">{game.records.map(record=><article className="history-row" key={record.id}><div className="match-symbol"><Icon name="arena"/></div><div className="history-main"><strong>{record.status==="SUCCEEDED"?resultLabel(record.result):record.status==="FAILED"?"对局未完成":record.status==="QUEUED"?"等待开赛":"正在计算"}</strong><p>{new Date(record.createdAt).toLocaleString("zh-CN",{month:"2-digit",day:"2-digit",hour:"2-digit",minute:"2-digit"})} <span>· 种子 {record.seed} · 上限 {record.maxTicks} 回合</span></p><div className="history-agents">{record.agents.map((id,i)=><span key={i}><i style={{background:COLORS[i]}}/>{i+1} 号 {record.agentNames?.[i]??agentName(id)}</span>)}</div></div><button className="button compact" onClick={()=>{game.openTrial(record);router.push("/game");}}>{record.status==="SUCCEEDED"?"查看回放":"查看对局"}<Icon name="arrow" size={16}/></button></article>)}</div></section>:<section className="empty-state"><span className="empty-orbit"><Icon name="history" size={30}/></span><h2>你的第一场对局，尚待开场</h2><p>在竞技场完成一次试跑，记录就会出现在这里。</p><Link href="/game" className="button primary">前往竞技场<Icon name="arrow" size={17}/></Link></section>}
  </>;
}
