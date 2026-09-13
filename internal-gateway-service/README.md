# 内部服务网关（internal-gateway-service）

仅监听 `127.0.0.1:8084` 的独立服务网关，只转发两个模型接口：`POST /internal/llm/generations` 与 `GET /internal/llm/models` 到 `llm-gateway-service`（默认 `http://127.0.0.1:8083`），请求体上限 128 KiB，下游响应超时默认 70 秒。浏览器不访问 `/internal/**`。

```text
业务服务（arena-service） → internal-gateway-service（127.0.0.1:8084）
        → llm-gateway-service（127.0.0.1:8083）→ 模型提供商
```

早期内部入口是 `gateway-service` 的一个 `internal` profile 实例；现拆为独立模块，公网网关与内部网关可各自演进。两个网关都需要的少量小类（请求跟踪过滤器、错误响应与错误处理）按约定在各自模块内复制一份，**不设共享 common 模块**——修改任何一侧需同步另一侧。

## 服务令牌

两段令牌，均至少 32 字符且必须不同（见本模块 `InternalGatewayConfig` 启动校验）：

| 令牌 | 持有方 |
|---|---|
| `GENERATION_SERVICE_TOKEN` | 调用服务（arena-service）→ 本网关；校验后丢弃 |
| `LLM_GATEWAY_TOKEN` | 本网关 → 模型服务；转发时替换 Authorization，并剥离 Cookie / X-User-Id / X-Service-Id |

## 本地启动

工作区已配置 Git 忽略的根目录 `.local/internal-gateway.properties`（含两个令牌），从仓库根目录或本模块目录启动都会自动读取；环境变量仍可覆盖。

```powershell
.\mvnw.cmd -pl internal-gateway-service spring-boot:run
```

或打包后运行 `java -jar internal-gateway-service/target/internal-gateway-service-0.0.1-SNAPSHOT.jar`。
健康检查：`GET http://127.0.0.1:8084/actuator/health`。

## 环境变量

| 名称 | 默认值 / 说明 |
|---|---|
| `INTERNAL_GATEWAY_ADDRESS` / `INTERNAL_GATEWAY_PORT` | `127.0.0.1` / `8084` |
| `LLM_SERVICE_URL` | `http://127.0.0.1:8083`，下游模型服务地址 |
| `GENERATION_SERVICE_TOKEN` | 必填，无默认值；调用服务凭据，至少 32 字符 |
| `LLM_GATEWAY_TOKEN` | 必填，无默认值；与模型服务共享，须与调用令牌不同 |
| `INTERNAL_GATEWAY_RESPONSE_TIMEOUT` | `70s`，应大于模型服务的 `LLM_TIMEOUT` |

## 测试

```powershell
.\mvnw.cmd -pl internal-gateway-service -am test
```

自动测试启动 reactor-netty 桩下游，不需要真实模型服务，不产生费用。真实连通性测试见 [模型服务](../llm-gateway-service/README.md) 的 `llm-gateway-service/smoke_gateway.py`（访问本网关 8084）。
