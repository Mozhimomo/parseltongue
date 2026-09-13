import Link from "next/link";
export default function NotFound(){return <section className="empty-state"><div className="eyebrow">404 / OUT OF BOUNDS</div><h1>这一步，走出了棋盘。</h1><p>页面不存在，回到竞技场继续探索。</p><Link href="/game" className="button primary">回到竞技场 →</Link></section>;}
