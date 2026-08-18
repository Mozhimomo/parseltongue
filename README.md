# Parseltongue

父工程当前包含三个服务：

| 服务 | 技术栈 | 默认端口 | 说明 |
|---|---|---:|---|
| `gateway-service` | Spring Cloud Gateway | 8080 | 统一 API 入口与登录态校验 |
| `auth-service` | Spring Boot MVC、MyBatis | 8081 | 注册、登录、刷新、注销和用户会话 |
| `demo-frontend` | Vinext、React | 3000 | 临时认证测试前端 |

## 本地启动

启动 MySQL：

```powershell
docker compose up -d mysql
```

分别启动两个 Java 服务：

```powershell
.\mvnw.cmd -pl auth-service spring-boot:run
.\mvnw.cmd -pl gateway-service spring-boot:run
```

启动前端：

```powershell
cd demo-frontend
npm run dev
```

前端统一访问 `http://localhost:8080/api/**`，网关将请求转发到认证服务。

## 验证

```powershell
.\mvnw.cmd test
cd demo-frontend
npm test
```
