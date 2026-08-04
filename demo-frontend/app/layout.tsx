import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Parseltongue Auth Lab",
  description: "JWT 与 MySQL 会话认证流程演示。",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}
