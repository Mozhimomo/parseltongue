# 独立模型调用服务

`llm-gateway-service` 使用 Java 17、Spring Boot 4.1 和 Spring AI 2.0，通过 **OpenAI Chat Completions 兼容协议**调用模型。模型注册、提供商地址、密钥、超时和输出额度只在本服务配置。

```text
业务服务 → internal-gateway-service（127.0.0.1:8084）
        → llm-gateway-service（127.0.0.1:8083）→ 模型提供商
```

竞技服务已接入本接口用于自然语言创建蛇：后台生成、运行验证、有限次自动修正、保存并用于试跑。模型选择和文本仅在后台使用，玩家只看到蛇的名字、描述和创建状态。比赛逐回合运行 Python，不调用模型；生产沙箱和版本管理仍待实现。

## 两档模型与注册

| 模型别名 | 调用方选择场景 | 当前实际模型 | 默认最大输出 token |
|---|---|---|---|
| `low-cost` | 简单测试、短文本检查、小范围修改 | `deepseek-v4-flash` | 2048 |
| `high-capability` | 复杂策略生成、多约束分析、疑难修复 | `deepseek-v4-flash` | 8192 |

测试阶段两档使用相同的 Flash 模型。调用方根据业务任务复杂度指定别名，服务不会再调用另一个模型判断复杂度，也不会在失败时自动升级到高价模型。以后替换 `LLM_HIGH_CAPABILITY_MODEL` 等配置即可独立切换实际模型，调用方接口不变。

提供商与模型分别注册在 `llm.providers`、`llm.models`。一个提供商配置可以被多个模型复用，也可以新增不同地址、不同密钥的 OpenAI 兼容提供商。模型引用不存在的提供商时启动失败。

例如，使用额外的 Spring 配置文件注册第二家提供商，并让高能力档走该提供商：

```yaml
llm:
  providers:
    premium:
      base-url: ${PREMIUM_BASE_URL}
      api-key: ${PREMIUM_API_KEY}
  models:
    high-capability:
      provider: premium
      provider-model: ${PREMIUM_MODEL}
      max-output-tokens: 8192
      completion-token-limit: true
      thinking: ""
```

通过 `--spring.config.additional-location=file:./llm-extra.yaml` 加载配置；文件只引用环境变量，不填写真实密钥。`completion-token-limit` 控制发送 `max_completion_tokens` 还是 `max_tokens`，按提供商要求设置，两者不会同时发送。`thinking` 是 DeepSeek 扩展，切换到其他提供商时设为空字符串，不发送该字段。

## 本地启动

当前工作区已配置 Git 忽略的 `.local/llm.properties` 与 `.local/internal-gateway.properties`，普通启动命令会自动读取，无需每次粘贴密钥。竞技服务的调用令牌单独放在 `.local/arena.properties`，不包含提供商 key。从仓库根目录或对应服务目录启动均可；环境变量仍可覆盖本地值。这些本地文件不会随 Git 或构建产物分发。

前端策略助手通过 `arena-service` 的 `/api/game/snakes` 接入本服务，竞技服务先核验用户会话并限制私有任务，再携带服务凭据经过内部网关。浏览器不访问 `/internal/**`。

需要两个不同的内部令牌，均至少 32 个字符：调用服务只持有 `GENERATION_SERVICE_TOKEN`；内部网关持有它和 `LLM_GATEWAY_TOKEN`；模型服务只持有 `LLM_GATEWAY_TOKEN` 与提供商密钥。可使用 `[guid]::NewGuid().ToString('N')` 分别生成令牌，配置到对应终端。

终端一，从项目根目录启动模型服务：

```powershell
$env:LLM_GATEWAY_TOKEN = '<内部网关到模型服务的令牌>'
$env:LLM_API_KEY = '<DeepSeek API Key>'
.\mvnw.cmd -pl llm-gateway-service spring-boot:run
```

终端二，启动独立内部网关模块：

```powershell
$env:GENERATION_SERVICE_TOKEN = '<调用服务到内部网关的令牌>'
$env:LLM_GATEWAY_TOKEN = '<与模型服务相同的令牌>'
.\mvnw.cmd -pl internal-gateway-service spring-boot:run
```

公网网关模块 `gateway-service` 仍按原命令启动在 `8080`，不注册 `/internal/**` 路由，即使携带用户 JWT 也不能调用模型接口；内部网关是独立模块 `internal-gateway-service`，路由表只包含下述两个模型接口，不包含登录和游戏 API。健康检查为 `GET /actuator/health`。

内部令牌是本地原型的服务身份方案，不代表已实现架构文档中的 mTLS 和逐服务权限体系。生产部署需隔离公网/内部网络，业务端口仅允许网关访问；只有模型服务开放提供商网络出口。不能把内部网关作为公网用户接口使用。

## 接口

内部网关的请求头：`Authorization: Bearer <GENERATION_SERVICE_TOKEN>`。网关校验后替换为下游专用令牌，不把调用方的 Cookie 或伪造用户标识传给模型服务。

### 查询模型注册

`GET http://127.0.0.1:8084/internal/llm/models`

返回 `code/message/data`，`data` 为模型别名、提供商注册名、实际模型名和最大输出 token 列表；不返回地址和密钥。

### 文本生成

`POST http://127.0.0.1:8084/internal/llm/generations`

```json
{
  "taskId": "strategy-test-001",
  "model": "low-cost",
  "messages": [
    {"role": "system", "content": "你是贪吃蛇策略代码助手。"},
    {"role": "user", "content": "用一句话说明如何优先避免撞墙。"}
  ],
  "maxOutputTokens": 128
}
```

