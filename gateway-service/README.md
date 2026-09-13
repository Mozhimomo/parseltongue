# Parseltongue Gateway Service

公网 API 入口默认监听 `8080`，将 `/api/game/**` 转发到 `8082` 的竞技服务，其余 `/api/**` 转发到 `8081` 的认证服务。公网拒绝 `/internal/**`，包括携带有效用户 JWT 的请求。

内部模型入口已拆分为独立模块 [internal-gateway-service](../internal-gateway-service/README.md)，默认只监听 `127.0.0.1:8084`，其路由表、服务令牌与启动方式见该模块文档。本服务只部署公网入口。

## 本地启动

在父工程根目录执行：

先按[根目录配置说明](../README.md#本地启动现有功能)导入 `.env`；`JWT_SECRET` 必填，必须与认证服务一致。

```powershell
.\mvnw.cmd -pl gateway-service spring-boot:run
```

前端继续访问 `http://localhost:8080/api/**`，不直接调用认证服务。

## 环境变量

| 名称 | 默认值 | 说明 |
|---|---|---|
| `GATEWAY_PORT` | `8080` | 网关端口 |
| `AUTH_SERVICE_URL` | `http://localhost:8081` | 认证服务地址 |
| `ARENA_SERVICE_URL` | `http://localhost:8082` | 竞技服务地址 |
| `JWT_SECRET` | 无，必填 | 至少 32 字节随机数据的 Base64 编码，必须与认证服务一致 |
| `CORS_ALLOWED_ORIGIN_PATTERN` | `http://localhost:*` | 允许的前端来源 |
| `GATEWAY_CONNECT_TIMEOUT_MS` | `3000` | 连接下游超时，毫秒 |
| `GATEWAY_RESPONSE_TIMEOUT` | `10s` | 下游响应超时 |
