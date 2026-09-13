# E2B 策略执行

每次候选检查创建一个 E2B 沙箱，36 项检查都在里面完成；模型修正后的下一份候选使用新沙箱。每场包含自建蛇的比赛也创建一个新沙箱，四条蛇和整套规则引擎都在其中运行，完成后一次返回回放。四条蛇使用常驻 Python 子进程，每回合通过本地管道传递 JSON，不调用 E2B 网络接口。只有内置策略的比赛继续使用本机可信执行器。

## 配置

在项目根目录安装 SDK（业务主机 Python 3.10+）：

```powershell
python -m venv .local/e2b-venv
.local/e2b-venv/Scripts/python.exe -m pip install -r match-worker/requirements.txt
```

复制 `match-worker/e2b.properties.example` 到 `.local/e2b.properties`，填写：

```properties
E2B_API_KEY=<在 E2B 控制台创建的 API key>
E2B_TEMPLATE=base
E2B_PYTHON=D:/Work/Parseltongue/.local/e2b-venv/Scripts/python.exe
```

`E2B_PYTHON` 改成部署机器上的绝对路径。SDK 固定为 `e2b==2.46.4`。默认 `base` 模板须提供 Linux、`python3`（3.10+）和 `user` 用户；也可以指定受信任的自定义模板 ID。模板不要包含业务凭据、持久卷或自动运行的外部服务。生产应固定模板版本，并在升级后执行云端验收。

竞技服务启动时自动读取 `.local/arena.properties` 和 `.local/e2b.properties`，从项目根目录或 `arena-service` 目录启动均可。修改后重启竞技服务。`.local` 已被 Git 忽略；密钥只进入可信 SDK 控制进程，不上传到沙箱，不放在命令行。

不需要 Docker、gVisor 或每条蛇独立的云沙箱。旧 `GAME_SANDBOX_*` / `GAME_DOCKER_CONTEXT` 配置已停止使用。

## 边界与故障

- E2B 创建参数显式关闭互联网访问、限制公开入口、启用安全访问令牌，生命周期为到期销毁。沙箱不挂载本机目录、不传数据库/模型网关凭据。详情见 [E2B 网络设置](https://docs.e2b.dev/network/internet-access) 和 [公开访问限制](https://docs.e2b.dev/network/restrict-public-access)。
- 整个 VM 是安全边界。内部子进程用于性能和普通故障控制，并不阻止同局恶意策略干扰其他蛇或裁判；这是当前接受的取舍。普通 Python 标准库可用，文件写入只影响一次性 VM。当前未实现反作弊、代码内容审核或策略间强隔离。
- 内部子进程：加载最多 2 秒；每次决策 200ms CPU、1 秒墙钟；地址空间 256MiB、文件 1MiB、文件描述符与用户进程数有限制。这些限制防普通脚本失控，不是替代 VM 的安全保证。策略异常/非法方向/反向/超时会淘汰该蛇；不会把普通故障当作整场平台失败。
- E2B 命令等待最多 80 秒，VM 生命周期最多 120 秒，Java 控制任务最多 130 秒。所有输出最多 8MiB，stderr 最多 64KiB。达到整场预算视为任务失败；长耗时策略可能无法跑满 2000 回合。
- 正常、失败、结果损坏都会执行销毁。上传代码前记录沙箱 ID 到主机临时目录 `parseltongue-e2b-<job-id>.json`；Java 强制终止控制器后通过这个 ID 再次回收。无法确认销毁时不接受结果，保留记录；创建请求丢失响应、主机掉线等情况由 E2B 到期销毁兜底。不会自动续期或暂停。
- 主机限制返回大小，检查请求种子、席位、规则、回合数，并通过 `verify_replay` 重新演算每个已记录动作，拒绝不一致结果。此过程不导入或执行策略。它证明记录符合规则，不证明动作没有被同局恶意程序篡改。
- 模型调用前检查密钥和 SDK 是否配置；这不是云端连通性保证。云端拒绝、超时、结果损坏和回收失败不会触发模型“修代码”，也不会降级本机运行。失败详情只留在私有日志/`.diagnostic`，玩家仍只看到任务状态。
- `.validation` 记录源 SHA-256、E2B 模板名、运行策略版本和检查结果，便于诊断，不是防篡改签名。旧 READY 蛇的比赛也必经 E2B；当前不依赖旧验证凭据授予本机执行权限。

## 测试

```powershell
cd match-worker
../.local/e2b-venv/Scripts/python.exe -m unittest discover -s tests -v
```

`test_generated.py` 用假的 SDK 验证任务边界、断网配置、单次提交、超时/输出上限、销毁与补偿回收、回放篡改拒绝。Java `StrategyTests` 模拟沙箱结果，验证生成/修复/归属/持久化，不调用付费云服务。

`test_e2b_runtime.py` 在 Linux 上实际启动子进程，运行仓库内固定测试样例，覆盖 36 项检查、标准库、状态保留、加载失败、死循环、噪声输出、四蛇对局和故障淘汰。Windows 自动跳过这组测试；可在已有 Ubuntu/CI 中从 `match-worker` 执行同一 unittest 命令。测试绝不读取 `.local/snakes`。

真实 E2B 验收需要密钥，会创建两个短时沙箱（一份候选检查、一场比赛）：在终端配置 `E2B_API_KEY`，设置 `RUN_E2B_TESTS=1`，执行：

```powershell
cd match-worker
../.local/e2b-venv/Scripts/python.exe -m unittest discover -s tests -p test_generated.py -v
```

未设置开关时明确跳过云端测试。离线测试通过不代表 E2B 账号、网络、模板和配额已经可用。