复杂任务将 `model` 改成 `high-capability`。`maxOutputTokens` 可省略，使用该档配置的上限；显式超限返回 400，不静默截断额度。`messages` 支持 `system/user/assistant`，至少包含一个 `user`，最多 32 条。请求不能指定 API key、URL、工具或其他模型参数，未知字段返回 400。

成功返回示例（用量仅为格式示意）：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "llmRequestId": "服务生成的 UUID",
    "taskId": "strategy-test-001",
    "model": "low-cost",
    "provider": "deepseek",
    "providerModel": "deepseek-v4-flash",
    "content": "先排除下一步会撞墙的方向。",
    "finishReason": "stop",
    "usage": {"inputTokens": 30, "outputTokens": 12, "totalTokens": 42}
  }
}
```

`providerModel` 优先采用提供商返回的实际模型标识，可能包含版本后缀。提供商未返回用量时 `usage` 为 `null`，表示未知，不表示免费。`finishReason=length` 表示输出被 token 上限截断，业务方不能把它当作完整代码；服务不执行返回文本或模型工具。

| HTTP 状态 | 含义 |
|---|---|
| 400 | 参数、消息、别名或输出额度无效 |
| 401 / 403 | 服务身份无效 / 不允许的接口 |
| 413 | 请求体超过 128 KiB 或消息总字符数超过配置 |
| 429 | 本实例调用并发已满，尚未请求提供商 |
| 502 | 提供商失败或未返回可用文本；不透传提供商错误正文 |
| 504 | 模型调用超时，提供商是否已完成及计费未知 |

服务生成 `llmRequestId`，通过响应正文和 `X-Request-Id` 返回，并记录任务、模型、状态、耗时和 token；普通日志不记录密钥、提示词和生成代码。公网/内部网关另有自己的请求跟踪日志。

## 配置

| 环境变量 | 默认值 / 说明 |
|---|---|
| `LLM_API_KEY` | 必填，仅模型服务注入 |
| `LLM_BASE_URL` | `https://api.deepseek.com`；使用 SDK 基址，不追加 `/chat/completions` |
| `LLM_GATEWAY_TOKEN` | 必填，内部网关和模型服务共享，至少 32 字符 |
| `GENERATION_SERVICE_TOKEN` | 必填，调用服务和内部网关共享，与上一个令牌不同 |
| `LLM_LOW_COST_MODEL` / `LLM_HIGH_CAPABILITY_MODEL` | 均为 `deepseek-v4-flash` |
| `LLM_LOW_COST_PROVIDER` / `LLM_HIGH_CAPABILITY_PROVIDER` | 均为 `deepseek`，引用提供商注册名 |
| `LLM_LOW_COST_MAX_OUTPUT_TOKENS` / `LLM_HIGH_CAPABILITY_MAX_OUTPUT_TOKENS` | `2048` / `8192` |
| `LLM_LOW_COST_COMPLETION_TOKEN_LIMIT` / `LLM_HIGH_CAPABILITY_COMPLETION_TOKEN_LIMIT` | `false`，使用 `max_tokens` |
| `LLM_LOW_COST_THINKING` / `LLM_HIGH_CAPABILITY_THINKING` | `disabled`，测试时控制开销；可单独设为 `enabled` |
| `LLM_MAX_INPUT_CHARACTERS` | `32000`，所有消息的 Java 字符长度之和 |
| `LLM_MAX_CONCURRENT_CALLS` | `4`，全模型共用的本实例上限，满载立即拒绝 |
| `LLM_TIMEOUT` | `60s`，单次提供商请求时限 |
| `LLM_SERVICE_ADDRESS` / `LLM_SERVICE_PORT` | `127.0.0.1` / `8083` |

内部网关自身的环境变量见 [internal-gateway-service 文档](../internal-gateway-service/README.md)。

## 测试与当前边界

```powershell
# 自动测试使用本地模拟提供商，不需要真实 key，不产生模型费用
.\mvnw.cmd -pl llm-gateway-service,gateway-service,internal-gateway-service -am test

# 服务和内部网关启动后，显式进行真实连通性测试
$env:GENERATION_SERVICE_TOKEN = '<调用服务到内部网关的令牌>'
python llm-gateway-service/smoke_gateway.py
```

真实测试脚本只访问内部网关，不持有提供商 key；对两档各发一次短请求，每次最多 32 个输出 token。普通 Maven 测试不会自动调用真实提供商。

首版已实现模型/提供商注册、双档路由、OpenAI 兼容协议、内部身份校验、输入/输出额度、并发和超时、用量与日志。SDK 状态码重试设为 0，不做应用层自动重放或高价模型降级。`taskId` 当前用于关联业务任务，**不是幂等键**；重复请求会再次调用并可能计费，超时不能盲目重试。

每日费用预算、按用户/任务限速、持久化审计与幂等账本、多实例统一配额、流式与 JSON Schema 输出尚未实现。当前额度为单次 token 上限和实例并发上限，不是费用结算系统。用户权限应由业务服务验证，不能让前端直接持有服务令牌。生成代码的安全沙箱仍按架构文档另行建设。

参考：[Spring AI 2.x / Boot 4.x 兼容性](https://docs.spring.io/spring-ai/reference/getting-started.html)、[DeepSeek OpenAI 兼容 API](https://api-docs.deepseek.com/)、[DeepSeek 思考模式](https://api-docs.deepseek.com/guides/thinking_mode/)。
