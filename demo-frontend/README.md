# Parseltongue Web

正式产品前端，沿用现有 `demo-frontend` 目录以保持构建路径兼容，包名为 `parseltongue-web`。采用 Vinext、React 19、TypeScript；本次仅在本地运行，不发布到 Sites。

## 页面与已接入能力

| 页面 | 功能 |
|---|---|
| `/`、`/game` | 四蛇策略选择、随机或指定种子、回合上限、异步开局、进度和错误恢复 |
| 竞技场回放 | 播放/暂停、重播、单步、0.5–8 倍速、拖动回合、查看结局、各蛇饥饿/长度/淘汰、事件记录、导出 JSON |
| `/assistant` | 我的蛇：名字＋自然语言描述、后台异步创建、状态恢复、持久列表、进入竞技场 |
| `/history` | 当前标签页/账号的试跑记录、重新打开私有回放；明确提示临时保留，不伪造服务端历史列表 |
| `/login`、`/register` | 空白真实表单、用户名/密码校验、密码可见切换、注册后登录、恢复原目标页面 |
| `/account` | 当前用户、角色、更新账号资料、自动/手动续期、退出当前设备、确认后退出全部设备 |
| `/rules` | 同步结算、食物/饥饿、碰撞、平局和无结果、四种内置策略 |

界面适配桌面、平板和手机，提供键盘焦点、语义标签、登录/空/加载/错误状态。回放面板聚焦后可用空格切换播放，左右方向键逐回合移动。竞技场可选择自己已经创建的蛇；排行榜和排位尚未实现。

## 本地启动

需要 Java 17、Python 3.10+、Node.js >=22.13、MySQL。依赖已安装时可直接运行；首次安装用 `npm ci`。

在项目根目录启动数据库，再分别在终端运行以下服务：

```powershell
docker compose up -d mysql
.\mvnw.cmd -pl auth-service spring-boot:run
.\mvnw.cmd -pl gateway-service spring-boot:run
.\mvnw.cmd -pl arena-service spring-boot:run
.\mvnw.cmd -pl llm-gateway-service spring-boot:run
.\mvnw.cmd -pl internal-gateway-service spring-boot:run
```

当前工作区的 DeepSeek 地址、key 和内部令牌已保存在 Git 忽略的根目录 `.local/` 中，相关服务自动读取。新机器需按 [模型服务文档](../llm-gateway-service/README.md) 配置；模型服务和内部网关未启动时，账号与对局功能仍可独立使用。

启动前端：

```powershell
cd demo-frontend
npm run dev
```

访问终端打印的地址，默认 [http://localhost:3000](http://localhost:3000)，端口占用时自动选择下一个端口。前端 `/api` 统一代理至 `http://localhost:8080`；可在前端进程设置 `GATEWAY_URL` 更换业务网关。

如果本机 `npm` 命令指向失效路径，可使用 Node 安装目录内的 `npm.cmd`，或直接 `node node_modules/vinext/dist/cli.js dev`；无需重装工程依赖。

## 认证与数据边界

- Access token 仅保存在浏览器内存中，不展示在 UI，不写 localStorage/sessionStorage。刷新使用后端 HttpOnly Cookie；同标签页请求共享刷新，支持 Web Locks 的浏览器还会串行处理跨标签页 Cookie 轮换。
- 页面共享账号状态和当前对局；退出后清除可见私人数据。所有对局和策略任务由后端再次核验会话与所属用户。
- sessionStorage 只保存按用户区分的当前标签页对局索引，不保存 token 或源码。蛇列表和状态从服务端加载，刷新或重新登录后恢复；已生成脚本持久保存在后端。对局回放仍为临时内存数据。
- 策略请求走 `前端 → 公网网关 → 竞技服务 → 内部网关 → 模型服务 → 提供商`。服务凭据和提供商 key 均不进入前端环境或构建文件。
- 脚本由后端在 E2B 中完成运行验证后才能用于试跑；含自建蛇的整场比赛也在 E2B 中完成。前端不展示模型、修正对话、代码或 token 用量。运行前需配置 E2B 密钥；代码版本管理待实现。
- 生产 Worker 已提供同源 `/api` 转发，未来部署时需设置服务端 `GATEWAY_URL` 指向可访问的业务网关。当前只完成本地运行，未上线。

## 验证

```powershell
npm run test:unit
npm run typecheck
npm run lint
npm run build
```

`npm test` 顺序执行会话单元测试、类型检查和生产构建；自动测试不会调用真实模型。后端 `StrategyTests` 覆盖策略入口鉴权、任务归属、并发限制和参数校验。浏览器交互和视觉验收需另行进行，编译通过不等于浏览器端到端验证。
