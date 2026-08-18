# Parseltongue Demo Frontend

临时认证测试前端，用于操作注册、登录、JWT 刷新和会话撤销接口。

## 本地运行

先在父工程根目录启动 MySQL、认证服务和网关：

```powershell
docker compose up -d mysql
.\mvnw.cmd -pl auth-service spring-boot:run
.\mvnw.cmd -pl gateway-service spring-boot:run
```

再启动前端：

```powershell
cd demo-frontend
npm run dev
```

访问 `http://localhost:3000`。开发服务器会把 `/api` 请求代理到统一网关 `http://localhost:8080`。
