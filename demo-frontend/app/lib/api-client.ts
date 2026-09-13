export type User = { id: number; username: string; role: "USER" | "ADMIN" };
export type LoginData = { accessToken: string; expiresInSeconds: number; user: User };
export type Session = { status: "checking" | "authenticated" | "guest" | "offline"; user: User | null };
export const initialSession: Session = { status: "checking", user: null };
export class ApiError extends Error {
  status: number;
  constructor(message: string, status: number) { super(message); this.name = "ApiError"; this.status = status; }
}
export function createSessionClient(transport: typeof fetch = (...args) => fetch(...args)) {
  let snapshot = initialSession, token = "", expires = 0, epoch = 0;
  let refreshFlight: Promise<LoginData> | null = null;
  let initialization: Promise<void> | null = null;
  const listeners = new Set<() => void>();
  const emit = (next: Session) => { snapshot = next; listeners.forEach(listener => listener()); };
  const accept = (data: LoginData) => {
    token = data.accessToken; expires = Date.now() + data.expiresInSeconds * 1000;
    emit({ status: "authenticated", user: data.user });
  };
  const clear = () => { token = ""; expires = 0; emit({ status: "guest", user: null }); };
  async function raw<T>(path: string, init: RequestInit = {}, bearer = ""): Promise<T> {
    const headers = new Headers(init.headers);
    if (init.body) headers.set("Content-Type", "application/json");
    if (bearer) headers.set("Authorization", `Bearer ${bearer}`);
    const timeout = AbortSignal.timeout(12000);
    try {
      const response = await transport(path, { ...init, headers, credentials: "include", signal: init.signal ? AbortSignal.any([init.signal, timeout]) : timeout });
      const payload = await response.json().catch(() => null);
      if (!response.ok || payload?.code !== 0) {
        const message = response.status >= 500 ? "服务暂时不可用，请稍后重试。" : payload?.message;
        throw new ApiError(message || "请求未能完成，请重试。", response.status);
      }
      return payload.data as T;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      if (init.signal?.aborted) throw error;
      throw new ApiError(timeout.aborted ? "请求超时，请检查网络后重试。" : "暂时无法连接服务，请稍后重试。", 0);
    }
  }
  function refresh(): Promise<LoginData> {
    if (refreshFlight) return refreshFlight;
    const started = epoch;
    // Serialize cookie rotation across tabs as well as concurrent requests in this tab.
    const refreshRequest = () => raw<LoginData>("/api/auth/refresh", { method: "POST" });
    const performRefresh = async (): Promise<LoginData> => {
      if (typeof navigator !== "undefined" && navigator.locks) return await navigator.locks.request("parseltongue-session-refresh", refreshRequest);
      return await refreshRequest();
    };
    const flight = performRefresh().then(data => {
      if (epoch === started) accept(data);
      return data;
    }).catch(error => {
      if (epoch === started && error instanceof ApiError && error.status === 401) clear();
      throw error;
    });
    refreshFlight = flight;
    void flight.finally(() => { if (refreshFlight === flight) refreshFlight = null; }).catch(() => {});
    return flight;
  }
  async function authenticated<T>(path: string, init: RequestInit = {}): Promise<T> {
    if (!token || expires <= Date.now() + 15000) await refresh();
    const usedToken = token;
    try { return await raw<T>(path, init, usedToken); }
    catch (error) {
      if (!(error instanceof ApiError) || error.status !== 401) throw error;
      if (usedToken === token) await refresh();
      try { return await raw<T>(path, init, token); }
      catch (retryError) { if (retryError instanceof ApiError && retryError.status === 401) clear(); throw retryError; }
    }
  }
  return {
    getSnapshot: () => snapshot,
    subscribe: (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; },
    initialize: () => {
      initialization ??= refresh().then(() => {}).catch(error => {
        if (snapshot.status === "checking") emit({ status: error instanceof ApiError && error.status === 401 ? "guest" : "offline", user: null });
      });
      return initialization;
    },
    raw, authenticated, refresh,
    async login(username: string, password: string) {
      epoch++; refreshFlight = null;
      const data = await raw<LoginData>("/api/auth/login", { method: "POST", body: JSON.stringify({ username, password }) });
      accept(data); return data.user;
    },
    register: (username: string, password: string) => raw<User>("/api/auth/register", { method: "POST", body: JSON.stringify({ username, password }) }),
    async verify() { const user = await authenticated<User>("/api/users/me"); emit({ status: "authenticated", user }); return user; },
    async logout(all = false) { await authenticated(all ? "/api/auth/logout-all" : "/api/auth/logout", { method: "POST" }); epoch++; refreshFlight = null; clear(); },
  };
}
export const sessionClient = createSessionClient();
export const errorMessage = (error: unknown) => error instanceof Error ? error.message : "操作未能完成，请重试。";
