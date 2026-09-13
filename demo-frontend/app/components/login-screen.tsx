"use client";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useState, type FormEvent } from "react";
import { sessionClient, errorMessage } from "../lib/api-client";
import { useAuth } from "./auth-provider";
import { Icon } from "./icons";

export function LoginScreen({ register = false }: { register?: boolean }) {
  const auth = useAuth(), router = useRouter(), params = useSearchParams();
  const [username,setUsername] = useState(""), [password,setPassword] = useState(""), [confirmation,setConfirmation] = useState("");
  const [visible,setVisible] = useState(false), [busy,setBusy] = useState(false), [error,setError] = useState("");
  const nextParam = params.get("next");
  const next = ["/game","/assistant","/history","/account"].includes(nextParam ?? "") ? nextParam! : "/game";
  async function submit(event: FormEvent) {
    event.preventDefault(); setError("");
    if (register && password !== confirmation) { setError("两次输入的密码不一致。"); return; }
    if (register && new TextEncoder().encode(password).length > 72) { setError("密码过长，请减少字符数量（中文字符会占用更多长度）。"); return; }
    setBusy(true);
    try {
      if (register) await sessionClient.register(username.trim(),password);
      try { await sessionClient.login(username.trim(),password); }
      catch (failure) { if (register) throw new Error("账号已创建，但登录未完成。请切换到登录后重试。"); throw failure; }
      setPassword(""); setConfirmation(""); router.push(next);
    } catch (failure) { setError(errorMessage(failure)); } finally { setBusy(false); }
  }
  return <div className="auth-layout"><section className="auth-intro"><div className="eyebrow">YOUR NEXT MOVE</div><h1>一场好对局，<br/>从你的选择开始<span className="accent">。</span></h1><p>让不同的策略在同一个棋盘里交锋。<br/>观察每一步，找到下一次胜出的可能。</p><div className="auth-benefits"><div><Icon name="arena"/><span>四蛇同场，自由配置策略</span></div><div><Icon name="history"/><span>逐回合回放，看懂胜负转折</span></div><div><Icon name="spark"/><span>与 AI 讨论，完善策略思路</span></div></div><Link href="/game" className="text-link">先看看竞技场 <Icon name="arrow" size={16}/></Link></section>
    <section className="auth-card"><div className="eyebrow">PARSELTONGUE ACCOUNT</div><h2>{register ? "创建你的账号" : "欢迎回到竞技场"}</h2><p className="muted">{register ? "准备好，让策略上场。" : "登录后继续你的策略探索。"}</p>
      {auth.user ? <div className="signed-in"><span className="avatar large">{auth.user.username[0].toUpperCase()}</span><p>你已登录为 <strong>{auth.user.username}</strong></p><Link href={next} className="button primary full">进入工作空间<Icon name="arrow" size={17}/></Link><Link href="/account" className="text-link">管理当前账号</Link></div> : <>
      <div className="auth-tabs"><Link className={!register ? "selected" : ""} href={`/login?next=${encodeURIComponent(next)}`}>登录</Link><Link className={register ? "selected" : ""} href={`/register?next=${encodeURIComponent(next)}`}>注册</Link></div>
      <form onSubmit={submit} className="stack-form"><label>用户名<input required autoComplete="username" placeholder="输入你的用户名" minLength={register ? 3 : 1} maxLength={32} pattern={register ? "[A-Za-z0-9_]+" : undefined} value={username} onChange={e=>setUsername(e.target.value)} disabled={busy}/>{register && <small className="muted">3–32 位字母、数字或下划线</small>}</label>
      <label>密码<div className="password-field"><input required type={visible ? "text" : "password"} autoComplete={register ? "new-password" : "current-password"} placeholder={register ? "至少 8 个字符" : "输入你的密码"} minLength={register ? 8 : 1} maxLength={72} value={password} onChange={e=>setPassword(e.target.value)} disabled={busy}/><button type="button" onClick={()=>setVisible(v=>!v)} aria-label={visible ? "隐藏密码" : "显示密码"} aria-pressed={visible}><Icon name="eye" size={18}/></button></div></label>
      {register && <label>确认密码<input required type={visible ? "text" : "password"} autoComplete="new-password" placeholder="再次输入密码" value={confirmation} onChange={e=>setConfirmation(e.target.value)} disabled={busy}/></label>}
      {error && <div className="error-message" role="alert">{error}</div>}<button className="button primary full" disabled={busy}>{busy ? "正在处理…" : register ? "创建账号并登录" : "登录"}<Icon name="arrow" size={17}/></button></form><p className="auth-footnote"><Icon name="shield" size={15}/>密码经加密存储。请勿与他人共享账号。</p></>}
    </section></div>;
}
