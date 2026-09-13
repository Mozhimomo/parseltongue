"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { Icon, type IconName } from "./icons";
import { useAuth } from "./auth-provider";
const navigation: { href: string; label: string; icon: IconName }[] = [
  { href: "/game", label: "竞技场", icon: "arena" }, { href: "/assistant", label: "我的蛇", icon: "spark" },
  { href: "/history", label: "对局记录", icon: "history" }, { href: "/account", label: "账号与安全", icon: "shield" },
];
export function AppShell({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  const auth = useAuth();
  return <div className="product-shell"><a className="skip-link" href="#main-content">跳到主要内容</a>
    <aside className="side-nav"><Link className="brand" href="/"><span className="brand-mark">P<span>_</span></span><div>Parseltongue<small>蛇语 · 策略竞技</small></div></Link>
      <div className="nav-caption">你的策略主场</div><nav aria-label="主导航">{navigation.map(item => {
        const active = path === item.href || (path === "/" && item.href === "/game");
        return <Link key={item.href} href={item.href} className={`nav-link ${active ? "active" : ""}`} aria-current={active ? "page" : undefined}><Icon name={item.icon}/>{item.label}{active && <span className="nav-dot"/>}</Link>;
      })}</nav><div className="nav-bottom"><Link href="/rules" className="nav-link"><Icon name="book"/>竞技规则</Link><div className="edition"><span className="live-dot"/>四蛇自由试跑<span>01</span></div></div>
    </aside><div className="main-column"><header className="product-header"><div className="breadcrumb">工作空间<span>/</span><strong>{navigation.find(i => i.href === path)?.label ?? (path === "/rules" ? "竞技规则" : path === "/login" || path === "/register" ? "欢迎回来" : "竞技场")}</strong></div><Link className="button compact account-link" href={auth.user ? "/account" : "/login"}><Icon name="user" size={17}/><span>{auth.user?.username ?? (auth.status === "checking" ? "连接中" : "登录账号")}</span><Icon name="arrow" size={15}/></Link></header>
      <main id="main-content" className="main-content">{children}</main><footer className="product-footer"><span>PARSELTONGUE</span><span>用策略交锋，让结果说话。</span></footer></div></div>;
}
