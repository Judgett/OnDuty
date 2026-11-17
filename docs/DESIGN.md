# 下班装死（OnDuty）· 功能与安全权限设计（v2.1.0）

> 交付物：`下班装死.apk`，包名 `com.onduty.gate`，versionCode 4 / versionName 2.1.0，minSdk 33 / targetSdk 34。注意：包名较旧版 com.shiftnotify.gate 已变更，两者无法覆盖安装，需卸载旧版后重装。
> 定位：**只处理工作软件的通知**，不覆盖影院 / 图书馆 / 会议等生活场景。

---

## 0. v2.1.0 相对 v2.0.1 的变更

| # | 用户优化点 | 实现 |
|---|---|---|
| 0 | 移除影院 / 会议场景 | 删除场景卡片、三个场景按钮、全局静默档位；应用退化为单一「定向收起」执行方式 |
| 1 | 工作日与法定工作日合并；节假日安排 | 合并为「法定工作日」一个类别；`Holiday.java` 内置 2026、2027 两年国务院放假与调休数据，**不联网、无 INTERNET 权限** |
| 2 | 下班 1 小时后仍连办公网 = 加班；提前连办公网 = 已抵达 | 两者均按上班放行，顶部状态卡用小字标记「加班中」/「已抵达」，诊断区同步显示 |
| 3 | 时间窗新增休假选项，起始时间可选，默认一天 | 新增 `DAY_VACATION`；点「休假」弹日期 + 时间 + 时长（1/2/3/7 天，默认 1 天）；休假期间全部放行，诊断卡可随时结束 |
| 4 | 长按记录按钮删除网络绑定；点击仍可再次绑定 | 办公 / 家庭两个记录按钮均加长按删除（带确认弹窗），点击照旧记录或覆盖 |

同时修复 v2.0.1 分析报告中的全部 P0 / P1 与部分 P2，见第 8 节。

---

## 1. 需求与语义

| 需求 | 语义 | 触发源 | 动作 |
|---|---|---|---|
| 上班时段开启工作软件通知，下班自动收起 | 时间窗 | 本地时钟 + 法定工作日（内置 2026/2027 表） | 窗内放行，窗外收起目标 App 通知 |
| 到办公地点自动开启，离开后自动关闭 | 地点 | 办公 / 家庭 Wi-Fi 网络指纹（零位置权限） | 办公网放行，家庭网收起，其他网按时间窗 |
| 加班 / 早到 | 地点 + 时间叠加 | 连办公网但在时间窗外 | 一律放行，并标注「加班中」/「已抵达」 |
| 休假 | 临时覆盖 | 用户选定起始时间与时长 | 期间全部放行，优先级最高 |
| 免每天手动开关系统通知 | 自动化 | 上述全部 | 一次性配置后不再碰系统设置 |
| 节假日自动判定 | 内置表 | `Holiday.java` 静态数据（2026/2027） | 不联网；其他年份按周一至周五 |
| 零联网 | 权限白名单 | 不声明 `INTERNET` | 物理上无外传通道 |

**默认策略**：`默认 = 放行`（fail-open）。任何判据缺失或授权撤销，都不会把用户静音一整天。

**判定优先级**：`休假 > 地点 > 时间窗 > 默认放行`。场景层已移除。

---

## 2. 执行方式：只保留定向收起

```java
Guard.MODE_ALLOW   = 0   // 通知原样呈现
Guard.MODE_SILENCE = 1   // 目标 App 通知被撤回 + 无声折叠摘要
```

```java
// NotifyService（NLS）
onNotificationPosted(sbn):
    if mode != MODE_SILENCE return
    if pkg == 自身 或 pkg 不在接管列表 return
    cancelNotification(sbn.key)
    Fold.bump(应用名)            // 只记条数 / 时间 / 应用名，不存正文
```

