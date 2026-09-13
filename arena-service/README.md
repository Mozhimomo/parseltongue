# Parseltongue 游戏服务

提供内置 AI / 自建蛇试跑、私有回放，以及自然语言创建蛇的完整基础流程。玩家只提交名字和描述，后端首先审查是否存在破坏系统的恶意意图，通过后才识别玩法、生成代码、E2B 验证、修正和保存。模型调用均通过独立 `internal-gateway-service`，安全审查、意图分析、模型、代码和用量等内部过程不返回浏览器。

在项目根目录另开终端：

```powershell
.\mvnw.cmd -pl arena-service spring-boot:run
```

同时需要认证服务（8081）、网关（8080）、前端（3000）。登录后访问前端 `/game`。竞技服务默认只监听 `127.0.0.1:8082`，客户端请求使用网关 `/api/game/**`。

| 环境变量 | 默认 | 用途 |
|---|---|---|
| `ARENA_SERVICE_PORT` | `8082` | 服务端口 |
| `ARENA_BIND_ADDRESS` | `127.0.0.1` | 绑定地址；跨主机部署前需配置网关与服务网络访问控制 |
| `GATEWAY_URL` | `http://localhost:8080` | 会话核验走此网关的 `/api/users/me` |
| `GAME_PYTHON` | `python` | Python 3.10+ 可执行文件，可设为绝对路径 |
| `E2B_PYTHON` | 同 `GAME_PYTHON` | 安装了 `match-worker/requirements.txt` 的可信 SDK 控制器 Python |
| `E2B_API_KEY` | 无 | E2B 控制面密钥，不进入沙箱 |
| `E2B_TEMPLATE` | `base` | 受信任的 Linux/Python 模板 |
| `GAME_WORKER_DIR` | 向上寻找 `match-worker` | 已部署的可信执行器目录，不接受用户输入 |
| `ARENA_DB_URL` | `DB_URL` 或 `jdbc:mysql://127.0.0.1:3307/parseltongue?...` | 策略库 JDBC 地址，默认使用项目 Docker MySQL |
| `ARENA_DB_USERNAME` / `ARENA_DB_PASSWORD` | `DB_USERNAME` / `DB_PASSWORD` 或项目开发默认值 | 策略库账号；只用于竞技服务，不进入沙箱 |
| `ARENA_SERVICE_URL` | `http://localhost:8082` | 网关进程中的竞技服务地址 |
| `INTERNAL_GATEWAY_URL` | `http://127.0.0.1:8084` | 策略助手的内部网关入口 |
| `GENERATION_SERVICE_TOKEN` | 无默认值 | 仅用于调用内部网关；提供商 API key 不进入竞技服务 |
| `SNAKE_INTENT_MODEL` | `low-cost` | 意图识别阶段使用的模型别名，玩家不可指定 |
| `SNAKE_SECURITY_MODEL` | `low-cost` | 优先执行的请求安全审查模型别名，玩家不可指定 |
| `SNAKE_GENERATION_MODEL` | `high-capability` | 代码生成和代码修正阶段使用的模型别名，玩家不可指定 |
| `SNAKE_STORAGE_DIR` | 项目根目录 `.local/snakes` | 旧文件策略的导入目录；新生成策略直接存入 MySQL |

本地自动读取项目根目录 `.local/arena.properties` 和 `.local/e2b.properties`（Git 忽略）；服务令牌应与内部网关配置一致。E2B 安装、配置与验证见 [执行器说明](../match-worker/sandbox/README.md)。修改后重启竞技服务。缺少配置时仍可运行内置蛇比赛，自建蛇不可用。

## API

所有接口都必须带有效 Bearer Token。每次请求经网关向现有认证接口核验数据库会话，所以注销会话后访问游戏接口会被拒绝；这暂代目标架构中尚未实现的内部 mTLS 核验接口。

- `GET /api/game/agents`：四种内置策略及当前玩家已创建的蛇，包含 ID、名字和描述。
- `POST /api/game/trials`：返回 202 与任务 ID。
- `GET /api/game/trials/{id}`：查询 `QUEUED/RUNNING/SUCCEEDED/FAILED`。
- `GET /api/game/trials/{id}/replay`：成功后获取回放；其他用户不可访问。
- `POST /api/game/snakes`：提交 `{ "name": "稳稳蛇", "description": "先避碰，饥饿时找食物" }`，返回 202。名字最多 40 字，描述最多 8000 字；拒绝 model、source 等未知字段。
- `GET /api/game/snakes`：当前玩家的蛇，按创建时间倒序。
- `GET /api/game/snakes/{id}`：查询 `GENERATING/READY/FAILED`。仅返回 id、name、description、status、error、createdAt；不返回源码、模型或修复详情。

创建流程：输入校验 → 创建异步任务 → 安全规则扫描 → 密钥/SDK 配置预检 → `low-cost` 恶意意图审查 → 明确 ALLOW 后才进入 `low-cost` 玩法识别 → 意图结构校验 → `high-capability` 代码生成 → E2B 内检查 → 保存并变为 READY。

