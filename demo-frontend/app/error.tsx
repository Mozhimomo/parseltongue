"use client";
export default function ErrorPage({reset}:{reset:()=>void}){return <section className="empty-state"><h1>页面暂时遇到问题</h1><p>请重试。已经提交的对局仍会在后台继续。</p><button className="button primary" onClick={reset}>重新加载</button></section>;}