- 渠道 `IMPORTANCE_LOW`、无声、无振动、锁屏不可见、1.5 秒节流；回到 `ALLOW` 时清除。
- **局限**：NLS 是事后移除，提示音 / 振动可能已触发；系统级不可撤销通知（前台服务常驻、安全提示）拦不住。这些已写进 App 内说明。
- **已移除**：全局静默（勿扰 / `AutomaticZenRule`）、模式 C 渠道静音 + 代理提醒、无障碍、Shizuku、AppOps、Device Owner。
  - 移除全局静默的额外收益：不再受 Android 15（API 35）「targetSdk ≥ 35 不得改全局勿扰状态」变更影响，`targetSdk 34` 不再是长期技术债。

---

## 3. 判定逻辑

### 3.1 地点状态

| 当前网络 | 判定 | Mode |
|---|---|---|
| 办公网命中 | `PLACE_WORK` | `ALLOW`（含早到 / 加班） |
| 家庭网命中 | `PLACE_AWAY` | `SILENCE` |
| 已连 Wi-Fi 但都不匹配 | `PLACE_OTHER` | 按时间窗 |
| 未连 Wi-Fi / 拿不到网关 | `PLACE_UNKNOWN` | 按时间窗 |
| 两处指纹撞车 | `PLACE_OTHER` | 按时间窗 + UI 红字提示 |

**确认与冷却规则（本轮调整后）**：

- **办公网：连上即到岗，零确认**。办公网是强信号——正常只有坐在工位才会连上，等待 2 分钟反而会漏掉刚到岗的通知。`PLACE_WORK` 立即生效，不进驻留确认、不受冷却阻挡。原 `ENTER_MS` 常量已废弃（保留供回退参考）。
- **家庭网：离开确认 5 分钟**。连续命中家庭网满 5 分钟才翻转为 `PLACE_AWAY`。收起比放行更保守，宁可晚收几分钟也不错收工作消息。
- **OTHER / UNKNOWN：立即退出在岗**。连着网但两处指纹都不匹配、或断网 / 拿不到网关，视为「明确不在岗」，立即回落到时间窗判定。
- **网关变化重新计时**：`pl_gw` 一变就清零待定计数，换网不会继承旧状态。

实现见 `Guard.tickPlace()`；可调常量 `LEAVE_MS=5min`、`REST_FIRST_MIN=20`、`OVERTIME_AFTER_MIN=60` 在 `Guard.java` 顶部，当前硬编码、界面不可调。诊断区的「切换确认中：已持续 X 分钟」来自 `pendingMin()`，现在只会对家庭网出现。

**v2.1.0 关键修复**：UNKNOWN 分支不再写 `pl_flip = now`，且网关变化判定去掉 `gwBefore.length() > 0` 前置条件。掉线重连时 `gwBefore=""` 与当前网关不等 → 冷却清零 + 待定计数作废 → 重新计时。不再出现「约 12 分钟回不去办公网」。

### 3.2 下班后一小时的三段规则（仅仍连办公网时生效）

`Guard.offDutyPhase(c, now)` 统一判定，`resolveMode()` 与 `statusTag()` 共用，避免口径分叉。触发前提：`PLACE_WORK && !inWin`（连着办公网且已过时间窗）。

| 距下班时间 | 阶段 | Mode | 顶部标记 |
|---|---|---|---|
| 提前到岗（`minutes < startMin`） | `PHASE_EARLY` | `ALLOW` | 已抵达 |
| 下班后 0–20 分钟 | `PHASE_REST_EARLY` | `ALLOW` | 休息中 |
| 下班后 20–60 分钟 | `PHASE_REST_LATE` | `SILENCE` | 休息中 |
| 满 60 分钟起 | `PHASE_OVERTIME` | `ALLOW` | 加班中 |

- 前 20 分钟放行：留给下班后仍用办公软件联系朋友的场景；后 40 分钟收起：进入休息时间不再提示，但仍算条数、发无声摘要。
- 满 1 小时恢复放行：视为加班，与用户原始定义一致。
- 阈值常量 `REST_FIRST_MIN = 20`、`OVERTIME_AFTER_MIN = 60` 在 `Guard.java` 顶部，硬编码不可界面调。
- 跨零点时间窗：窗外区间 `(end, start)` 同样按 `minutes - end` 计算；距上班不足 2 小时提前归入「已抵达」。
- **未连办公网时不适用**，已离开公司的情形仍按 `PLACE_AWAY` / 时间窗原规则处理。