输入校验拒绝空白、长度超限、无文字/数字的描述及异常控制字符，不消耗模型调用。登录、字段白名单、归属和限额检查继续生效。安全规则对名字和描述进行 Unicode 规范化后扫描凭据、文件、网络、进程/资源滥用、逃逸和提示词注入等线索；命中结果供模型结合上下文审核，不以关键词单独定罪，未命中也不能跳过审核。

安全审查是独立且优先的必经阶段，只返回 `ALLOW/BLOCK/UNCERTAIN`、风险类别和简短原因。重点审查窃密、删除/篡改系统数据、外传、执行命令、耗尽资源、绕过限制和审核、沙箱逃逸、编码/延迟攻击。正常玩法夹带恶意指令时按完整请求拒绝，不能先提取正常玩法而洗掉恶意部分；名字也参与审查。围攻、抢食、饿死对手等正常游戏行为与系统攻击分开判断，否定危险操作的防御性约束也需要结合上下文。

只有字段、类型、枚举、长度及一致性均合法的明确 `ALLOW` 才继续。`BLOCK`、`UNCERTAIN`、连续格式错误、截断或审核服务故障均停止，不进入玩法识别、代码生成或策略运行。明确拒绝/不确定的结论不重试；仅格式错误/截断允许重试一次。审查结论保存于策略库 `security_review`，普通日志只记录阶段/判定，玩家只看到普通失败状态，不暴露命中规则或审查原因。

低成本模型返回结构化意图：是否存在可实现的蛇玩法、玩法摘要、按重要性排序的目标、冒险程度、约束和默认假设。简短风格会补全合理默认值，不与玩家反复交流；不可能的能力尽量转换为可行的移动目标。无可用玩法的请求停止，不进入代码生成。响应必须通过字段、类型、枚举和长度校验，意图记录保存在策略库 `strategy_intent` 中。原始描述和校验后的意图作为数据一起传给代码模型；意图识别不是安全审核，E2B 仍是执行边界。

安全审查最多输出 512 tokens，玩法识别最多输出 1024 tokens，两阶段各允许一次格式修正重试。代码阶段最多生成三次（首次加两次修正），沿用同一份意图。正常链路为一次安全审查、一次玩法识别、一次代码生成；极端上限为 2+2+3 次。模型网络/服务故障不自动重放；E2B 基础设施错误不触发模型修正。私有诊断通过 `stage=security/intent/model/validation` 区分失败位置，任务 ID 使用 `-security-N` / `-intent-N` / `-code-N` 区分阶段。

请求侧审查降低恶意请求进入生成阶段的概率，不证明模型绝不漏判，也不证明生成源码一定无害。E2B 执行隔离仍强制保留。本地测试使用模拟审核结果验证顺序、拒绝和故障分支；实际模型的攻击识别率、误报率还需用真实对抗样本评估。生成源码的内容安全审核尚未实现。

每份候选使用一个新 E2B 沙箱，检查包括加载、`decide` 接口、合法方向和禁止反向，24 个边界调用，以及四席位 × 三种子共 12 场真实对局（每场最多 200 回合）。检查证明这些场景能运行，不保证任意局面的正确性或胜率。

代码生成提示词位于 `src/main/resources/prompts/strategy-generation.md`，启动时合并同目录的 observation JSON Schema、完整局面示例和可运行 Python 示例，作为首次生成与每次修正共用的系统提示词。内容包含任务输入、源码/函数返回值两层输出契约、坐标和字段定义、同步结算、尾巴占位、饥饿公式、胜负规则、预算、边界情况与修正要求。修改后需重新构建并重启竞技服务。

生成请求按默认网关 32000 字符预算组织：优先完整保留系统契约和结构化策略意图；原始描述因转义等导致超长时仅向代码模型发送带 `description_truncated` 标记的前缀，保存的原文与前置安全审查仍完整。修正时按剩余预算截取上一次源码，不再固定附带 16000 字符而挤占规则空间。Python 测试将局面示例与真实引擎输出对比，并在 Linux 子进程中运行提示词的完整代码示例通过 36 项检查；这验证示例可运行，不代表实际模型总能生成同等质量代码。

两档别名的实际模型由模型服务的 `LLM_LOW_COST_MODEL` 和 `LLM_HIGH_CAPABILITY_MODEL` 决定。仓库默认都指向 `deepseek-v4-flash`；要获得真实的能力/成本分层，需要配置不同的实际模型。竞技服务不会自行猜测或切换提供商模型。

全局最多 8 个未完成创建、两路执行；每玩家最多 1 个未完成创建，10 分钟最多 10 次。限额通过数据库计数和事务锁执行，重启不会清空历史计数。源码版本和 READY 状态一起提交，写入失败不会暴露半成品。模型网络失败和超时不自动重放，避免状态不明时重复消费。

