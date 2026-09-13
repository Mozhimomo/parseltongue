"use client";
import Link from "next/link";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { useAuth, LoginNotice } from "../components/auth-provider";
import { Icon } from "../components/icons";
import { sessionClient, errorMessage } from "../lib/api-client";

type Snake = { id: string; name: string; description: string; status: "GENERATING" | "READY" | "FAILED"; error?: string; createdAt: string };
const EXAMPLES = ["稳健求生，优先走向空间大的地方。饥饿时寻找食物，尽量避开其他蛇头。", "积极争夺食物，但不进入死胡同。吃饱后保持距离，等待对手犯错。", "沿着棋盘边缘游走，遇到危险提前转向；快饿了才去寻找最近的食物。"];

function SnakeStudio({owner}:{owner:number}) {
  const [name,setName]=useState(""),[description,setDescription]=useState("");
  const [snakes,setSnakes]=useState<Snake[]>([]),[loading,setLoading]=useState(true),[submitting,setSubmitting]=useState(false);
  const [error,setError]=useState(""),[loadError,setLoadError]=useState(""),[reload,setReload]=useState(0);
  const submittingRef=useRef(false),revision=useRef(0);
  useEffect(()=>{
    let active=true; let timer:ReturnType<typeof setTimeout>; const controller=new AbortController();
    const poll=async()=>{
      const startedRevision=revision.current;
      try {
        const data=await sessionClient.authenticated<Snake[]>("/api/game/snakes",{signal:controller.signal});
        if(!active)return;
        if(startedRevision===revision.current)setSnakes(data);
        setLoading(false);setLoadError("");
        timer=setTimeout(poll,data.some(s=>s.status==="GENERATING")?1500:10000);
      } catch(failure) { if(active){setLoading(false);setLoadError(errorMessage(failure));timer=setTimeout(poll,5000);} }
    };
    void poll();return()=>{active=false;controller.abort();clearTimeout(timer);};
  },[reload]);
  const generating=snakes.some(s=>s.status==="GENERATING"),busy=submitting||generating;
  async function submit(event:FormEvent){
    event.preventDefault();if(submittingRef.current||generating||!name.trim()||!description.trim())return;
    submittingRef.current=true;setSubmitting(true);setError("");revision.current++;
    try {
      const created=await sessionClient.authenticated<Snake>("/api/game/snakes",{method:"POST",body:JSON.stringify({name:name.trim(),description:description.trim()})});
      if(sessionClient.getSnapshot().user?.id!==owner)return;
      revision.current++;setSnakes(current=>[created,...current.filter(s=>s.id!==created.id)]);
      setName("");setDescription("");setReload(n=>n+1);
    } catch(failure){if(sessionClient.getSnapshot().user?.id===owner){setError(errorMessage(failure));setReload(n=>n+1);}}
    finally{submittingRef.current=false;setSubmitting(false);}
  }
  return <div className="assistant-layout"><section className="surface assistant-editor"><div className="section-heading"><h2>创造一条蛇</h2><Icon name="spark"/></div>
    <form onSubmit={submit}><label className="snake-field">给它起个名字<input required maxLength={40} value={name} disabled={busy} onChange={e=>setName(e.target.value)} placeholder="例如：稳稳蛇" autoComplete="off"/></label>
      <label className="snake-field">你希望它怎样行动？<textarea required maxLength={8000} rows={9} value={description} disabled={busy} onChange={e=>setDescription(e.target.value)} placeholder="说说它的性格、觅食方式，以及遇到危险时如何选择…"/></label>
      <div className="input-meta"><span>用自己的话描述就好。</span><span>{description.length} / 8000</span></div>
      {!description&&<div className="prompt-examples"><span className="muted small">还没想好？从一个想法开始</span>{EXAMPLES.map((text,i)=><button key={text} type="button" disabled={busy} onClick={()=>setDescription(text)}><span>0{i+1}</span>{text}<Icon name="arrow" size={16}/></button>)}</div>}
      {error&&<div className="error-message" role="alert">{error}</div>}
      <button className="button primary full" disabled={busy||loading||!!loadError||!name.trim()||!description.trim()}><Icon name="spark" size={18}/>{submitting?"正在提交…":generating?"你的蛇正在诞生…":"创建这条蛇"}</button>
      <p className="model-note">创建需要一点时间。可以离开页面，稍后回来查看。</p>
    </form></section>
    <section className="surface assistant-output"><div className="section-heading"><h2>我的蛇</h2><span className="badge">{snakes.filter(s=>s.status==="READY").length} 条已就绪</span></div>
      {loadError&&<div className="error-message" role="alert">{loadError}<button className="button compact" onClick={()=>setReload(n=>n+1)}>重新连接</button></div>}
      {loading?<div className="assistant-empty" role="status"><p>正在寻找你的蛇…</p></div>:!snakes.length?<div className="assistant-empty"><span className="empty-orbit"><Icon name="spark" size={30}/></span><h3>第一条蛇，从你的想法开始</h3><p>为它取名，告诉它如何行动。准备好后，就能去竞技场试一试。</p></div>:<div className="created-snakes">{snakes.map(s=><article className="created-snake" key={s.id} aria-label={s.name}><div className="section-heading"><h3>{s.name}</h3><span className="badge" role="status">{s.status==="READY"?"已就绪":s.status==="GENERATING"?"正在诞生":"未完成"}</span></div><p className="snake-description">{s.description}</p>{s.status==="GENERATING"?<p className="muted small">正在为它准备独特的行动方式，请稍等。</p>:s.status==="READY"?<Link className="button compact" href="/game">去竞技场<Icon name="arrow" size={16}/></Link>:<><p className="error-message">{s.error||"这次未能创建成功，请稍后重试。"}</p><button className="button compact" disabled={busy} onClick={()=>{setName(s.name);setDescription(s.description);setError("");}}>用这个想法再试一次</button></>}</article>)}</div>}
    </section></div>;
}
export default function AssistantPage(){
  const {user}=useAuth();
  return <><div className="page-heading"><div><div className="eyebrow">YOUR SNAKES</div><h1>一句想法，一条独特的蛇<span className="accent">。</span></h1><p>取一个名字，描述它的策略，等它准备好上场。</p></div></div><LoginNotice returnTo="/assistant"/>{user?<SnakeStudio key={user.id} owner={user.id}/>:<section className="surface assistant-empty"><Icon name="spark" size={32}/><h2>创造属于你的蛇</h2><p>登录后，就可以把想法变成竞技场上的新伙伴。</p><Link className="button primary" href="/login?next=%2Fassistant">登录并创建</Link></section>}</>;
}