### 3.3 休假

- 存储：`vac_start` / `vac_end`（毫秒），`vac_end > now` 即激活。
- `resolveMode` 中位于总开关之后的第一优先级，返回 `ALLOW`。
- 点「休假」→ 弹 `dialog_vacation.xml`（DatePicker + TimePicker + 时长 1/2/3/7 天，默认 1 天）→ 写入起止时间并把 `sched_daymode` 设为 `DAY_VACATION`。
- 结束休假 → `clearVacation()` 清时间并把模式恢复为 `DAY_LEGAL`，避免模式滞留导致全天误放行。
- `dayAllowed` 在 `DAY_VACATION` 下按法定工作日处理（兜底），`inWindow` 不再对休假做特殊放行。

### 3.4 法定工作日与内置节假日

- 类别合并：「工作日」与「法定工作日」统一为 `DAY_LEGAL`（编号 3 保持兼容；旧配置编号 1 也按同一逻辑处理）。
- `Holiday.java` 是纯静态内置表，**不联网、不缓存、不申请 INTERNET**。内装国务院办公厅公布的两年数据：
  - **2026**（国办发明电〔2025〕7 号，2025-11-04）：元旦 1/1–1/3、春节 2/15–2/23、清明 4/4–4/6、劳动 5/1–5/5、端午 6/19–6/21、中秋 9/25–9/27、国庆 10/1–10/7；补班 1/4、2/14、2/28、5/9、9/20、10/10。
  - **2027**（国务院办公厅通知，2026-11-05）：元旦 1/1–1/3、春节 2/5–2/13、清明 4/3–4/5、劳动 5/1–5/5、端午 6/9、中秋 9/15、国庆 10/1–10/7；补班 1/31、2/14、5/8、9/26、10/9。
- 其他年份：**不猜测、不套用旧表**，按周一至周五判定；诊断区显示「XXXX 年未内置，按周一至周五」。
- `Holiday.isWorkday(Calendar)` 是唯一切入口，`Guard.legalWorkday()` 直接委托，无联网分支、无 SharedPreferences 节假日键。
- 诊断区显示数据来源（「内置表 2026」/「内置表 2027」/「未内置」）。

**隐私边界**：本应用物理上没有网络能力；无第三方 SDK、无埋点、无崩溃上报、无远程配置。

---

## 4. 工程结构（实际交付）

原设计的 Gradle 多模块 + Kotlin/Compose/Room/WorkManager 在本机构建环境不可用（无 Android Studio / Gradle 依赖解析），落地为等价的单模块实现：

```
app/
├─ AndroidManifest.xml      权限白名单 / 组件 / minSdk 33
├─ build.ps1                aapt2 → javac → d8 → zipalign → apksigner（$PSScriptRoot + UTF-8 BOM）
├─ keystore/shiftnotify.jks 签名证书（沿用，保证覆盖升级）
├─ src/com/onduty/gate/
│   ├─ Guard.java           判定中枢：优先级 / 时间窗 / 法定工作日 / 地点状态机 / 休假 / 加班标记 / 日志 / 诊断
│   ├─ Holiday.java         内置 2026/2027 节假日表（纯静态、零联网）
│   ├─ NetFp.java           网络指纹（网关 + DNS + 网段），零位置权限
│   ├─ NotifyService.java   NLS 通知监听 + 60s tick + 网络回调 + 定向收起
│   ├─ Fold.java            无声折叠摘要（不存正文）
│   ├─ MainActivity.java    状态卡 + 五张卡片 + 休假对话框 + 权限引导
│   └─ AppPickerActivity.java 应用多选（<queries> 限定有启动图标的应用）
└─ res/                     XML 布局 + drawable + colors + strings
```

- 数据层：`SharedPreferences`（`guard2`），无 Room、无加密（不存通知正文，风险面仅应用名 / 网络指纹 / 事件时间）。
- 定时：NLS 服务内 60 秒 tick + 网络回调事件驱动，无 WorkManager、无前台服务。
- 依赖：零第三方库，纯 framework API，可完全离线复现构建；运行期也零联网。

