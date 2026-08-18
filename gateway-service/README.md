# Parseltongue Gateway Service

统一 API 入口，默认监听 `8080`，并将 `/api/**` 转发到默认监听 `8081` 的认证服务。

## 本地启动

在父工程根目录执行：

```powershell
.\mvnw.cmd -pl gateway-service spring-boot:run
```

前端继续访问 `http://localhost:8080/api/**`，不直接调用认证服务。

## 环境变量

| 名称 | 默认值 | 说明 |
|---|---|---|
| `GATEWAY_PORT` | `8080` | 网关端口 |
| `AUTH_SERVICE_URL` | `http://localhost:8081` | 认证服务地址 |
| `JWT_SECRET` | 本地开发密钥 | 必须与认证服务一致 |
| `CORS_ALLOWED_ORIGIN_PATTERN` | `http://localhost:*` | 允许的前端来源 |
| `GATEWAY_CONNECT_TIMEOUT_MS` | `3000` | 连接下游超时，毫秒 |
| `GATEWAY_RESPONSE_TIMEOUT` | `10s` | 下游响应超时 |
