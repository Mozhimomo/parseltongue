# 四蛇规则引擎与测试 AI

Python 3.10+；规则引擎只依赖标准库，生成策略执行另需 `requirements.txt` 中的 E2B SDK。实现同步移动、身体/蛇头碰撞、进食增长、饥饿、最后存活者获胜、同时全灭和回合上限，以及可重算的 JSON 回放。

## 运行与复盘

在项目根目录执行：

```powershell
python match-worker/run_match.py --seed 42 --output match-worker/outputs/seed-42.json
python match-worker/run_match.py --verify match-worker/outputs/seed-42.json
```

可指定四个内置 AI 和回合上限：

```powershell
python match-worker/run_match.py --ais greedy forager cautious straight --seed 7 --max-ticks 500 --output match-worker/outputs/seed-7.json
```

不传 `--output` 时向标准输出写完整回放，供 Java 游戏服务消费。`--verify` 仅通过已记录动作重算引擎，不执行 AI；数据文件只用 JSON 解析。

## AI 桩

四个函数位于 `parseltongue_game/stubs.py`，统一接口为 `decide(observation: dict) -> str`；实际函数名与注册名如下：

| 注册名 / 函数名 | 行为 |
|---|---|
| `straight` | 保持方向，故意保留容易撞墙的行为，便于测试淘汰 |
| `cautious` | 优先直行，前方被占用或越界时换方向 |
| `greedy` | 选择朝最近食物靠近的可行方向 |
| `forager` | 广度优先搜索食物，评估空间并躲避对手可能到达的蛇头格 |

输入包含 `self`、四条蛇的 `snakes`、`board`、`food`、`hunger_rules`、逻辑回合和版本。身体坐标从头到尾，坐标原点左上。所有输入都是副本；输出仅允许 `UP/RIGHT/DOWN/LEFT`。返回字典或反向移动会使该蛇故障淘汰。

**生成代码只在 E2B 中加载：一个候选检查或一场比赛一个沙箱，整场比赛在沙箱内完成。** `strategy_job.py` 是主机上的可信 SDK 控制器，`e2b_runtime` 是上传到沙箱的执行器，`generated_job.py` 仅作兼容入口。只有内置 AI 的比赛仍可本机执行。不得将生成代码加入 STUBS 或导入本机引擎。配置和测试见 [sandbox/README.md](sandbox/README.md)。

## 规则与回放

默认 20×20、初始长度 3、5 个食物；饥饿初值/上限 100，每回合减 1，进食恢复 40 并增长 1。饥饿在移动和进食之后结算，剩 1 时当回合进食仍可存活。

不进食时释放尾格；进食时保留尾格；动作故障的蛇本回合身体仍占位。头碰头和交换蛇头位置均死亡；本回合死亡身体在整轮后移除。所有动作从同一局面采集，然后同时裁决。

最后活蛇胜，其余败；最后一轮正常全灭者平局，之前淘汰者及脚本故障者败。到达回合上限仍多蛇存活，整场 `NO_CONTEST`，不按长度判胜。默认技术上限 2000，网页试跑默认 500，可调整。

回放包含规则、种子、AI 席位、逐回合快照、采用的动作、进食/淘汰事件与结果。同一版本引擎、同一 Python 运行环境、相同种子和内置策略可复现；回放不保证跨引擎版本兼容，也不是带签名的比赛认证凭证。

## 测试

```powershell
cd match-worker
python -m unittest discover -s tests -v
```

完整 HTTP 链路测试需要已运行的开发网关、认证和竞技服务；下列脚本会创建一个随机测试账号，完成试跑后注销其会话。仅在测试数据库运行：

```powershell
python match-worker/smoke_gateway.py --gateway http://localhost:8080
```
