import type { Metadata } from "next";
import "./globals.css";
import { AppShell } from "./components/app-shell";
import { AuthProvider } from "./components/auth-provider";
import { GameProvider } from "./components/game-provider";

export const metadata: Metadata = {
  title: "Parseltongue · 四蛇竞技",
  description: "配置四蛇对局、逐回合复盘，与 AI 推敲策略。你的私人策略竞技工作空间。",
  icons: { icon: "/favicon.svg" },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN">
      <body><AuthProvider><GameProvider><AppShell>{children}</AppShell></GameProvider></AuthProvider></body>
    </html>
  );
}