---

## 5. 权限清单（v2.1.0 实际 Manifest，已用 aapt2 badging 核验）

| 权限 | 类型 | 用途 | 是否必需 |
|---|---|---|---|
| `BIND_NOTIFICATION_LISTENER_SERVICE` | 系统特殊授权（手动） | 监听并撤回目标 App 通知 | 必需 |
| `POST_NOTIFICATIONS` | Android 13+ 运行时 | 发无声折叠摘要 | 可拒绝，仅丢摘要 |
| `ACCESS_NETWORK_STATE` | normal | 读取当前网络指纹、判断 Wi-Fi | 地点判据需要 |

**只有这三项，没有第四项。**

**明确未声明的安全相关权限**：

- **`INTERNET`**：完全没有。应用物理上无法联网，无上传 / 统计 / 远程配置 / 广告 SDK。
- **任何位置权限**：不读 GPS、不读经纬度、不读 SSID；只用网关 + DNS 认网络。
- **`ACCESS_WIFI_STATE` / `ACCESS_BACKGROUND_LOCATION`**：不需要。
- **存储读写**：`READ/WRITE_EXTERNAL_STORAGE`、`MANAGE_EXTERNAL_STORAGE` 均未声明；数据只在 App 私有目录；`allowBackup=false`，adb backup 也导不出。
- **通讯录 / 短信 / 通话 / 相机 / 录音 / 无障碍 / 勿扰访问 / `QUERY_ALL_PACKAGES` / 悬浮窗 / 写系统设置**：均未声明。
- **root / ADB / Shizuku / Device Owner**：不需要。

---

## 6. 隐私与安全

- **通知内容不落盘**：`Fold` 只存条数、时间、应用名；日志只存判定变化、网关、应用数量。
- **网络指纹只存本机**：网关 + DNS + 网段，不含 SSID，不上传，不用于设备识别。
- **零网络能力**：`INTERNET` 未声明，无外传通道；无统计、崩溃上报、广告 SDK、远程配置。
- **fail-open**：总开关关闭、授权撤销、NLS 断连、判据缺失 → 一律放行或按时间窗，不静音全体。
- **可审计**：本地日志最多 12 条，可查看 / 清空。
- **组件安全**：`AppPickerActivity exported=false`；NLS 按官方声明 `exported=true` + 权限绑定；`PendingIntent` 使用 `FLAG_IMMUTABLE`；`isListenerEnabled` 逐项解析 ComponentName，不用子串匹配。
- **签名密钥不入库**：`app/keystore/shiftnotify.jks` 与口令仅存于本地，已通过 `.gitignore` 排除，不会上传到公开仓库。仓库使用者需自备 keystore 才能构建签名包。

---

## 7. 后台可靠性

| 风险 | 对策 |
|---|---|
| Doze / 进程被杀 | NLS 由系统绑定托管 + 60s tick + 网络回调；引导用户关闭电池优化；不承诺 100% 准时 |
| 设备重启 | 无 `RECEIVE_BOOT_COMPLETED`，依赖系统重绑 NLS；最坏情况为短时间内判定未刷新 |
| NLS 授权撤销 | `onListenerDisconnected()` → 写日志并回到放行；UI 常驻提示卡 |
| Wi-Fi 短暂断开 | UNKNOWN 立即生效但不再启动冷却，重连办公网立即到岗、无需确认（P0-2 修复 + 本轮调整） |
| 年份未内置 | 按周一至周五判定，诊断区标注「未内置」，不影响其他功能 |
| 休假结束模式滞留 | `clearVacation()` 同时把 `sched_daymode` 恢复为 `DAY_LEGAL` |

---

## 8. 已修复问题（对应 v2.0.1 分析报告）

