# CoreProtect Neo

## !!!WARN:该 mod 由 AI 制作!!!

English documentation: [README-en.md](README-en.md)

> CoreProtect 24.1 的 NeoForge 26.2 移植版：操作记录、查询与回滚。
>
> 数据库表、许多配置与指令名称沿用原版。NeoForge 元数据编码与 Bukkit 对象数据不通用。

---

## 一、这是什么

原版 CoreProtect 是 Paper/Spigot 插件，本项目把它移植到 NeoForge 平台：

- 改编原版数据模型：数据库表名、主要字段、语言短语和配置键沿用原版，物品与方块实体元数据使用 NeoForge 编码
- 替换平台层：Bukkit 事件改为 NeoForge 事件，Bukkit 的世界/物品/方块 API 改为 Minecraft/NeoForge API
- 指令反馈主要使用原版 Phrase 词条与 lang 语言文件

modid：`coreprotect`　版本：`24.1-neoforge-26.2`　包名：`net.coreprotect`

---

## 二、环境要求

| 项目 | 要求 |
|---|---|
| Minecraft | 26.2 |
| NeoForge | 26.2.0.88 |
| Java | 25 |
| 服务端 | 必需（所有记录与指令都在服务端执行） |
| 客户端 | 可选（无客户端专属功能） |
| 数据库 | 内置 SQLite（默认），可选 MySQL |

---

## 三、安装与数据目录

1. 把 `coreprotect-24.1-neoforge-26.2.jar` 放进服务端 mods 目录；
2. 启动一次服务器，会自动创建数据目录 `coreprotect/`，位置在服务器根目录下，内容如下：

| 文件 / 目录 | 说明 |
|---|---|
| `config.yml` | 配置（键名与原版一致） |
| `database.db` | SQLite 数据库（使用 MySQL 时不生成） |
| `blacklist.txt` | 可选：忽略名单 |
| `lang/` | 可选：自定义词条与翻译 |

3. 修改 `config.yml` 后执行 `/co reload` 或重启即可生效。

---

## 四、与原版的差异

此移植版仍在开发中。构建与 SQLite 测试覆盖方块状态、方块实体快照及回滚／恢复的数据库行状态。隔离的 NeoForge 26.2 服务端测试还验证了箱子物品、方块属性及替换已有方块后的回滚／恢复。测试现有 CoreProtect 数据库前请先备份。DQM 法术、真人指令、MySQL 与 Bukkit 数据迁移尚未验证。

以下功能或兼容性尚未验证：

- WorldEdit 与 FAWE 的改动记录（NeoForge 端无对应插件体系）
- 绕过 NeoForge 玩家方块事件的模组改动（包括 DQM 法术）目前不会被记录；尚未用 DQM 验证
- 旧 Bukkit 方块实体元数据无法由 NeoForge 恢复；缺少可读快照时会跳过该方块实体的放置
- bStats 遥测（移植版不发送任何遥测数据）
- AdvancedChests 兼容
- API 包：`LookupFilter`、`LookupOptions`、`UsernameAPI`，需要把 Bukkit 的 Material 与 World 换成注册名与维度 ID
- 告示牌与头颅的专属记录
- 旧版本数据库升级脚本 2.11 至 2.20，迁移极老数据库时需要重写，相关文件当前保留在 `src/pending-java`
- 检查器目前是右键单点查看历史，原版是可点击分页列表并区分交互与容器页签

---

## 五、构建

```bash
gradlew build
```

产物为 `build/libs/coreprotect-24.1-neoforge-26.2.jar`。

构建环境：

| 组件 | 版本 |
|---|---|
| ModDevGradle | 2.0.148 |
| NeoForge | 26.2.0.88 |
| Gradle wrapper | 9.8.0 |
| JDK | 25 |
| 数据库驱动 | sqlite-jdbc 3.53.0.0、mysql-connector-j 9.1.0、HikariCP 7.0.2，通过 jarJar 打包进模组 |

---

## 六、验证

`gradlew build testmodJar` 运行 SQLite/NBT 契约测试。`node src/test/node/server-smoke.mjs`
在 `build/smoke` 中安装隔离 NeoForge 服务端，测试方块记录、游戏世界中的回滚／恢复，
并重启检查历史记录。服务端测试还检查取消事件不会产生记录，以及数据库暂时不可用后队列可重试。
测试服务端仅监听本机 25586 端口。
