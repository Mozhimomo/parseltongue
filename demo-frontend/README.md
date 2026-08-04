# Parseltongue Auth Demo

这是一个可随时删除的独立演示前端，用来操作项目中的注册、登录、JWT 刷新与会话撤销接口。

## 本地运行

先在项目根目录启动 MySQL 和后端：

```powershell
docker compose up -d
mvn spring-boot:run
```

再启动本演示前端：

```powershell
cd demo-frontend
npm install
npm run dev
```

访问 `http://localhost:3000`。开发服务器会把 `/api` 请求代理到 `http://localhost:8080`。

不再需要演示页时，直接删除整个 `demo-frontend` 目录即可，不影响后端。
