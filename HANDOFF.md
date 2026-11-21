# AiCode New 开发交接

AiCode New 是 `jieapi/aicode` 的 fork，与上游可按包名共存安装。

## 仓库与身份

| 项 | 值 |
| --- | --- |
| 仓库路径 | `~/workspace/aicode-new` |
| 分支 | `main`（保留上游历史，`origin` 仍指向上游，尚未配置自有远端） |
| 应用名 | AiCode New |
| applicationId | `com.aicodenew`（debug: `com.aicodenew.debug`，beta: `com.aicodenew.beta`） |
| namespace | `com.aicode`（刻意与源码包一致，避免改 155 个文件的 R 引用） |
| 版本名 | `1.0.<git 提交数>-dev`，打自有 `v1.0.x` tag 后自动沿用 tag |
| versionCode | git 提交数（随提交单调递增） |

## 构建环境

容器内已配置好，无需重复安装：

- JDK 21：`/usr/lib/jvm/java-21-openjdk-amd64`
- Android SDK：`/root/android/sdk`（platforms `android-36`、`android-37.0`；build-tools `35.0.0`、`36.0.0`、`37.0.0`）
- `app/local.properties` → `sdk.dir=/root/android/sdk`
- **内存限制**：本机仅约 3.5GB。`~/.gradle/gradle.properties` 已设为 `-Xmx1700m -XX:MaxMetaspaceSize=640m -XX:+UseSerialGC`、`workers.max=2`。仓库内 `gradle.properties` 的 7168m 是给 CI 的 16GB runner 用的，本机直跑会被 OOM 杀掉。

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
       ANDROID_HOME=/root/android/sdk ANDROID_SDK_ROOT=/root/android/sdk

./gradlew :app:assembleUniversalDebug --no-daemon --max-workers=2      # 冒烟编译
./gradlew :app:testUniversalDebugUnitTest --no-daemon --max-workers=2  # 单元测试
./gradlew :app:assembleUniversalRelease --no-daemon --max-workers=2    # 正式包（R8，7~15 分钟）
python3 scripts/check_migrations.py                                    # 迁移对账
```

## 发版

1. `assembleUniversalRelease` 产出 `app/build/outputs/apk/universal/release/app-universal-release.apk`
2. 复制到 `~/workspace/aicode-new-<versionName>-release.apk` 交付
3. 核对：`aapt2 dump badging`（包名/版本/应用名）、`apksigner verify`（签名）

**签名密钥**：`app/aicode-new.jks` + `app/keystore.properties`，两者都已 gitignore。务必保留，换密钥会导致后续版本无法覆盖安装。

## 已完成的改动

### 上游同步
2026-11-21 合并上游 v1.13.0 之后的 22 个提交（合并提交 `b00f59f0`）。带进来的主要能力：插件三轴架构（执行后端 / Agent 工作流 / 外部会话源）、宿主侧 UDS 二进制多路复用通道、设置页跨工作区会话管理与内容查看、多 Key 分别覆盖 Base URL、日志诊断工作台、帮助与赞助页、GitHub Star 弹窗、终端未初始化容器报错。数据库升到 **v65**（新增 64/65 两个迁移），上游本次没有改构建配置与依赖，我们的技术栈升级没有被回退。

冲突处理要点：`values-en` 一律删除（保持无英文资源）；不恢复已删掉的 `LanguageSettingsRepository`；`AgentRunManager` 取上游 `finishCommand` 重构 + 保留本地 `publishActiveSessions()`（上游已把斜杠命令改由 `SlashCommandRegistry` 分流，原有的提前 return 要跟着删）；`SettingsViewModel` 双方向新增都保留；README 保留开源协议声明与 AiCode New 品牌，同时并入上游赞助段落。

### 技术栈（升级到当时最新稳定）
Gradle 9.9.1 / AGP 9.5.1（内置 Kotlin，已迁移，不再应用 `kotlin-android`）/ Kotlin 2.4.21 / KSP 2.3.14 / Hilt 2.60.1 / Compose BOM 2026.09.00 / Room 2.8.5 / Retrofit 3.0.0 / OkHttp 5.5.1 / Vico 3.3.1（包路径迁到 `compose.*`）/ BouncyCastle 1.87 三件套对齐 / compileSdk 37 / build-tools 36 / JDK 21。

### 功能
- **更名为 AiCode New**：应用名与界面文案、官网、文档站、README/NOTICE、插件模板。
- **独立包名**：`com.aicodenew`，可与官方 AiCode 同机共存。
- **退出前二次确认**：偏好设置新增「退出」分组，默认开启；任务运行中额外提示会被中断。
- **模型弹层直达 AI 供应商**：弹层右上角「添加供应商」，进去后返回直接回首页且不弹侧边栏。
- **运行模式中文化 + 长按设默认**：底部按钮显示「构建 / 计划 / 自动」；长按设置之后新会话的默认模式，当前会话不变。
- **纯中文界面**：思考强度等去掉英文标注；删除英文资源与语言切换入口，界面固定中文。

## 约定（改动时遵守）

- 新增用户可见文案写进 `app/src/main/res/values/strings.xml`（**已无 `values-en`**，不需要英文）；合并上游时上游新增的英文串直接丢弃
- 品牌替换只动用户可见文案，不要动代码常量、请求头、URL、包名。上游的 `X-AiCode-Session-Id` / `X-AiCode-Operation-Id` / `X-AiCode-Subscribe-Only` 曾被误改成 `X-AiCode New-...`，与 `PluginProviderRegistrar.SESSION_HEADER_NAME` 不一致，已在合并时修正
- UI/功能变化同步更新 `docs-site/docs/`（`guide/general.md`、`guide/chat.md`、`guide/modes.md`、`guide/appearance.md`、`guide/overview.md` 索引）
- 新增偏好项要同时接入 `BackupSnapshot` 与 `BackupManagerImpl`（快照 + 恢复）
- 改完跑冒烟编译 + 单元测试，再出正式包

## 已知问题

- `FileChangeHubTest` 偶发失败（测试间共享静态观察者状态未清理），与业务改动无关，单独重跑通常通过。
