"use client";
import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth, LoginNotice } from "../components/auth-provider";
import { sessionClient, errorMessage } from "../lib/api-client";
import { Icon } from "../components/icons";
export default function AccountPage(){
  const auth=useAuth(),router=useRouter(),dialog=useRef<HTMLDialogElement>(null);
  const [pending,setPending]=useState(""),[message,setMessage]=useState(""),[error,setError]=useState("");
  async function perform(action:"verify"|"refresh"|"logout"|"logout-all"){
    setPending(action);setMessage("");setError("");
    try{if(action==="verify"){await sessionClient.verify();setMessage("账号信息已更新，当前登录状态有效。");}else if(action==="refresh"){await sessionClient.refresh();setMessage("登录状态已更新，可以继续使用。");}else{await sessionClient.logout(action==="logout-all");dialog.current?.close();router.push("/login");}}
    catch(failure){setError(errorMessage(failure));dialog.current?.close();}finally{setPending("");}
  }
  return <><div className="page-heading"><div><div className="eyebrow">YOUR ACCOUNT</div><h1>账号与安全<span className="accent">。</span></h1><p>查看个人信息，管理你的登录状态。</p></div></div><LoginNotice returnTo="/account"/>{auth.user&&<div className="account-layout"><section className="surface profile-card"><span className="avatar large">{auth.user.username[0].toUpperCase()}</span><h2>{auth.user.username}</h2><span className="badge">{auth.user.role==="ADMIN"?"管理员":"玩家"}</span><dl className="profile-fields"><div><dt>账号 ID</dt><dd className="mono">{auth.user.id}</dd></div><div><dt>登录状态</dt><dd><span className="live-dot"/> 已登录</dd></div></dl><button className="button full" disabled={!!pending} onClick={()=>void perform("verify")}><Icon name="refresh" size={17}/>{pending==="verify"?"正在更新…":"更新账号信息"}</button></section>
    <section className="surface security-panel"><div className="section-heading"><h2>登录与设备安全</h2><Icon name="shield"/></div><div className="security-row"><div><h3>保持当前登录</h3><p>系统会自动续期，也可以手动更新登录状态。</p></div><button className="button compact" disabled={!!pending} onClick={()=>void perform("refresh")}>{pending==="refresh"?"更新中…":"更新登录状态"}</button></div><div className="security-row"><div><h3>退出当前设备</h3><p>结束当前会话，其他设备不受影响。</p></div><button className="button compact" disabled={!!pending} onClick={()=>void perform("logout")}><Icon name="logout" size={16}/>退出登录</button></div><div className="security-row"><div><h3>退出全部设备</h3><p>如果曾在共享设备上登录，可使所有会话失效。</p></div><button className="button compact danger" disabled={!!pending} onClick={()=>dialog.current?.showModal()}>退出全部设备</button></div>
    {message&&<div className="success-message" role="status"><Icon name="check" size={18}/>{message}</div>}{error&&<div className="error-message" role="alert">{error}</div>}</section></div>}
    <dialog ref={dialog} className="confirm-dialog" aria-labelledby="logout-title"><div className="dialog-icon"><Icon name="shield" size={28}/></div><h2 id="logout-title">退出所有已登录设备？</h2><p>包括当前设备在内的所有登录会话都会结束。再次使用时需要重新登录。</p><div className="dialog-actions"><button className="button" disabled={!!pending} onClick={()=>dialog.current?.close()}>保留登录</button><button className="button danger" disabled={!!pending} onClick={()=>void perform("logout-all")}>{pending?"正在退出…":"确认退出全部设备"}</button></div></dialog>
  </>;
}
