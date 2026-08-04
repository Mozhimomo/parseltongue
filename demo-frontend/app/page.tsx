"use client";

import { FormEvent, useEffect, useState } from "react";

type UserRole = "USER" | "ADMIN";

type User = {
  id: number;
  username: string;
  email: string | null;
  role: UserRole;
};

type LoginData = {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  user: User;
};

type ApiEnvelope<T> = {
  code: number;
  message: string;
  data: T;
};

type Activity = {
  time: string;
  tone: "success" | "error" | "neutral";
  message: string;
};

async function callApi<T>(
  path: string,
  init: RequestInit = {},
  accessToken?: string,
): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body) headers.set("Content-Type", "application/json");
  if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);

  const response = await fetch(path, {
    ...init,
    headers,
    credentials: "include",
  });
  const payload = (await response.json().catch(() => null)) as ApiEnvelope<T> | null;
  if (!response.ok) {
    throw new Error(payload?.message || `请求失败（${response.status}）`);
  }
  return payload?.data;
}

function nowLabel() {
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  }).format(new Date());
}

export default function Home() {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [identifier, setIdentifier] = useState("demo_user");
  const [loginPassword, setLoginPassword] = useState("password123");
  const [username, setUsername] = useState("demo_user");
  const [email, setEmail] = useState("demo@example.com");
  const [registerPassword, setRegisterPassword] = useState("password123");
  const [accessToken, setAccessToken] = useState("");
  const [user, setUser] = useState<User | null>(null);
  const [pending, setPending] = useState("");
  const [backendState, setBackendState] = useState<"checking" | "online" | "offline">(
    "checking",
  );
  const [activities, setActivities] = useState<Activity[]>([
    { time: "--:--:--", tone: "neutral", message: "等待认证操作" },
  ]);

  const addActivity = (
    message: string,
    tone: Activity["tone"] = "neutral",
  ) => {
    setActivities((current) =>
      [{ time: nowLabel(), tone, message }, ...current].slice(0, 4),
    );
  };

  const acceptLogin = (data: LoginData, message: string) => {
    setAccessToken(data.accessToken);
    setUser(data.user);
    setBackendState("online");
    addActivity(message, "success");
  };

  useEffect(() => {
    let active = true;
    callApi<LoginData>("/api/auth/refresh", { method: "POST" })
      .then((data) => {
        if (!active) return;
        setAccessToken(data.accessToken);
        setUser(data.user);
        setBackendState("online");
        setActivities([
          { time: nowLabel(), tone: "success", message: "已从刷新会话恢复登录" },
        ]);
      })
      .catch((error: Error) => {
        if (!active) return;
        setBackendState(error instanceof TypeError ? "offline" : "online");
      });
    return () => {
      active = false;
    };
  }, []);

  const handleLogin = async (event: FormEvent) => {
    event.preventDefault();
    setPending("login");
    try {
      const data = await callApi<LoginData>("/api/auth/login", {
        method: "POST",
        body: JSON.stringify({ identifier, password: loginPassword }),
      });
      acceptLogin(data, "登录成功，会话已写入 MySQL");
    } catch (error) {
      setBackendState(error instanceof TypeError ? "offline" : "online");
      addActivity((error as Error).message, "error");
    } finally {
      setPending("");
    }
  };

  const handleRegister = async (event: FormEvent) => {
    event.preventDefault();
    setPending("register");
    try {
      await callApi<User>("/api/auth/register", {
        method: "POST",
        body: JSON.stringify({
          username,
          email: email || null,
          password: registerPassword,
        }),
      });
      setIdentifier(username);
      setLoginPassword(registerPassword);
      setMode("login");
      setBackendState("online");
      addActivity("注册成功，现在可以使用新账号登录", "success");
    } catch (error) {
      setBackendState(error instanceof TypeError ? "offline" : "online");
      addActivity((error as Error).message, "error");
    } finally {
      setPending("");
    }
  };

  const verifyIdentity = async () => {
    setPending("verify");
    try {
      const current = await callApi<User>(
        "/api/users/me",
        { method: "GET" },
        accessToken,
      );
      setUser(current);
      addActivity("过滤器鉴权通过，用户信息有效", "success");
    } catch (error) {
      addActivity((error as Error).message, "error");
    } finally {
      setPending("");
    }
  };

  const refreshAccessToken = async () => {
    setPending("refresh");
    try {
      const data = await callApi<LoginData>("/api/auth/refresh", {
        method: "POST",
      });
      acceptLogin(data, "Access Token 已刷新，旧 Refresh Token 已轮换");
    } catch (error) {
      addActivity((error as Error).message, "error");
    } finally {
      setPending("");
    }
  };

  const logout = async (allDevices: boolean) => {
    setPending(allDevices ? "logout-all" : "logout");
    try {
      await callApi<void>(
        allDevices ? "/api/auth/logout-all" : "/api/auth/logout",
        { method: "POST" },
        accessToken,
      );
      setAccessToken("");
      setUser(null);
      addActivity(
        allDevices ? "所有设备会话已撤销" : "当前设备会话已撤销",
        "success",
      );
    } catch (error) {
      addActivity((error as Error).message, "error");
    } finally {
      setPending("");
    }
  };

  return (
    <main className="app-shell">
      <div className="noise" aria-hidden="true" />
      <header className="topbar">
        <a className="brand" href="#top" aria-label="Parseltongue Auth Lab 首页">
          <span className="brand-mark">P</span>
          <span>
            <strong>PARSELTONGUE</strong>
            <small>AUTH LAB / 01</small>
          </span>
        </a>
        <div className={`api-state ${backendState}`}>
          <span className="pulse" />
          {backendState === "checking"
            ? "正在检测后端"
            : backendState === "online"
              ? "API 已连接"
              : "API 未连接"}
        </div>
      </header>

      <section className="workspace" id="top">
        <div className="story-panel">
          <div className="eyebrow">
            <span>JWT</span>
            <span>BCrypt</span>
            <span>MyBatis</span>
            <span>MySQL</span>
          </div>
          <h1>
            身份可信，
            <br />
            会话<span>可撤销。</span>
          </h1>
          <p className="intro">
            一个可操作的认证流程 Demo。短期 JWT 负责表达身份，数据库会话负责让退出、禁用与角色变更立即生效。
          </p>

          <div className="flow-map" aria-label="登录鉴权流程">
            <div className="flow-node active-node">
              <span className="node-number">01</span>
              <div>
                <strong>登录凭据</strong>
                <small>BCrypt 校验密码</small>
              </div>
              <span className="node-state">HASH</span>
            </div>
            <div className="flow-line"><span /></div>
            <div className="flow-node">
              <span className="node-number">02</span>
              <div>
                <strong>签发 JWT</strong>
                <small>包含 userId 与 sessionId</small>
              </div>
              <span className="node-state">30 MIN</span>
            </div>
            <div className="flow-line"><span /></div>
            <div className="flow-node">
              <span className="node-number">03</span>
              <div>
                <strong>会话核验</strong>
                <small>每次请求查询 MySQL</small>
              </div>
              <span className="node-state">LIVE</span>
            </div>
          </div>

          <div className="system-note">
            <span>设计说明</span>
            <p>
              Refresh Token 只以 SHA-256 哈希存入数据库；原始值保存在 HttpOnly Cookie，前端脚本无法读取。
            </p>
          </div>
        </div>

        <div className="console-panel">
          {!user ? (
            <section className="auth-card" aria-labelledby="auth-title">
              <div className="card-heading">
                <div>
                  <span className="section-index">AUTH / 01</span>
                  <h2 id="auth-title">{mode === "login" ? "欢迎回来" : "创建账号"}</h2>
                </div>
                <span className="lock-badge">SECURE</span>
              </div>

              <div className="mode-switch" role="tablist" aria-label="认证方式">
                <button
                  className={mode === "login" ? "selected" : ""}
                  onClick={() => setMode("login")}
                  type="button"
                  role="tab"
                  aria-selected={mode === "login"}
                >
                  登录
                </button>
                <button
                  className={mode === "register" ? "selected" : ""}
                  onClick={() => setMode("register")}
                  type="button"
                  role="tab"
                  aria-selected={mode === "register"}
                >
                  注册
                </button>
              </div>

              {mode === "login" ? (
                <form onSubmit={handleLogin}>
                  <label>
                    用户名或邮箱
                    <input
                      value={identifier}
                      onChange={(event) => setIdentifier(event.target.value)}
                      autoComplete="username"
                      placeholder="your_name"
                      required
                    />
                  </label>
                  <label>
                    密码
                    <input
                      type="password"
                      value={loginPassword}
                      onChange={(event) => setLoginPassword(event.target.value)}
                      autoComplete="current-password"
                      placeholder="至少 8 位"
                      required
                    />
                  </label>
                  <button className="primary-action" disabled={Boolean(pending)}>
                    <span>{pending === "login" ? "正在验证…" : "进入认证系统"}</span>
                    <span aria-hidden="true">→</span>
                  </button>
                </form>
              ) : (
                <form onSubmit={handleRegister}>
                  <label>
                    用户名
                    <input
                      value={username}
                      onChange={(event) => setUsername(event.target.value)}
                      autoComplete="username"
                      minLength={3}
                      maxLength={32}
                      required
                    />
                  </label>
                  <label>
                    邮箱 <em>选填</em>
                    <input
                      type="email"
                      value={email}
                      onChange={(event) => setEmail(event.target.value)}
                      autoComplete="email"
                    />
                  </label>
                  <label>
                    密码
                    <input
                      type="password"
                      value={registerPassword}
                      onChange={(event) => setRegisterPassword(event.target.value)}
                      autoComplete="new-password"
                      minLength={8}
                      maxLength={72}
                      required
                    />
                  </label>
                  <button className="primary-action" disabled={Boolean(pending)}>
                    <span>{pending === "register" ? "正在创建…" : "创建演示账号"}</span>
                    <span aria-hidden="true">→</span>
                  </button>
                </form>
              )}

              <p className="demo-hint">
                表单已填入演示数据。首次使用请先注册，再切换到登录。
              </p>
            </section>
          ) : (
            <section className="session-card" aria-labelledby="session-title">
              <div className="card-heading">
                <div>
                  <span className="section-index">SESSION / ACTIVE</span>
                  <h2 id="session-title">认证已建立</h2>
                </div>
                <span className="user-avatar">{user.username.slice(0, 1).toUpperCase()}</span>
              </div>

              <div className="user-summary">
                <div>
                  <span>当前用户</span>
                  <strong>{user.username}</strong>
                  <small>{user.email || "未绑定邮箱"}</small>
                </div>
                <div className="role-chip">{user.role}</div>
              </div>

              <div className="token-block">
                <div className="token-heading">
                  <span>ACCESS TOKEN</span>
                  <span>30 MIN</span>
                </div>
                <code>{accessToken.slice(0, 36)}…{accessToken.slice(-18)}</code>
              </div>

              <div className="action-grid">
                <button onClick={verifyIdentity} disabled={Boolean(pending)}>
                  <span>01</span>
                  验证当前身份
                </button>
                <button onClick={refreshAccessToken} disabled={Boolean(pending)}>
                  <span>02</span>
                  刷新访问令牌
                </button>
                <button onClick={() => logout(false)} disabled={Boolean(pending)}>
                  <span>03</span>
                  退出当前设备
                </button>
                <button
                  className="danger"
                  onClick={() => logout(true)}
                  disabled={Boolean(pending)}
                >
                  <span>04</span>
                  退出全部设备
                </button>
              </div>
            </section>
          )}

          <section className="activity-card" aria-label="认证活动">
            <div className="activity-heading">
              <span>ACTIVITY LOG</span>
              <span>最近 {activities.length} 条</span>
            </div>
            <div className="activity-list" aria-live="polite">
              {activities.map((activity, index) => (
                <div className="activity-item" key={`${activity.time}-${index}`}>
                  <span className={`activity-dot ${activity.tone}`} />
                  <time>{activity.time}</time>
                  <p>{activity.message}</p>
                </div>
              ))}
            </div>
          </section>
        </div>
      </section>

      <footer>
        <span>PARSELTONGUE / AUTHENTICATION DEMO</span>
        <span>Controller · Service · DAO</span>
      </footer>
    </main>
  );
}
