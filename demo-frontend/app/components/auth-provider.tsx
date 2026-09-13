"use client";
import { createContext, useContext, useEffect, useSyncExternalStore } from "react";
import Link from "next/link";
import { initialSession, sessionClient, type Session } from "../lib/api-client";
import { Icon } from "./icons";
const AuthContext = createContext<Session>(initialSession);
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const session = useSyncExternalStore(sessionClient.subscribe, sessionClient.getSnapshot, () => initialSession);
  useEffect(() => { void sessionClient.initialize(); }, []);
  return <AuthContext.Provider value={session}>{children}</AuthContext.Provider>;
}
export const useAuth = () => useContext(AuthContext);
export function LoginNotice({ returnTo }: { returnTo: string }) {
  const auth = useAuth();
  if (auth.status === "authenticated") return null;
  return <div className="notice"><Icon name="shield"/><div><strong>{auth.status === "checking" ? "正在恢复登录…" : auth.status === "offline" ? "暂时无法连接服务" : "登录，进入你的私人工作空间"}</strong><p>{auth.status === "offline" ? "可以先浏览界面，连接恢复后重新登录。" : "你的策略和试跑回放，仅自己可见。"}</p></div>{auth.status !== "checking" && <Link href={`/login?next=${encodeURIComponent(returnTo)}`} className="button compact">登录<Icon name="arrow" size={16}/></Link>}</div>;
}