## 策略库与迁移

竞技服务现依赖 MySQL，默认连接项目 Docker 容器的 `127.0.0.1:3307/parseltongue`。Flyway 使用独立目录 `db/arena-migration` 和历史表 `arena_flyway_schema_history`，从版本 0 建立基线，避免与认证服务的迁移记录冲突。

业务数据访问与认证服务统一使用 MyBatis：`StrategyMapper` 接口对应 `mapper/StrategyMapper.xml` 中的参数化 SQL 和结果映射；`StrategyRepository` 负责额度规则、SHA-256 校验和 `TransactionTemplate` 事务边界。准入锁、额度检查与任务插入共用事务，源码版本与 READY 更新也共用事务。测试中的 `JdbcTemplate` 仅用于准备或检查数据库状态、注入损坏数据，业务调用均经过真实 MyBatis Mapper。

| 表 | 持久内容 |
|---|---|
| `snake_strategies` | UUID、玩家归属、名字、原始描述、生成状态、失败信息、当前版本、审查结果、玩法意图和时间 |
| `snake_strategy_versions` | 策略 ID + 版本号、完整 Python 源码、SHA-256、验证结果、生成模型别名和时间 |
| `snake_generation_diagnostics` | 各阶段失败诊断与尝试次数，不通过玩家 API 暴露 |
| `snake_creation_guard` | 创建任务时检查限额的事务锁 |

当前每条创建成功的蛇发布不可覆盖的第 1 版，后续编辑/版本切换接口尚未开放。比赛按玩家归属读取当前源码版本，并核对 SHA-256；列表和状态只查询元数据，不读取或返回源码。数据库中没有新增跨服务用户表外键，用户身份继续由业务网关核验。

首次启动自动导入 `.local/snakes`（或 `SNAKE_STORAGE_DIR`）中的旧元数据、源码和审查/意图/验证/诊断附件。同一 UUID 只导入一次，不覆盖数据库记录；原文件保留作迁移备份，不再作为新数据源。缺失/超限源码的 READY 记录转为 FAILED，无效元数据跳过并记录日志。可通过启动日志 `Strategy library initialized imported=...` 检查迁移数量。

生成调度仍是**单实例内存队列**：重启时数据库中未完成的 GENERATING 记录标为 FAILED，不自动重放付费请求；所有完成/失败策略及源码继续保留。请勿同时运行两个竞技服务实例；多实例租约、持久任务调度和自动续跑尚未实现。对局回放的持久化不在本次策略库范围内。

持久数据保存在 Docker 命名卷 `parseltongue_mysql_data`。应用/容器重启会保留数据；备份应覆盖 MySQL 数据库，不再只备份 `.local/snakes`。不要使用 `docker compose down -v` 清理需要保留的数据库。业务数据库连接信息只保留在竞技服务，E2B 控制器继续清理环境变量且仅上传当前比赛所需源码。

创建请求：

```json
{"agents":["straight","cautious","greedy","forager"],"seed":42,"maxTicks":500}
```

agents 必须恰好四个：内置注册名或 `snake:<uuid>`。自建蛇必须属于当前玩家且为 READY；源代码由后端读取。seed 为 0..2147483647，maxTicks 为 1..2000；不允许提交代码、脚本路径或任意启动参数。回放仅包含动作、状态及 `agent_names`，不含脚本。

## 原型限制

同一用户最多 2 个未完成比赛，全局最多 8 个，两路并行。内置比赛使用 `run_match.py`，上限 30 秒。含自建蛇的整场比赛由 `strategy_job.py` 提交到一个新 E2B 沙箱，内部四个常驻子进程负责策略，规则引擎也在其中运行。主机仅提交任务和校验回放，不加载生成代码。E2B 命令最多 80 秒，VM 到期销毁上限 120 秒，Java 控制任务上限 130 秒，输出最多 8MiB。缺少密钥或 SDK 时不调用模型；云端不可用时任务失败，绝不降级本机执行。

回放存于有界内存：最多约 24 条任务、16 MiB JSON 字符预算，完成记录最长 30 分钟；达到容量会提前淘汰旧记录，服务重启清空。不保证任务持久性，不进行排位结算。当前接受同局脚本可能互相干扰，E2B 负责隔离业务主机；回放校验不构成反作弊。版本管理、持久队列、云端配额监控和排位后续实现。

```powershell
.\mvnw.cmd -pl arena-service -am test
```

测试包含真实 Python 子进程对局、回放归属、注销会话拒绝、参数约束、队列限额和执行失败，因此测试机也需要 Python。

`StrategyTests` 使用独立 H2 测试库并模拟 E2B 结果，不需要云账号；`StrategyRepositoryTests` 覆盖事务、归属、迁移、重启恢复及源码完整性。Python 测试分别覆盖 SDK 调度和 Linux 内部执行器，真实云端验收显式启用。失败细节写入策略库诊断表，玩家仍只看到创建状态。