| 级别 | 问题 | 修复 |
|---|---|---|
| **P0-1** | 首次安装从不申请 `POST_NOTIFICATIONS`，摘要默认不显示 | `MainActivity.onCreate()` 中按 `summaryOn` 主动申请；提示可点击重试 |
| **P0-2** | Wi-Fi 掉线重连被冷却卡约 12 分钟 | UNKNOWN 分支不写 `pl_flip`；网关变化判定去掉长度前置条件；重连即重新计时 |
| **P1-1** | 场景态服务销毁不恢复勿扰 | 全局静默档整体移除，问题消失 |
| **P1-2** | MODE_ZEN 无兜底 | 模式移除，问题消失 |
| **P1-3** | 文案「一律放行」与实际不符 | `help_body` 重写，明确「断网走时间窗、窗外收起」 |
| **P1-4** | build.ps1 硬编码旧路径 | 改为 `$PSScriptRoot`，并补 UTF-8 BOM 以正确处理中文文件名 |
| **P2-1** | Zen 恢复覆盖用户操作 | 随模式移除 |
| **P2-2** | `allowBackup=true` | 改为 `false` |
| **P2-4** | 摘要在模式切换时滞留 | 离开 SILENCE 时 `Fold.clear` |
| **P2-7** | 通知使用权字符串包含误判 | 改为 `ComponentName.unflattenFromString` 逐项解析 |

未做：SAF 配置导入导出、模式 C 代理提醒、日历联动、蓝牙辅助判据、GPS 围栏（均非本版目标）。

---

## 9. 适配与验证

**目标机型**：Android 13+（minSdk 33），优先 vivo X100S Pro / 魅族 20（Android 16）。targetSdk 保持 34（本机仅有 android-34 平台）；移除全局静默后不再受 Android 15 勿扰规则变更影响，在 Android 16 上按既有兼容性运行。

**构建产物核验（已执行）**：

- `aapt2 dump badging`：`versionCode=4 / versionName=2.1.0`、`minSdk=33`、`targetSdk=34`；权限仅 `ACCESS_NETWORK_STATE` + `POST_NOTIFICATIONS`（最终打包后复核，确认无 `INTERNET`）。
- APK 内 `classes.dex` 与 `build/dex/classes.dex` SHA-256 一致；APK 与 `app-signed.apk` 字节一致。
- 签名证书 SHA-256 `16A5FF7206B8DB87B27531C832566D52F1518C5813580A32E91F22E69CF1F939`，与旧版一致。
- 74 处 Java 资源引用与 `R.java` 交叉核对无缺失（唯一「缺失」为 `android.R.string.cancel` 系统资源，误报）；布局 47 处 `@string` 引用全部有定义。

**仍需真机验证**（本机无模拟器）：

- [ ] 首次安装弹 `POST_NOTIFICATIONS` 授权，摘要正常出现
- [ ] 办公网锁屏 3 秒再亮屏，2–3 分钟内回到「办公网络」（P0-2 回归）
- [ ] 下班 1 小时后仍连办公网，顶部显示「加班中」且通知正常放行
- [ ] 提前到公司连办公网，显示「已抵达」且放行
- [ ] 点「休假」选起始时间与时长，期间全部放行；点「结束休假」恢复判定
- [ ] 长按办公 / 家庭「记录当前网络」可删除绑定，点击可重新绑定
- [ ] 内置节假日生效：2026/2027 的放假日与补班日判定正确；诊断区显示「内置表 20XX」而非联网来源
- [ ] vivo / 魅族上的后台保活与 NLS 重绑表现

---

## 10. 构建说明

```powershell
& "D:\AI\AIWorkSpace\OnDuty\app\build.ps1"
```

- 工具链：`D:\Tools\AndroidSDK`（`platforms\android-34\android.jar`、`build-tools\36.0.0`）+ JDK 21。
- build-tools 34.0.0 的 d8 在本工程会崩，必须用 36.0.0；`d8 --min-api 33`。
- **路径说明**：工程根目录 `OnDuty/app` 为纯英文路径，避免 PowerShell 5.1 向原生工具传中文路径的乱码问题；build.ps1 带 UTF-8 BOM，中文输出文件名可正确生成。
- 版本号写在 `AndroidManifest.xml`；`aapt2 link` 的 `--version-code` 仅在清单未声明时生效，改版本必须改清单。
