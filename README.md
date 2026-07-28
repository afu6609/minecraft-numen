<div align="center">

# Numen · 言出法随

### 让大模型真正住进 Minecraft 的 AI 工程框架（Harness）

*言出法随（yán chū fǎ suí）——你说出口，它便成真。*

[English](README_EN.md) · [**简体中文**](README.md)

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1%20~%2026.1.2-62B47A?style=flat-square)
![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-DE7C36?style=flat-square)
![Java](https://img.shields.io/badge/Java-17%20%7C%2021%20%7C%2025-007396?style=flat-square&logo=openjdk&logoColor=white)
![License](https://img.shields.io/badge/code-LGPL--3.0-A8731E?style=flat-square)

[**Momo 服务器分支**](#momo-服务器分支) · [**愿景**](#愿景) · [**它是怎么做到的**](#它是怎么做到的) · [**通达**](#通达) · [**快速开始**](#快速开始) · [**常见问题**](#常见问题) · [**能做什么**](#能做什么) · [**生态**](#生态) · [**给开发者**](#给开发者) · [**路线图**](#路线图)

</div>

<p align="center">
  <img src="docs/numen-demo.gif" alt="Numen 实机演示：砍树 · 挖矿 · 合成 · 战斗 · 联动 Mekanism" width="640">
</p>

---

## Momo 服务器分支

> 当前分支：`momo/server-agent`
>
> 基于 [Dwinovo/minecraft-numen](https://github.com/Dwinovo/minecraft-numen) 的 Forge 1.20.1 分支继续开发；假玩家身体、寻路、工具、调度器与美术资源均来自 Numen。本分支保留上游仓库、版权与许可证信息，并尽量通过公开接口扩展，方便继续合并上游更新。

这个分支把高层 Agent 从玩家客户端搬到了 Minecraft 专用服务器旁边：即使所有真人都下线，Momo 的身体、记忆与大脑仍然可以留在世界中运行。模型由独立的 `server-brain` 进程通过回环 MCP 驱动，Minecraft 主线程不会等待模型响应。

```text
原版 / Pojav / 安装 Numen 的客户端
                 │
              普通聊天
                 ▼
Forge 1.20.1 专用服务器
  Numen 假玩家 + 感知 + 工具 + 任务调度器
                 │
       仅本机 127.0.0.1 MCP
                 ▼
server-brain
  Codex SDK + 聊天路由 + 记忆 + 经验记录 + 策略
```

### 当前已经完成

- **无人在线也能运行。** 专用服务器可以恢复已登记的同伴，并在没有 owner 客户端的情况下直接感知和操作世界。
- **普通聊天就是入口。** 监听服务器聊天，由小模型先判断 `ignore / reply / act`；只有确实需要回应或行动时才唤醒游戏 Agent。
- **复用 ChatGPT/Codex 订阅登录。** `server-brain` 使用 Codex SDK 和 Codex CLI 的 ChatGPT 登录缓存，不要求把 OpenAI API Key 放进 Minecraft 客户端。
- **服务端完整感知。** 可以读取在线玩家的生命、饥饿、位置、背包与装备，并观察玩家附近的方块、实体和环境。
- **真实执行游戏动作。** 移动、跟随、挖矿、砍树、采集、合成、建造、容器交互、近战与远程战斗仍走 Numen 的假玩家身体和任务调度器。
- **可续跑、可局部修正的结构工作流。** 大型建造会保存精确蓝图和版本号；每个检查点后对照真实世界，放置失败时先预检支撑、朝向、占位与可站位置，再用带 revision 校验的局部 patch 修正，而不是重发整栋建筑或盲目重试。
- **客户端可选。** 未安装 Numen 的原版 Java 客户端与 Pojav 手机端可以正常加入、看到 Momo 行动并在普通聊天中交流；安装匹配版本的客户端后，还可使用名册、物品页、HUD 等增强界面。
- **MCP 仅监听回环地址。** 高权限工具默认不暴露到公网，外部大脑与 Minecraft 都运行在同一台受控服务器上。

架构、权限边界和 Skill 晋级设计见 [`docs/momo-server-agent.md`](docs/momo-server-agent.md)。对应的引擎改动位于 [afu6609/numen-api 的 `momo/server-agent` 分支](https://github.com/afu6609/numen-api/tree/momo/server-agent)。

### 当前限制

- 客户端原有的“模型 / MCP / Skill”设置管理的是**客户端内置大脑**，尚不能修改服务器上的 `server-brain`。
- 不建议在服务器 Agent 工作时从 `G` 面板聊天页启动客户端内置大脑；在控制权握手完成前，两套大脑可能同时向同一个身体下发任务。普通服务器聊天不受影响。
- 经验存储、候选 Skill 数据结构与验证策略已经建立，但“任务成功后自动归纳 → 自动复用 → 达标晋级”的完整闭环仍在开发中，不能把一次成功等同于以后必然成功。
- 当前重点是小型私人 Forge 1.20.1 服务器；长时间稳定性、更多模组和更多客户端环境仍需继续实测。

### 当前分支的首次测试

1. 启动 Forge 服务端和 `server-brain`。
2. 用准备作为 owner 的账号进入服务器。
3. 在游戏聊天栏执行 `/numen player summon momo`。
4. 如果希望 Momo 拥有 OP 权限，在服务器控制台执行 `op momo`。
5. 先发送“桃桃你好”验证普通聊天，再逐步测试状态查询、跟随、砍树、挖矿和战斗。

离线模式服务器会根据玩家名生成 UUID；owner 召唤完成后不要随意更换玩家名，否则会被服务器视为另一个玩家。

### 下一步：客户端服务器管理模式

后续会改造匹配版本的客户端插件。客户端连入支持该协议的服务器后，将自动显示“服务器大脑”模式，通过经过 owner/OP 校验的游戏网络包管理真正运行在 Ubuntu 上的 Momo，而不是另起一套本地大脑。计划包括：

- 查看服务器 Agent、Codex 登录、MCP 与当前任务状态；
- 选择聊天分类模型、执行模型和推理等级；
- 管理回应规则、玩家权限与危险操作策略；
- 查看、启停和编辑服务器 Skill，并检查候选 Skill 的验证次数与失败记录；
- 管理经服务端允许的外部 MCP，查看工具清单与连接状态；
- 查看记忆、经验、运行日志，并提供明确的停止任务与释放控制入口。

模型凭据和高权限 MCP 不会下发到客户端。未安装本分支、或完全不安装 Numen 的玩家仍可正常加入和聊天。

> 以下内容继续保留 Numen 上游项目的完整介绍。上游默认运行方式是“owner 客户端内置大脑 + 玩家自己的 API Key”；本分支新增的“专用服务器大脑”是并存的第二种运行方式。

---

我们已经有了会聊天、会写代码、会推理的 AI。但它们都活在一个对话框里——没有身体，没有世界，做完一件事，转头就忘了自己干过什么。

**Numen 想把大模型从对话框里放出来，让它真正"活"在一个世界里。** 而 Minecraft，就是离"一个完整世界"最近的地方。

给它一具会挖矿、会战斗、会搭红石的身体；给它一双看得见矿脉走向、也看得穿机器内部的眼睛；再给它一句——**言出法随**。

你说"去挖一组铁回来"，它就真的下矿、寻路、挥镐、满载而归，回头还问你要不要顺手熔了。你说"在我站的地方盖个小屋"，地基就一块块垒起来。**你说出口的每一句话，都会在世界里变成真实发生的事。** 用你的模型会的任意语言。

```
你：    挖一组铁矿回来
Numen： 这就去。下矿找铁。
        ▸ 4 步 · locate_biome · move_to · auto_mine · collect_items   ✔
Numen： 拿到 64 个粗铁——要我熔了吗？
```

## 愿景

让 AI 玩通原版，只是起点。

Minecraft 真正的宇宙在模组里：机械动力（Create）的齿轮传动、应用能源（AE2）的存储网络、Mekanism 的工厂流水线……成千上万的玩家把无数个小时浇灌进这些系统。**Numen 的终极目标，是让 AI 通达这整个宇宙**——不只是看得懂，而是能亲手玩转：你说一句"给我建一套自动化铁工厂"，它能跨着机械动力和 AE2 把它真正搭出来。

这条路很长，我们才刚上路。但方向，无比清晰。

> **言出法随 / 通达** —— 你的意图直达世界，AI 的能力直达每一个模组。

## 能做什么

<table>
  <tr>
    <td width="50%"><img src="docs/showcase/plan.png" width="100%"><br><b>🧠 详细规划</b> · 逐步拆解任务</td>
    <td width="50%"><img src="docs/showcase/pathfinding.png" width="100%"><br><b>🔭 感知与寻路</b></td>
  </tr>
  <tr>
    <td><img src="docs/showcase/combat.png" width="100%"><br><b>⚔️ 原生战斗</b></td>
    <td><img src="docs/showcase/interact.png" width="100%"><br><b>🧩 模组兼容</b> · 图中为 Mekanism</td>
  </tr>
</table>

给它一个意图，它会自己拆成几十步动作、一口气干完——规划路线、选对工具、判断距离、随机应变，全程不用你盯着。

- ⛏️ **干真活**——挖矿、伐木、采集、建造、精确放置与破坏、照配方手搓合成、用熔炉熔炼、把战利品分门别类塞进箱子。
- 🧭 **真走位**——服务端寻路引擎：会搭桥、垫脚、搭柱上升、挖隧道、下挖楼梯、游泳。"去那个坐标"就是字面意思——哪怕一路挖到钻石层，或者从深井底自己凿一条路回地面。
- ⚔️ **真战斗**——原生玩家近战与弓箭，真冷却、真暴击；受伤会自己吃东西，快淹死会自己游上岸。
- 🔭 **真感知**——扫方块、扫实体、查状态、查配方、定位任意结构与群系，甚至不开 GUI 就透视一台机器的内部。
- 🧠 **真记性**——对话跨存档持久化、太长会自动压缩；它记得用过的工作台、熔炉、箱子，下次直接走回去，而不是重造一个。死了也能恢复：原版死亡照常掉落，缓一会儿在你身边重生。

近三十个这样的"工具"，拼成它此刻的双手与双眼。而它的本事还能继续往上长——靠的正是[通达](#通达)的那两件法宝：

- 📖 **写一篇 Skill，调教它。** `config/numen/skills/` 下一篇篇 Markdown 工作流，相关时才加载、让提示词始终精简。开箱即带一整套通往屠龙终局的攻略（下界、烈焰棒、末影珍珠、要塞、龙战……）。改一篇、或自己写一篇，就能把你基地的规矩、或一个新 mod 的玩法，亲手教给它。
- 🔌 **接一个 MCP，扩张它。** 一个兼容模块，把某个 mod 的内部世界结构化地接进它的感官与双手——让它"能做什么"的边界，随整个模组生态一起生长。

两者叠着用，它就能从玩通原版，一路走向玩转整个模组宇宙。

## 它是怎么做到的

你聊天的那个同伴，只是这套系统穿上的一具身体。让"言出法随"成立的，是它底下的工程——我们叫它 **Harness**。

在 AI 工程里，harness 指的是模型外面那层脚手架：它把一个模型，接上一个能感知、能行动、能从结果里学习的世界。**Numen 就是为 Minecraft 打造的这层脚手架。** 它由四部分构成：

- 🧍 **身体——一具真玩家。** 同伴是服务端的*假玩家*（`ServerPlayer`），每个动作都走原生玩家代码路径。这意味着它天生就和红石、怪物 AI、容器、以及别人的 mod 站在同一套规则里。
- 👁️ **眼睛——感知 API。** 自身与世界的状态、范围方块与实体扫描、配方查询、单块检视，乃至**不开 GUI** 就读出一台机器**装着什么**（物品、流体、能量）的能力化透视。
- ✋ **双手——行动 API。** 移动、挖矿、放置、战斗（原生近战 + 弓）、驱动任意容器/机器 GUI、管理背包、定位结构与群系。
- 🔁 **会教学的反馈回路。** 每一次工具返回——无论成功还是失败——都被写成**教模型"Minecraft 怎么玩"**的一句话。"徒手挖不动铁矿——至少装备一把石镐"，就是这套回路在干活。这正是 agent 的本质：用工具在环境里拿到**真实反馈（ground truth）**，再据此决定下一步。模型，就是这样一路学会玩的。

在这一切之上，**大脑跑在你自己的机器上**：agent loop 在 owner 的客户端、用 owner 的 API key 调 LLM，每个玩家各付各的用量，服主不必替所有人买单，你也不必上交 key。而且它**零第三方运行时依赖**——LLM 传输只用 JDK 的 `HttpClient` + Gson（毕竟 Java 的 AI 生态，你懂的，我只能手搓）。

## 通达

让 AI 玩通原版只是开胃菜。我们真正想啃下的，是模组——这才是 Minecraft 的深水区。

好在 Numen 的身体是个真玩家，所以*物理上*它和模组天生合得来：模组的机器、箱子、机关，它照样能挖、能放、能右键、能从槽位里掏东西，**不必为谁单独写适配**。它甚至能隔着外壳，一眼看穿大多数机器、储罐、电池里装了多少物品、流体和能量。

但"伸得出手"不等于"看得懂"。AI 知道机械压印机是干嘛的吗？知道一张 AE2 网络该怎么连吗？把这份"看懂"交付给模型，靠的是两件法宝——它们恰恰也是 Claude 自己用来通达真实世界的两样东西：

- 🔌 **MCP——接通。** 一个兼容模块，把一个 mod 的内部世界结构化地接进 AI 的感官与双手。用官方的比喻：这是**递给它一把锤子**。
- 📖 **Skill——调教。** 一篇纯文本的[工作流](#能做什么)，零代码、人人能写，教 AI 怎么把能力使到点子上。这是**教它怎么抡这把锤子去钉钉子**。

一个给能力，一个给手艺——更妙的是，**它们能叠着用**：

> 有些 mod，一篇 Skill 就够了：原版的双手已经够它伸，它缺的只是一份说明书。
> 而机械动力、AE2、Mekanism 这种自成一个宇宙的大块头——先用 MCP 把它接进来，再用 Skill 教会它怎么玩。**接通 + 调教，合体即通达。**

你叫得出名字的每一个 mod，AI 都能玩转。这是大饼，但每一块砖都在往上垒。

## 快速开始

1. **安装** mod（Fabric 端另需 [Fabric API](https://modrinth.com/mod/fabric-api)），启动一次。
2. **填入 API key。** 按 **`G`** → **设置**，选 provider，粘贴你自己的 key（OpenAI、DeepSeek、Kimi、Qwen、豆包…… 任意 OpenAI 兼容后端都行）。
3. **召唤一个同伴。** 点面板左栏的 **`+`**，给它起个名字，回车。
4. **点它的头像开聊**，把要做的事告诉它。剩下的，交给它。

> 按 `G` 的面板有三页：**Chat**（聊天 + 实时计划面板）、**Items**（一张仿原版背包的只读角色卡）、**Settings**（填 key 和模型）。左栏就是同伴名册——点头像切换、点 **`+`** 召唤、点 **`✕`** 注销，基本不用敲指令。左边缘还有个小头像 HUD——它说话时，头像和气泡会一起滑出来。

## 常见问题

**要花钱吗？** Numen 开源免费。它调用大模型用的是你自己的 API key，各付各的用量；用便宜的模型（如 DeepSeek）一次典型任务通常只花几分钱。

**用哪个模型好？** 任意 OpenAI 兼容后端都行。想省钱：DeepSeek、Qwen、Kimi；想要最聪明：Claude、GPT。模型越快越聪明，同伴表现越好。

**我的 API key 安全吗？** 大脑跑在你自己的客户端，key 只存在本地、只用于直连你选的模型后端，不经过任何第三方服务器，也不会上传给作者。

**联机服能用吗？** 能。同伴是服务端的真玩家，动作走原生玩家代码、由服务端逐一校验，你只能驱动自己的同伴。服务端只需装 Numen，客户端各自填各自的 key。

**它会拆我家、乱来吗？** 它只做生存里一个真玩家能做的事，且每个动作都归属校验到它的主人——不会凭空造物，也不碰不属于你的东西。

**响应有点慢？** 每走一步都要过一次大模型推理，模型越快体验越顺；这块仍在持续优化。

## 生态

**Numen**（[minecraft-numen](https://github.com/Dwinovo/minecraft-numen)，本仓库）是那个 mod——AI 同伴本体，跑在 **[numen-api](https://github.com/Dwinovo/numen-api)** 引擎上（经 **[numen-maven](https://github.com/Dwinovo/numen-maven)** 发布），引擎对外开放一套小巧的公共 API。两类东西建在它之上：

**扩展一个同伴**——同伴自己的大脑仍然做主：
- **桥（Bridge）** 把一个外部渠道接进同伴：消息进来，同伴自己决定怎么做。基于 `NumenGateway`。→ **[numen-qq-bridge](https://github.com/Dwinovo/numen-qq-bridge)**（QQ），后续还有更多。
- **技能（Skill）** 教同伴怎么做事——markdown 注入它的上下文。随 Numen 内置，或社区编写。
- **社区 Addon** 为同伴增加面向特定玩法的工具和技能。→ **[Numen Creative](https://github.com/venti0824/numen-creative)**（非官方社区扩展；Minecraft 1.20.1 Forge、Numen 0.0.7、Java 17）

**把 Numen 暴露出去**——把操控权交给外部大脑：
- **[numen-mcp](https://github.com/Dwinovo/numen-mcp)** 是一个 Model Context Protocol 服务器：任意外部智能体（比如 Claude）直接驱动同伴。基于 `NumenActuator`。

## 给开发者

Numen 出厂的每一个工具、每一篇技能，全部只用公共 API 写成，没有任何私有通道。引擎（[numen-api](https://github.com/Dwinovo/numen-api)）拆分出来之后，任何 mod 作者都拿得到同一份能力：

- 🔧 **通过 `NumenGateway` 注册一个工具**，你 mod 的能力就长在了 AI 的手上。工具契约里刻意**不含任何 Minecraft 概念**——怎么完成调用（同步、异步、自己发包、调外部网络服务）完全由工具自己做主。正因如此，同一套 API 伸向 Discord、QQ、MCP，和伸向一条矿脉一样顺手。
- 📖 **随 jar 附带技能**——一句调用，就把你 jar 里的 `/skills` 目录变成内置技能，玩家装上你的 mod，AI 自动学会怎么玩它。
- 🏗️ **或者，造一个完全不同的 AI**——同一块地基上，AI NPC、剧情角色、服务器管家，随你想象。

```gradle
repositories { maven { url = 'https://raw.githubusercontent.com/Dwinovo/numen-maven/main' } }
dependencies  { modImplementation "com.dwinovo.numen:numen-api-fabric-1.21.1:<version>" }
```

面向集成的公共对接 API 采用 **MIT** 授权——写工具、写技能、写兼容，不必被 LGPL 牵着走。上手指南、完整示例与版本矩阵，见 [numen-api 的 README](https://github.com/Dwinovo/numen-api)。

## 参与共建

这是一张敞开的蓝图，欢迎一起垒砖：

- 🐛 **提 issue / PR。** Bug、点子、兼容实验都欢迎——在 [issues](https://github.com/Dwinovo/minecraft-numen/issues) 开个帖子。
- 📖 **写一篇技能（Skill）。** 一篇 Markdown 工作流就能教 AI 玩一套新东西，零代码、人人能写。写好提 PR，就能进社区技能库。
- 🔧 **写工具 / 桥 / MCP。** 对着公共 API（[numen-api](https://github.com/Dwinovo/numen-api)）写，任何 mod 作者都能把自己的能力接上 AI 的手。
- 🏗️ **自己构建。** 克隆仓库，`./gradlew :fabric:build`（或 `:neoforge:build`）；架构与完整工具清单都在 `common/src/main/java/com/dwinovo/numen/` 里。

## 路线图

Numen 还很年轻。原版玩法已经跑得很顺；而"让 AI 通达整个模组宇宙"的故事，正一行行被写出来。我们想去的地方：

- **为大模组接通（MCP）。** 机械动力、AE2、Mekanism 这些自成宇宙的科技 mod，专门的 MCP 兼容模块会把它们的内部结构接进 AI 的感官与双手。`inspect_block_storage` 那一眼透视，是第一块砖。
- **长成一座技能图书馆（Skill）。** 让"教 AI 玩一个新 mod"简单到只需写一篇 Markdown，由社区共建共享——和 MCP 叠着用，就能把 AI 从"接通"一路带到"精通"。
- **越玩越像个老玩家。** 更深的世界记忆与长程规划。

欢迎贡献代码、提交技能、做兼容实验。这是一张敞开的蓝图——**也是一份正在被兑现的邀请函。**

---

<div align="center">

<sub>想自己构建、看完整工具清单或架构设计？都在源码里——从 <code>common/src/main/java/com/dwinovo/numen/</code> 看起。</sub>

<sub><b>授权</b>：源代码采用 <a href="LICENSE">LGPL-3.0</a>——你分发的修改版必须以同协议继续开源。面向兼容模块 / MCP 桥接的<b>公共对接 API</b>采用 <a href="LICENSE-API">MIT</a>，让任何人都能自由地写 mod 兼容。美术与资源为 <a href="LICENSE-ASSETS">保留所有权利</a>，"Numen" / "言出法随" 名称亦予保留。基于 <a href="https://github.com/jaredlll08/MultiLoader-Template">MultiLoader Template</a> 构建。</sub>

<sub>寻路的<b>规划层</b>基于启发式搜索文献实现：加权 A*、预算化的部分路径提交、以及 RTAA* 式跨段启发学习（Koenig &amp; Likhachev, <i>Real-Time Adaptive A*</i>, AAMAS 2006），附独立于游戏的单元测试。<b>路径跟随层</b>（沿计划路径的进度追踪与偏差恢复）参考机器人学与游戏 AI 的路径跟随文献：Pure Pursuit 前瞻点跟随（Coulter, <i>Implementation of the Pure Pursuit Path Tracking Algorithm</i>, CMU-RI-TR-92-01, 1992）、参数连贯（coherence）定位（Millington, <i>AI for Games</i>），以及 Recast/Detour 走廊跟随与 ROS 2 Nav2 进度检查的工业实践。<b>执行层</b>与 <a href="https://github.com/cabaletta/baritone">Baritone</a> 的根本区别在于运行位置：Baritone 是纯客户端模组、操控本机玩家；Numen 驱动的是<b>服务端假玩家</b>，移动/挖掘/放置全部经服务端 API 实现——设计思路上借鉴了其公开机制，<b>未复制、移植或改写其任何源码</b>。本项目代码采用 LGPL-3.0 属自主选择，与 Baritone（同为 LGPL-3.0）无衍生关系。</sub>

</div>
