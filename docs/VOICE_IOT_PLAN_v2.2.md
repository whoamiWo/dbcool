# DBCool 语音设备平台（Voice IoT）建设方案 v2.2

> **版本**：v2.2（2026-11-03 新增）  
> **状态**：待执行（本文为**唯一权威实施方案**，取代本主题此前所有口头方案）  
> **基线**：DBCool v2.1.3（`a2a5881`，2026-11-03）  
> **决策人**：项目所有者  
> **证据标注约定**：✅ = 源码拉取 / API 实测 / 仓库清单核对实证；⚠️ = 推断或待实测  
> **交叉引用**：[`docs/CI_GATES.md`](./CI_GATES.md)（门禁）｜[`docs/PRODUCTIZATION_ROADMAP.md`](./PRODUCTIZATION_ROADMAP.md)（产品化主线）｜[`docs/secrets-management.md`](./secrets-management.md)（密钥）｜[`docs/sla.md`](./sla.md)（SLA）｜[`docs/acceptance-checklist.md`](./acceptance-checklist.md)（验收）

---

## 一、执行摘要

### 1.1 一句话定位

> **让小智音箱（ESP32）成为 DBCool 企业协作平台的一等终端**——私有接入（不依赖 xiaozhi.me）、在 IM 频道里作为虚拟成员被 @ 和回答、把语音作为通知矩阵的新出口、把录音经 AI 分析转成结构化日志、用声纹在噪音环境中过滤误触发。

### 1.2 核心结论

| # | 结论 | 依据 |
|---|---|---|
| 1 | **xiaozhi.me 官方控制台闭源，无开源实现**；但生态有 MIT/Apache 开源对标物（`xinnan-tech/xiaozhi-esp32-server` 11.1k star，2026-11-03 仍活跃） | ✅ GitHub API 实测 |
| 2 | **只需实现 1 个 OTA 检查接口，设备即自动接入你的服务器**——固件把 OTA 响应逐键写入 NVS，建连时读取。**无需改固件、无需刷机**，保留 171 个固件变体生态与官方 OTA 通道 | ✅ `main/ota.cc` + `main/protocols/websocket_protocol.cc` 源码 |
| 3 | **官方固件无存储、无录音、无声纹能力**（`idf_component.yml` 全量 90+ 依赖中无 FATFS/SDMMC/LittleFS；`application.cc` 检索 `voiceprint` / `speaker` 零命中） | ✅ 实测 |
| 4 | **会话音频必然流经服务器** → 服务端旁路录制是零固件改动的录音能力 | ✅ 协议语义 |
| 5 | **声纹 ≠ 降噪唤醒**。声纹解决「谁在说话」，降噪唤醒在设备端（`esp-sr` + `esp_audio_effects`），服务器无法参与。**声纹的企业价值是过滤误触发**（「环境里只有注册用户能叫醒设备」） | ✅ 固件依赖 + 声纹 Provider 源码 |
| 6 | DBCool 已有**六成以上企业级底座**（多租户 Schema 隔离 / ACL / 计费 / 配额 / 审计 / 工作流 / 通知矩阵 / 密钥托管 / 可观测）→ 本方案**不新建独立栈**，只加 `com.nocobase.iot` 一个包 | ✅ 包结构实测 |
| 7 | **既有 Agent 框架可直接复用**：`AgentService`(ReAct, MAX_STEPS=6) + `AgentTool` SPI + `AgentToolRegistry` → 现有 6 个工具（查数据库/查Wiki/建任务/发钉钉/总结频道）**免费继承给音箱** | ✅ `com.nocobase.ai` 实测 |
| 8 | **主线上线 4-5 周**；声纹 +2 周（可并行）；固件侧新模式为独立可选二期 | 见 §11 |

### 1.3 投入产出概览

```
┌───────────────────────────────────────────────────────────────────────┐
│ 复用（DBCool 已有，零新建）                                             │
│   多租户 · ACL · 计费/配额 · 审计 · 工作流 · 通知矩阵(8 Dispatcher)      │
│   Wiki+pgvector · MinIO · Redis · Prometheus/Grafana · JWT/OIDC/LDAP   │
├───────────────────────────────────────────────────────────────────────┤
│ 新建（本次）                                                           │
│   ① com.nocobase.iot 包（Java，同进程）                               │
│   ② backend-python voice 模块（Provider 抽象 + 录音分析 + 声纹）       │
│   ③ voiceprint-api（Fork，PG + 多租户改造）                           │
│   ④ 控制台 6 个页面（React）                                          │
│   ⑤ 协议规范文档 + 模拟器验证脚本                                      │
├───────────────────────────────────────────────────────────────────────┤
│ 可选（二期，需自编译固件）                                              │
│   ⑥ kDeviceStateRecording 新状态 + MCP 录音工具                        │
│   ⑦ xz_recorder 组件 + TF 卡离线补传                                   │
└───────────────────────────────────────────────────────────────────────┘
```

---

## 二、事实基线（证据）

### 2.1 开源生态现状 ✅

| 项目 | Star | License | 最后推送 | 定位与可借鉴点 |
|---|---|---|---|---|
| `78/xiaozhi-esp32` | 32.5k | MIT | 2026-11-01 | **固件 + 协议 SSOT**：138 板卡目录 / 171 固件变体 / 53 厂商 |
| `xinnan-tech/xiaozhi-esp32-server` | 11.1k | MIT | 2026-11-03 | **最完整开源控制台**：Python+Java+Vue+uni-app；`core/connection.py`(78KB)、`core/providers/`(8 类 40+ 实现)、`core/handle/`(12 处理器)、22 页控制台 |
| `joey-zhou/xiaozhi-esp32-server-java` | 1.4k | MIT | 2026-09 | Java 一体化：设备监控/音色定制/角色切换/对话记录 |
| `hackers365/xiaozhi-esp32-server-golang` | 452 | MIT | 2026-11-02 | Go 版：WS + MQTT/UDP、声纹/声音克隆/知识库/MCP 远程调用/主动音频下发 |
| `AnimeAIChat/xiaozhi-server-go` | 499 | NOASSERTION | 2026-08 | Go 版 |
| `huangjunsen0406/py-xiaozhi` | 3.5k | — | 2026-10-31 | **协议模拟器**（P0 验证首选，与 `backend-python` 同栈） |
| `TOM88812/xiaozhi-android-client` | 1.7k | — | 2026-09 | Android/iOS 客户端（多端形态参考） |
| `100askTeam/xiaozhi-linux` | 293 | — | — | 嵌入式 Linux：全志 T113/Tina、嘉楠 K230、RK、STM32MP157 |
| `RealDeco/xiaozhi-esphome` | 842 | — | 2026-11 | ESPHome 集成（第三方生态兼容参考） |
| `xinnan-tech/voiceprint-api` | 132 | **Apache-2.0** | **2025-07-16** | **3D-Speaker 声纹 API**（FastAPI，端口 8005，MySQL） |
| `OpenVoiceOS/OpenVoiceOS` | 组织级 | 开源 | 2026-11-02 | Mycroft 续作。**架构参考**：`ovos-PHAL` 硬件抽象层、`ovos-messagebus` 消息总线 |
| `picovoice/porcupine` | 5.0k | Apache-2.0 | 2026-11-02 | 离线唤醒词引擎（商业授权，参考实现） |

> **结论**：上游做「设备 → 对话」，本方案做「设备 → **企业协作**」。所有小智服务端都没有 IM / 工作流 / 多租户 / 权限模型 / 计费审计 —— **这是生态断层，也是本项目的立足点**。

### 2.2 协议核心机制 ✅

#### 2.2.1 服务器地址下发（关键杠杆）

```cpp
// main/ota.cc —— OTA 响应逐键写入 NVS
Settings settings("websocket", true);          // 命名空间 "websocket"
cJSON_ArrayForEach(item, websocket) { settings.SetString(item->string, item->valuestring); }
Settings settings("mqtt", true);              // 命名空间 "mqtt"

// main/protocols/websocket_protocol.cc —— 建连时读取
Settings settings("websocket", false);
std::string url     = settings.GetString("url");     // ← 你的服务器地址
std::string token   = settings.GetString("token");   // ← 你的鉴权 token
int         version = settings.GetInt("version");   // ← 1/2/3（整数，非字符串）
websocket_->SetHeader("Authorization", token.c_str());            // ⚠️ 裸 token
websocket_->SetHeader("Protocol-Version", std::to_string(version_).c_str());
websocket_->SetHeader("Device-Id", SystemInfo::GetMacAddress().c_str());
websocket_->SetHeader("Client-Id", Board::GetInstance().GetUuid().c_str());
```

| 结论 | 说明 |
|---|---|
| **OTA 接口 = 设备接入口** | 实现 `GET/POST /xiaozhi/ota/` 返回 `websocket` 段，设备即改指你的服务器 |
| **OTA 升级能力白送** | 同一接口的 `firmware` 段可下发新固件 URL |
| **可完全接管 OTA 通道** | `ota_url` 存于 NVS `wifi` 命名空间且可写（`Settings settings("wifi", false)` + `GetString("ota_url")`） |
| 固件默认 OTA 地址 | `CONFIG_OTA_URL = https://api.tenclass.net/xiaozhi/ota/`（`Kconfig.projbuild`） |
| ⚠️ **Authorization 是裸 token** | 官方文档写 `Bearer <token>`，源码是 `SetHeader("Authorization", token.c_str())` → **服务端必须双兼容** |

#### 2.2.2 NVS 键位表（OTA 下发的精确键名，键名错位即静默失效）

| 命名空间 | 键名 | 类型 | 说明 |
|---|---|---|---|
| `websocket` | `url` | string | WebSocket 服务地址 |
| `websocket` | `token` | string | 鉴权 token（裸 token 形式放进 `Authorization` 头） |
| `websocket` | `version` | **int** | 二进制协议版本 1/2/3 |
| `websocket` | `recording_enabled` | string | ★ 本方案扩展（见 §5.3） |
| `mqtt` | `endpoint` | string | `host:port` |
| `mqtt` | `client_id` | string | MQTT 客户端 ID |
| `mqtt` | `username` | string | MQTT 用户名 |
| `mqtt` | `password` | string | MQTT 密码 |
| `mqtt` | `keepalive` | int | 保活间隔（默认 240s） |
| `mqtt` | `publish_topic` | string | 上行主题 |
| `wifi` | `ota_url` | string | OTA 检查地址（可写 → 可私有化 OTA 闭环） |

#### 2.2.3 二进制音频帧格式 ✅

```cpp
// main/protocols/protocol.h
struct BinaryProtocol2 {            // v2: metadata + timestamp（支持服务端 AEC）
    uint16_t version; uint16_t type; uint32_t reserved;
    uint32_t timestamp; uint32_t payload_size; uint8_t payload[];
} __attribute__((packed__));

struct BinaryProtocol3 {            // v3: 轻量 header ← 推荐默认
    uint8_t type; uint8_t reserved; uint16_t payload_size; uint8_t payload[];
} __attribute__((packed__));
// v1 = 裸 Opus
```

| 版本 | 帧结构 | 用途 | 本方案取舍 |
|---|---|---|---|
| v1 | 裸 Opus | 最简 | 兜底降级 |
| v2 | 16 字节头 + payload | 服务端 AEC（回声消除） | 噪音场景可选 |
| **v3** | **4 字节头 + payload** | **最简，设备端默认推荐** | **默认** |

音频参数：上行 **16kHz** / 下行 **24kHz**，mono，**60ms** 帧。

#### 2.2.4 MQTT+UDP 通道（二期可选）

```
UDP 音频包头（16 字节，网络字节序）：
type(1) | flags(1) | payload_len(2) | ssrc(4) | timestamp(4) | sequence(4) | payload

加密：AES-CTR，128bit key + 128bit nonce（服务端经 MQTT hello 响应下发）
防重放：sequence 单调递增校验；计数器含 timestamp+sequence
```

> **一期不做**：需 EMQX + 公网 UDP + AES-CTR 实现 + NAT 穿透，占 20% 工作量仅换来更低延迟与更差防火墙友好度。

### 2.3 小智原有模式体系完整还原 ✅

#### 2.3.1 设备状态机（`main/device_state.h`，11 状态）

```cpp
enum DeviceState {
    kDeviceStateUnknown, kDeviceStateStarting, kDeviceStateWifiConfiguring,
    kDeviceStateIdle,    kDeviceStateConnecting,
    kDeviceStateListening, kDeviceStateSpeaking,
    kDeviceStateNotifying,          // ← 单向播报模式
    kDeviceStateUpgrading, kDeviceStateActivating,
    kDeviceStateAudioTesting, kDeviceStateFatalError
};
```

由 `DeviceStateMachine`（`main/device_state_machine.h`）管理：`TransitionTo` / `CanTransitionTo` / `IsValidTransition` **严格校验非法迁移**，并提供 `StateCallback` 观察者。

#### 2.3.2 交互模式（`main/protocols/protocol.h`）

```cpp
enum ListeningMode {
    kListeningModeAutoStop,      // 唤醒词对话
    kListeningModeManualStop,    // 按键对话
    kListeningModeRealtime       // 实时全双工（需 AEC 支持）
};
enum AbortReason { kAbortReasonNone, kAbortReasonWakeWordDetected };
```

#### 2.3.3 MCP 工具体系（`main/mcp_server.cc`）

```cpp
// 普通工具（Agent 可调用）
self.get_device_status / self.audio_speaker.set_volume
self.screen.set_brightness / self.screen.set_theme / self.camera.take_photo

// 用户专属工具（AddUserOnlyTool，仅本机用户可调 —— 敏感操作归此类）
self.get_system_info / self.reboot / self.upgrade_firmware
self.screen.get_info / self.screen.snapshot / self.screen.preview_image
self.assets.set_download_url
```

> `self.screen.snapshot` 描述为 *"Snapshot the screen and upload it to a specific URL"* → **官方已有「设备端采集并上传到指定 URL」的现成范式**，本方案的录音上传完全对标该范式。

#### 2.3.4 消息类型全集（服务端需处理）

`hello` · `listen` · `abort` · `stt` · `tts` · `llm` · `mcp` · `system` · `alert` · `custom` · `notify` · `goodbye`

关键语义：
- `listen`：`{session_id, type, state: "start"|"stop"|"detect", mode: "auto"|"manual"|"realtime", text?}`
- `abort`：`{session_id, type, reason: "wake_word_detected"}` → 服务端必须立即停 TTS 推流
- `tts`：`{state: "start"|"stop"|"sentence_start", text?}` → `start` 后紧跟二进制下行音频
- `notify`：`{audio_url, subtitles[]}` → **云→设备单向播报，明确不开放麦克风上行**
- `mcp`：`{session_id, type:"mcp", payload: <JSON-RPC 2.0>}` → `initialize` / `tools/list` / `tools/call`

### 2.4 固件能力边界 ✅

实测 `main/idf_component.yml` 全量 90+ 依赖：

| 结论 | 证据 |
|---|---|
| **无任何存储介质支持** | 无 `esp_vfs_fatfs` / `sdmmc` / `esp_littlefs` / `wearleveled` |
| **无设备端录音能力** | `main/audio/` 仅 `audio_codec` / `audio_service` / `audio_debugger` / `fixed_queue` / `wake_word`，**无 writer** |
| **无声纹代码** | `main/application.cc` / `application.h` 全文检索 `voiceprint` / `speaker` → **0 命中** |
| **`notify` 单向** | 官方文档：*"It does not open a conversation audio channel and never enables microphone uplink."* |
| **会话音频必然过服务器** | ← **服务端旁路录制的零成本依据** |
| **唤醒在设备端离线完成** | 依赖 `espressif/esp-sr == 2.4.7` → **唤醒前音频不上行，服务端无法参与唤醒判定** |
| 已具备 AFE 能力 | 依赖 `espressif/esp_audio_effects ~1.3.0`（AEC/NS/AGC/VAD） |
| 音频编解码器已具备 | `espressif/esp_audio_codec ~2.5.0` → **Opus 编码器可复用，无需新增依赖** |

### 2.5 声纹实现现状 ✅

#### 2.5.1 上游是 HTTP 客户端，非内置模型

```
main/xiaozhi-server/core/utils/voiceprint_provider.py   ← Provider（客户端）
main/xiaozhi-server/core/utils/cache/                    ← 健康检查缓存（CacheType.VOICEPRINT_HEALTH）
docs/voiceprint-integration.md                           ← 部署指南
```

识别流程（实测源码）：

```
POST {base}/voiceprint/identify
  Authorization: Bearer <key>
  multipart/form-data: speaker_ids="id1,id2"  +  file=audio.wav
→ 200 { "speaker_id": "...", "score": 0.87 }
→ score < similarity_threshold(默认 0.4) → 返回 "未知说话人"
→ 否则返回 speaker_map["name"]
```

| 配置项 | 说明 |
|---|---|
| 说话人配置 | `"speaker_id,姓名,描述"`（如 `"test1,张三,张三是一个程序员"`） |
| 阈值参数 | `server.voiceprint_similarity_threshold`，默认 0.4，范围 0.0-1.0 |
| 前置条件 | 智能体记忆模式必须为「本地短期记忆」且开启「上报文字+语音」 |
| 健康检查 | 带缓存；URL 形如 `http://host:8005/voiceprint/health?key=<key>`，key 从 query 提取 |

#### 2.5.2 独立服务 `xinnan-tech/voiceprint-api`

| 项 | 值 |
|---|---|
| License | **Apache-2.0**（可商用）✅ |
| 星标 / 最后推送 | 132 / **2025-07-16（16 个月未更新）** ⚠️ |
| 技术栈 | Python 3.10 + FastAPI + Uvicorn，端口 **8005** |
| 模型 | **3D-Speaker** |
| 存储 | **MySQL**：`voiceprints(id, speaker_id UNIQUE, feature_vector LONGBLOB, created_at, updated_at)` |
| 接口 | `/voiceprint/health?key=` · `/voiceprint/identify` · `/voiceprint/register` · Swagger `/voiceprint/docs` |
| 认证 | `Authorization: Bearer <key>`（key 在 URL query 中传递） |
| 依赖 | `conda create -n voiceprint-api python=3.10` → `pip install -r requirements.txt` |

> **必须改造的 4 项**：MySQL→PG、加 `tenant_id`、加保留期/吊销、特征量化。

### 2.6 DBCool 现有底座 ✅（可直接复用，零新建）

| 包 | 已有能力 | 本方案复用为 |
|---|---|---|
| `auth/` | JWT、`AclEnforcer`（字段级+行级+操作级）、OIDC、LDAP、`KeyRingService`（rotate/revoke/findByKid/密钥环） | 平台登录、设备凭据轮换语义、模型厂商密钥托管 |
| `tenant/` | **Schema 级隔离**（`SchemaTenantConnectionProvider` 每次取连接 `SET search_path`）、租户配额 | 租户隔离（设备/声纹/会话全部带 `tenant_id`） |
| `ai/` | `AiAgentEntity`（**频道虚拟成员**，含 `channelId`）、`AgentTool` SPI、`AgentToolRegistry`、`AgentService`（ReAct MAX_STEPS=6）、`AiAssistantService`（→ Python `/api/ai/chat`，已有三层防护：限流/缓存/配额） | 语音 Agent 编排底座（**不重写**） |
| `im/` | 频道、消息、线程、表情、已读、置顶、阅后即焚、**@提及**、斜杠命令（`/remind` `/invite` `/ai`）、Huddle(WebRTC)、`MessageSearchService` | 音箱作为频道虚拟成员 |
| `workflow/` | 可视化编排、8 个 NodeHandler、条件、Cron、**Redis 分布式锁**、死信、webhook 触发 | OTA 灰度、录音分析后派单 |
| `notification/` | **8 个 Dispatcher**：DingTalk / WeCom / Feishu / WeChat / Email / Webhook | 新增 `VoiceDispatcher`（语音出口） |
| `billing/` | 套餐、订阅、发票、支付网关、逾期任务（17 个类） | 语音用量套餐 |
| `quota/` | Redis 分钟桶 + LRU 本地缓存 + 延迟刷回（`TenantQuotaService`） | 语音分钟/字符/token 维度配额 |
| `compliance/` | `UserDataErasureService`（导出 + 删除） | 录音/声纹/会话删除 |
| `audit/` | `AuditService` 全链路留痕 | 设备操作/录音调听/声纹变更审计 |
| `ratelimit/` | `GlobalRateLimitFilter`（登录/消息/上传/API Key 维度） | 设备维度限流 |
| `apikey/` | API Key + 独立限流维度 | 设备接入的机器凭据参考 |
| `attachment/` | `MinioStorageService`（上传/预签名/缩略图）、`maxFileSizeMb=100` | 录音文件存储与播放 |
| `wiki/` | 块编辑器、版本历史、反向链接、**pgvector 768 维 + 中文分词混合检索** | 录音分析生成的纪要页（纳入语义检索） |
| `integration/credential/` | `TenantCredentialService` + `CredentialCryptoService`（AES-GCM 密文落库） | **模型厂商 + 声纹服务密钥托管** |
| `observability/` | Prometheus + Grafana + Loki + Jaeger + `alerts.yml` 规则组 | 语音专属指标与告警 |
| 交付链路 | `docker-compose.yml` + `install.sh` + GitHub Actions + **契约门禁**（backend 438 / frontend 130，零漂移） | 部署与 CI |

### 2.7 已知既有缺陷（本方案会踩到的）⚠️

| 缺陷 | 证据 | 语音影响 | 处置 |
|---|---|---|---|
| `eraseAiConversations()` **未实现** | 仅 `log.warn` + `incrementConversationsErased(0)` | 语音会话接入合同时必踩 | **P5 顺手修**（真实删除） |
| `HuddleSignalingHandler` **内存路由** | `Map<String, Set<WebSocketSession>>`，注释自认「多实例需迁移 Redis pub/sub」；K8s `replicas:3` + HPA 到 10 | 设备会话若用内存 Map → **生产必然时好时坏** | **P1 第一天起就用 Redis** |
| `MessageService` @提及**只发通知不触发 Agent** | `MessageService.java:106-112` 仅 `createMentionNotification` | 「IM 中 @小智即回答」当前**不成立** | **P3 新增 `VoiceAgentDispatcher`** |
| 契约门禁零漂移 | `scripts/api-contract-verify.py` | 新增端点未登记 → CI 红 | 每次新增端点重跑两个导出脚本 |
| 文档数字门禁 | `scripts/doc-numbers-verify.py` 只扫 `README.md`/`BASELINE.md`/`RISK_REGISTER.md`/`TECH_DEBT.md`/`docs/PRODUCTIZATION_ROADMAP.md` | 本文档不在扫描范围 | 文中门禁数字标注快照日期 |

### 2.8 落地约定 ✅（本方案遵循的既有约定）

```
Flyway 迁移：backend-java/src/main/resources/db/migration/V77__*.sql 起（当前最大 V76__schema_completion.sql）
表命名：与现有 V74__billing_system.sql 一致（小写下划线 + COMMENT ON TABLE/COLUMN）
错误码：contracts/errors.yaml —— 0 成功 / 1000 INVALID_PARAMS / 1001 UNAUTHORIZED / 1002 FORBIDDEN / 1003 NOT_FOUND
响应信封：{ code, message, data }（ApiResponse）
免鉴权端点：SecurityConfig 中 .requestMatchers(...).permitAll()
Java 包：com.nocobase.<domain>（service/controller/dto/entity 分目录）
Java 测试：backend-java/src/test/java/com/nocobase/<domain>/
前端页面：frontend/src/pages/<Page>.tsx + .test.tsx（React.lazy 桥接命名导出）
前端特性：frontend/src/features/<domain>/
i18n：frontend/src/i18n/locales/zh-CN.json 顶层键（新增 "iot"）
Python 路由：backend-python/src/nocobase_py/routers/<name>.py，prefix="/api/<name>"，在 main.py include_router
观测：observability/prometheus.yml（job_name）+ observability/alerts.yml（groups[].rules[]）
验证脚本：scripts/<name>-e2e-verify.py（可失败、有明确 PASS/FAIL 计数）
```

---

## 三、定位与差异化

### 3.1 竞品对照

| 能力域 | xiaozhi.me | xiaozhi-esp32-server | **DBCool Voice IoT** |
|---|---|---|---|
| 开源 | ❌ | ✅ MIT | ✅ Apache-2.0 |
| 设备接入（不改固件） | ✅ | ✅ | ✅ **OTA 下发地址** |
| 语音对话 ASR/LLM/TTS | ✅ | ✅ 40+ Provider | ✅ 借实现 + **凭证托管** |
| 降噪唤醒（设备端 AFE） | ✅ | ✅ | ✅ 依赖固件已有能力 |
| **声纹识别** | ✅ | ✅ 独立服务 | ✅ **自建 + 多租户 + 合规** |
| **噪音场景「谁能唤醒」** | ❌ | ❌ | ✅ **声纹过滤误触发** ★ |
| **IM 集成** | ❌ | ❌ | ✅ **频道虚拟成员** |
| **工作流联动** | ❌ | ❌ | ✅ |
| **录音转日志** | ❌ | ❌ | ✅ |
| 多租户 | 基础 | ❌ | ✅ **Schema 级隔离** |
| 企业权限 | 基础 | Shiro 简单角色 | ✅ **字段级+行级+操作级** |
| 计费/配额 | 充值 | ❌ | ✅ |
| 审计 | 基础 | ❌ | ✅ 39+ 调用点 |
| 模型厂商密钥 | 托管 | 明文配置 | ✅ **AES-GCM 租户级托管** |

### 3.2 五项差异化护城河（生态中无人做过）

```
① 音箱 = IM 频道虚拟成员      → @小智 提问，语音回答 + 文本回帖
② 语音 = 通知矩阵新出口        → 工作流/告警 → 音箱播报（复用 8 Dispatcher 体系）
③ 录音 = AI 结构化日志         → 录音 → ASR → LLM → Wiki / IM / 工作流
④ 声纹 = 企业级权限过滤        → 只有注册用户能唤醒设备
⑤ 全套企业底座复用            → 多租户/ACL/计费/审计/配额/可观测 零新建
```

### 3.3 范围边界（明确不做）

| 不做 | 理由 |
|---|---|
| 改官方固件（主线） | OTA 下发地址已足够；改固件 = 放弃 171 变体 + 失去官方 OTA |
| MQTT+UDP（一期） | 需 EMQX + 公网 UDP + AES-CTR + NAT 穿透，20% 工作量仅换更低延迟 |
| 138 变体全适配 | 先做 2-3 种 |
| 自研 ASR/TTS/声纹模型 | 用云 API + Fork 现成实现 |
| 更换 IM/工作流/权限底座 | 复用现有 DBCool 资产 |

---

## 四、总体架构

### 4.1 系统架构

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         DBCool 统一底座（复用，不改）                          │
│  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐        │
│  │IM 频道 │ │Workflow│ │  Wiki  │ │Billing │ │  ACL   │ │ Audit  │        │
│  │消息/提及│ │ 编排   │ │ 知识库 │ │/Quota  │ │ 租户   │ │        │        │
│  └───┬────┘ └───┬────┘ └───┬────┘ └───┬────┘ └───┬────┘ └───┬────┘        │
│      │改①      │加节点    │接RAG    │语音维度  │设备审计  │设备事件          │
│      ▼         ▼         ▼         ▼         ▼         ▼                │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │              ★ 新增 com.nocobase.iot 包（Spring Boot 内同进程）         │  │
│  │  设备域          会话域           Agent 适配         企业级            │  │
│  │  DeviceEntity    IotSessionEntity  VoiceAgentAdapter  DeviceCredential │  │
│  │  Credential      IotUtteranceEntity SpeakDeviceTool   Retention      │  │
│  │  Firmware        DeviceSession(Redis)                CircuitBreaker  │  │
│  │  OtaTask         RecordingWriter                      ProviderChain   │  │
│  │  录音域：IotRecording → VoiceAnalysis → Wiki / IM / Workflow          │  │
│  │  声纹域：VoiceprintProvider（并行 ASR）→ SpeakerMatch → 策略过滤        │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│         │ WSS                    │ HTTP                                   │
└─────────┼────────────────────────┼──────────────────────────────────────────┘
          │                        │
   ┌──────▼──────┐        ┌────────▼─────────┐        ┌──────────────┐
   │ ESP32 音箱   │        │ backend-python    │        │  控制台 Web   │
   │ 官方固件     │◀──────▶│ (新增 voice/)     │        │  React 18     │
   │ (不改固件)   │        │ ASR/LLM/TTS      │        │ 设备/Agent/   │
   └─────────────┘        │ Voiceprint        │        │ OTA/录音/声纹 │
                          │ Provider 抽象     │        └──────────────┘
                          └────────┬─────────┘
                                   ▼
              云厂商 ASR/LLM/TTS · 本地 CosyVoice · voiceprint-api · MCP Server
```

### 4.2 关键技术决策（9 条）

| # | 决策 | 选择 | 理由 |
|---|---|---|---|
| 1 | 网关部署形态 | **Spring Boot 内同进程** | 0→1 阶段少跨进程复杂度；复用租户上下文/JWT/审计/STOMP。**会话状态从第一天存 Redis**，为将来拆分留路（见 §2.7 教训） |
| 2 | 音频传输通道 | **WebSocket only** | 穿透性好、延迟够用、运维简单；MQTT+UDP 推二期 |
| 3 | AI 编排位置 | **`backend-python` 扩展**（新 `routers/voice.py`，prefix `/api/voice`） | 已有 FastAPI + asyncpg + `llm_cache` + `quota`；语音 SDK 生态更全 |
| 4 | Agent 层 | **不重写**，加 2 个适配器 | `AgentService`/`AgentTool` SPI 已完备；现有 6 个工具免费继承给音箱 |
| 5 | 设备/固件/OTA/音色/模板 | **动态表引擎**（collection） | 复用 ACL/视图/导入导出，少写一半 CRUD |
| 6 | 会话/录音/分析/声纹 | **硬表 + Flyway（V77 起）** | 高频写 + 时序聚合 + 保留期清理 |
| 7 | 设备凭据 | **独立表 + 复用 `KeyRingService` 语义** | 唯一「真·企业级」新增能力，是多租户/审计/计费/吊销的地基 |
| 8 | 声纹服务 | **Fork `voiceprint-api` → PG 改造** | Apache-2.0 可商用；数据不出域；企业合规必需 |
| 9 | 协议与 Provider 来源 | **照抄上游（MIT / Apache-2.0）** | 协议必须一致，不能自创；Provider 目录结构照搬 |

### 4.3 与小智协议的完整对应（时序）

```
① 设备开机联网
② GET/POST /xiaozhi/ota/  ← 你的响应：
{
  "websocket": {"url":"wss://<域名>/xz/ws","token":"<设备token>","version":3,
                "recording_enabled":"true"},
  "firmware":  {"version":"1.2.3","url":"https://..."},
  "server_time": {"timestamp":...,"timezone_offset":0}
}
③ 固件写 NVS("websocket") = {url, token, version, recording_enabled}
④ 建连握手头：
   Authorization: <裸token>   Protocol-Version: 3
   Device-Id: <MAC>            Client-Id: <UUID>
⑤ 设备 → {"type":"hello","version":3,"features":{"mcp":true,"aec":true},
           "transport":"websocket",
           "audio_params":{"format":"opus","sample_rate":16000,"channels":1,"frame_duration":60}}
⑥ 服务端 → {"type":"hello","transport":"websocket","session_id":"...",
             "audio_params":{"format":"opus","sample_rate":24000,"channels":1,"frame_duration":60}}
⑦ 设备 → {"type":"listen","state":"start","mode":"auto"} + 二进制 Opus 流
⑧ 服务端 → {"type":"stt","text":"..."} / {"type":"tts","state":"start"} + Opus 下行
```

**兼容要点**：
- `Authorization` **裸 token 与 Bearer 双兼容**（源码与文档不一致）
- `version` 必须是**整数**
- `mode` 字段新增 `"recording"` **不影响老设备**（老设备只发 auto/manual/realtime）

---

## 五、模式体系（核心设计）

### 5.1 六种模式总表（**新增而非替换**）

| 模式 | 设备状态 | 触发 | 音频上行 | 设备播报 | 平台处理 | 产出 |
|---|---|---|---|---|---|---|
| **唤醒词对话** | `Listening` | 唤醒词 | ✅ | ✅ TTS | ASR→LLM→TTS | `iot_session`(DIALOGUE) |
| **按键对话** | `Listening` | BOOT 键 | ✅ | ✅ | 同上 | 同上 |
| **实时全双工** | `Listening` | AEC | ✅ 双向 | ✅ | 同上（带 AEC） | 同上 |
| **单向播报** | `Notifying` | 云端 `notify` | ❌ | ✅ | 只下发音频 | 播报记录 |
| **★ 录音转日志** | `Recording`（新增） | 定时/按键/远程/阈值 | ✅ | ❌ | 存音频→ASR→LLM | `iot_recording` + `iot_voice_analysis` |
| **★ 声纹过滤** | 横切所有有上行音频的模式 | 服务端并行 | ✅ | ✅ | 声纹并行 ASR → 策略过滤 | `iot_speaker` + 个性化上下文 |

> **设计要点**：**声纹不是一个独立模式，而是横切所有「有音频上行」模式的过滤器** —— 这是它能解决噪音误触发的根本原因。

### 5.2 录音转日志模式：三层实现

#### 层次 1：服务端旁路录制（**零固件改动，1-2 天**）⭐ 优先做

**原理**：对话模式音频本就上行到服务器，旁路存一份。

```
设备 (Listening) ──上行 Opus──▶ 服务器 ──┬──▶ ASR/LLM/TTS ──▶ 下行播报（原有）
                                      └──▶ RecordingWriter ──▶ MinIO（新增）
```

**降噪处理**：用 `iot_utterance.role` 分离用户语音（`USER`）与 TTS 回放（`ASSISTANT`），分析只用 `USER` 段。

| 优点 | 缺点 |
|---|---|
| 零固件改动、零协议改动、零风险 | 只能录到「对话期间」的音频 |
| 立即可用 | 会录到设备自己的 TTS（靠 role 分离解决） |

#### 层次 2：新增设备状态 `kDeviceStateRecording`（**需固件分支，3-5 天**）

```cpp
// main/device_state.h —— 新增一个枚举值
enum DeviceState {
    ..., kDeviceStateNotifying,
    kDeviceStateRecording,      // ★ 新增：录音转日志模式
    kDeviceStateUpgrading, ...
};

// main/device_state_machine.cc —— 补全合法迁移（漏一条会卡死设备）
bool DeviceStateMachine::IsValidTransition(DeviceState from, DeviceState to) const {
    if (from == kDeviceStateIdle     && to == kDeviceStateRecording) return true;
    if (from == kDeviceStateRecording && to == kDeviceStateIdle)      return true;
    if (from == kDeviceStateRecording && to == kDeviceStateListening) return true;  // 随时切回对话
    ...
}

// main/protocols/protocol.h —— 与现有 ListeningMode 同构
enum RecordingMode {
    kRecordingModeTimer,      // 定时录音
    kRecordingModeButton,     // 按键录音
    kRecordingModeRemote,     // 平台远程触发
    kRecordingModeThreshold,  // 声音阈值触发
};
```

**关键差异 vs 现有 Listening**：

| | `Listening`（对话） | `Recording`（录音） |
|---|---|---|
| 发 `listen` 消息 | ✅ | ❌（不发 → 服务器不回 TTS） |
| 服务器回 TTS | ✅ | ❌ |
| 设备播报 | ✅ | ❌（可选确认音） |
| **音频上行通道** | ✅ | ✅ **完全相同** |

> **最小改动关键**：复用**完全相同的音频上行通道**，只是不发 listen、不收 TTS。

#### 层次 3：MCP 工具触发（**复用现有 MCP 体系，最优雅**）

```cpp
// main/mcp_server.cc —— AddUserOnlyTools 里新增
// （归入 AddUserOnlyTool 而非 AddTool：与 reboot 同安全级别，避免被 Agent 误触发）
AddUserOnlyTool("self.recorder.start",  "Start recording audio for logging", ...);
AddUserOnlyTool("self.recorder.stop",   "Stop recording", ...);
AddUserOnlyTool("self.recorder.status", "Get recording status and duration", ...);
```

> 与官方 `self.screen.snapshot`（截图并上传到指定 URL）**完全同构**。

#### 四种触发方式（零协议新增）

| 触发 | 实现 | 固件改动 |
|---|---|---|
| **平台远程** | 平台发 `{"type":"custom"}` 或 MCP `tools/call` | **零** |
| **按键** | 复用现有 `boot_button_` 的 `OnLongPress` | 小 |
| **定时** | FreeRTOS Timer → `SetDeviceState(kDeviceStateRecording)` | 中 |
| **声音阈值** | `audio_service_.IsVoiceDetected()` 轮询 | 中 |

#### 设备如何表达「这是录音会话」

**方案 A（推荐）**：OTA 下发的 `websocket` 段增加键
```json
{"websocket":{"url":"...","token":"...","version":3,"recording_enabled":"true"}}
```
→ 固件写 NVS(`websocket`)["recording_enabled"] → **决定该设备是否允许录音模式**

**方案 B**：设备发 `{"type":"listen","state":"start","mode":"recording"}`
→ 服务端按 `mode` 字段区分 → **不影响老设备**

**建议 A + B 组合**：A 定能力边界，B 定本次会话类型。

### 5.3 噪音环境唤醒：四层防线

> ⚠️ **必须先纠正认知**：**声纹 ≠ 降噪唤醒**。唤醒判定发生在**设备端**，音频**尚未上行**，服务器无法参与。

```
┌──────────────────────────────────────────────────────────────┐
│ 第1层 硬件选型（决定 ~60% 效果）                              │
│   双麦/多麦阵列（波束成形）→ BOX-3 / KORVO-2                  │
│   麦克风 SNR（数字麦优于模拟麦）· 麦间距                      │
├──────────────────────────────────────────────────────────────┤
│ 第2层 固件 AFE（已有 espressif/esp_audio_effects 1.3.0）      │
│   AEC · NS 降噪 · AGC 自动增益 · VAD                          │
│   CONFIG_USE_DEVICE_AEC / CONFIG_USE_SERVER_AEC 二选一        │
├──────────────────────────────────────────────────────────────┤
│ 第3层 固件唤醒算法（esp-sr 2.4.7）                            │
│   WakeNet 多麦波束成形 + 降噪 · 自定义唤醒词 · 阈值可调        │
├──────────────────────────────────────────────────────────────┤
│ 第4层 服务端降级（★ 我们的增量）                               │
│   ① VAD/SNR 判定有效语音                                      │
│   ② 声纹识别 → 只有注册用户才响应（防旁人误触发）★ 核心        │
│   ③ 关键词兜底：唤醒后先判内容再决定是否响应                    │
│   ④ 注入说话人上下文 → LLM 知道在对谁说话                       │
└──────────────────────────────────────────────────────────────┘
```

**★ 声纹在噪音场景的真正价值**：**不是提高召回率，而是过滤误触发** —— 「环境里只有注册用户能叫醒设备」。这正是企业（车间/医院/餐厅）场景的核心诉求。

**端到端流程**：
```
噪音环境 → 设备被唤醒（唤醒率由硬件+AFE决定）
        → 音频上行 → 服务端 VAD/SNR 判定
        → 声纹识别（并行 ASR）
           ├─ 命中注册用户 → 正常对话 + 个性化（"张总，3号车间湿度超标"）
           └─ 未命中/噪音 → 静默丢弃 或 播报「请再说一次」
```

---

## 六、数据模型

### 6.1 硬表（Flyway V77 起）

#### V77__iot_device.sql

```sql
-- IoT 设备档案（全局表，网关需跨租户索引 device_id）
CREATE TABLE iot_device (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(128) NOT NULL,          -- 设备 MAC 地址
    client_id VARCHAR(128),                   -- 板卡 UUID
    name VARCHAR(128) NOT NULL,
    model VARCHAR(64),                        -- 板型：esp32s3-box-3 / esp32-s3-epaper-1.54 ...
    board VARCHAR(64),
    fw_version VARCHAR(32),
    status VARCHAR(16) NOT NULL DEFAULT 'OFFLINE',  -- OFFLINE/ONLINE/BUSY/BANNED
    agent_id UUID,                            -- 绑定的 ai_agent.id
    group_id UUID,                            -- 设备分组
    recording_enabled BOOLEAN NOT NULL DEFAULT FALSE,  -- ★ 是否允许录音模式
    online_at TIMESTAMPTZ,
    last_seen TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_iot_device_tenant_device UNIQUE (tenant_id, device_id)
);
CREATE INDEX idx_iot_device_tenant_status ON iot_device (tenant_id, status);
CREATE INDEX idx_iot_device_tenant_agent  ON iot_device (tenant_id, agent_id);

COMMENT ON TABLE  iot_device IS 'IoT 语音设备档案';
COMMENT ON COLUMN iot_device.device_id IS '设备 MAC 地址，OTA 下发与建连的设备标识';
COMMENT ON COLUMN iot_device.recording_enabled IS '是否允许该设备进入录音转日志模式（默认关闭，隐私优先）';
```

#### V78__iot_device_credential.sql

```sql
-- 设备凭据（★ 企业级地基：多租户/审计/计费/吊销的唯一支点）
CREATE TABLE iot_device_credential (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    token_hash VARCHAR(128) NOT NULL,         -- 仅存 SHA-256 哈希，明文不落库
    credential_version INT NOT NULL DEFAULT 1,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    rotate_at TIMESTAMPTZ,                    -- 下次轮换时间
    revoked_at TIMESTAMPTZ,                   -- 非空即吊销
    last_used_at TIMESTAMPTZ,                 -- 用于识别僵尸设备
    ip_whitelist VARCHAR(512),
    CONSTRAINT uk_iot_cred_tenant_device_ver UNIQUE (tenant_id, device_id, credential_version)
);
CREATE INDEX idx_iot_cred_device ON iot_device_credential (device_id, revoked_at);

COMMENT ON TABLE  iot_device_credential IS '设备访问凭据（哈希存储，支持轮换宽限与秒级吊销）';
COMMENT ON COLUMN iot_device_credential.token_hash IS 'SHA-256(token) 十六进制；原始 token 仅在签发响应中返回一次';
```

#### V79__iot_session.sql

```sql
CREATE TABLE iot_session (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    agent_id UUID,
    channel_id UUID,                          -- 关联 IM 频道
    user_id UUID,                             -- 发起人（可空 = 设备自触发）
    session_type VARCHAR(16) NOT NULL DEFAULT 'DIALOGUE',  -- DIALOGUE/RECORDING/NOTIFY
    speaker_id VARCHAR(255),                  -- ★ 声纹识别结果（未识别为空）
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at TIMESTAMPTZ,
    duration_ms BIGINT,
    asr_chars INT,
    tts_chars INT,
    llm_tokens INT,
    end_reason VARCHAR(32),                   -- COMPLETED/ABORTED/TIMEOUT/ERROR
    audio_url VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_iot_session_tenant_device_start ON iot_session (tenant_id, device_id, started_at DESC);
CREATE INDEX idx_iot_session_tenant_ended        ON iot_session (tenant_id, ended_at);

COMMENT ON COLUMN iot_session.session_type IS '会话类型：DIALOGUE 对话 / RECORDING 录音转日志 / NOTIFY 单向播报';
COMMENT ON COLUMN iot_session.speaker_id IS '声纹识别的说话人 ID；未识别或未启用声纹为空';
```

#### V80__iot_utterance.sql

```sql
CREATE TABLE iot_utterance (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    session_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL,                -- USER / ASSISTANT
    text TEXT,
    audio_url VARCHAR(512),
    latency_ms INT,
    tokens INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_iot_utterance_session ON iot_utterance (session_id, created_at);

COMMENT ON COLUMN iot_utterance.role IS 'USER=用户语音，ASSISTANT=TTS 回放（录音分析时仅取 USER 段）';
```

#### V81__iot_recording.sql

```sql
CREATE TABLE iot_recording (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    session_id UUID,
    recording_id VARCHAR(64),                 -- 设备侧生成的 UUID（幂等键）
    trigger_type VARCHAR(16) NOT NULL,        -- server/dialog/timer/button/mcp/remote/manual
    source VARCHAR(16) NOT NULL,              -- SERVER 服务端录制 / UPLOAD 控制台上传 / DEVICE 设备上传
    session_type VARCHAR(16) NOT NULL DEFAULT 'DIALOGUE',
    status VARCHAR(16) NOT NULL,              -- PENDING/UPLOADING/DONE/ANALYZING/FAILED
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    duration_ms INT,
    sample_rate INT,
    format VARCHAR(16) DEFAULT 'opus',
    size_bytes BIGINT,
    sha256 VARCHAR(64),
    storage_key VARCHAR(512),                 -- MinIO 对象键
    segment_total INT,                        -- 分段上传总段数
    segment_uploaded INT,                     -- 已上传段数（断点续传进度）
    analysis_id UUID,                         -- → iot_voice_analysis.id
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ                    -- 保留期到期时间（定时任务清理）
);
CREATE INDEX idx_iot_recording_tenant_device ON iot_recording (tenant_id, device_id, created_at DESC);
CREATE INDEX idx_iot_recording_expires        ON iot_recording (tenant_id, expires_at);
CREATE UNIQUE INDEX uk_iot_recording_rid ON iot_recording (tenant_id, recording_id);

COMMENT ON COLUMN iot_recording.trigger_type IS '触发来源：dialog 对话旁路 / timer 定时 / button 按键 / mcp MCP / remote 平台远程 / manual 人工上传';
COMMENT ON COLUMN iot_recording.source IS 'SERVER=服务端录制，UPLOAD=控制台人工上传，DEVICE=设备端上传';
COMMENT ON COLUMN iot_recording.expires_at IS '保留期到期；定时任务按此清理 MinIO 对象与记录';
```

#### V82__iot_voice_analysis.sql

```sql
CREATE TABLE iot_voice_analysis (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    recording_id UUID NOT NULL,
    session_id UUID,
    device_id VARCHAR(128),
    channel_id UUID,
    template_id UUID,                         -- 分析模板（iot_analysis_template）
    transcript TEXT,                          -- 完整转写
    summary TEXT,                             -- AI 摘要
    key_points VARCHAR(2048),                 -- JSON 数组
    action_items VARCHAR(2048),               -- JSON 数组
    sentiment VARCHAR(16),                    -- POSITIVE/NEUTRAL/NEGATIVE
    intent VARCHAR(64),                       -- 意图分类
    risk_flags VARCHAR(512),                  -- JSON 数组
    qa_pairs VARCHAR(4096),                   -- JSON 数组
    llm_model VARCHAR(64),
    prompt_tokens INT,
    completion_tokens INT,
    cost DECIMAL(12,6),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_iot_va_tenant_created ON iot_voice_analysis (tenant_id, created_at DESC);
CREATE INDEX idx_iot_va_device         ON iot_voice_analysis (tenant_id, device_id, created_at DESC);

COMMENT ON COLUMN iot_voice_analysis.template_id IS '所用分析模板；模板决定 prompt 与输出 schema';
COMMENT ON COLUMN iot_voice_analysis.transcript IS 'ASR 完整转写；受保留期策略约束，到期随录音一并清理';
```

#### V83__iot_speaker.sql（★ 声纹，最敏感数据）

```sql
CREATE TABLE iot_speaker (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    speaker_id VARCHAR(255) NOT NULL,         -- 对应 voiceprint-api 的 speaker_id
    speaker_name VARCHAR(128),
    description VARCHAR(512),
    user_id UUID,                             -- 关联平台用户（可选）
    consent_at TIMESTAMPTZ,                   -- ★ 授权时间
    consent_record_id UUID,                   -- ★ 授权凭证 ID（审计可追溯）
    feature_ref VARCHAR(512),                 -- 指向 voiceprint 表的特征存储
    feature_model VARCHAR(64),                -- 模型版本（换模型时可区分/重算）
    sample_rate INT DEFAULT 16000,
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ,                   -- ★ 保留期（默认 90 天）
    revoked_at TIMESTAMPTZ,                   -- 非空即吊销
    CONSTRAINT uk_iot_speaker_tenant_sid UNIQUE (tenant_id, speaker_id)
);
CREATE INDEX idx_iot_speaker_user ON iot_speaker (tenant_id, user_id);

COMMENT ON TABLE  iot_speaker IS '声纹档案（生物识别特征，最高敏感级别）';
COMMENT ON COLUMN iot_speaker.consent_at IS '用户显式授权时间；为空视为未授权，不参与识别';
COMMENT ON COLUMN iot_speaker.feature_ref IS '特征存储引用；特征本身不出现在本表，不可导出';
COMMENT ON COLUMN iot_speaker.expires_at IS '保留期（默认 90 天）；到期自动失效并清理特征';
```

#### V84__voiceprint.sql（voiceprint-api 服务侧，Fork 后改造）

```sql
-- Fork 自 xinnan-tech/voiceprint-api（原表：MySQL voiceprints）
-- 改造点：MySQL→PG（BYTEA）+ 加 tenant_id + 加模型版本
CREATE TABLE voiceprint (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    speaker_id VARCHAR(255) NOT NULL,
    feature_vector BYTEA NOT NULL,            -- PG 替代 LONGBLOB
    feature_model VARCHAR(64),                -- 模型版本标识
    embedding_dim INT,                        -- 特征维度（量化后降传输量）
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_voiceprint_tenant_sid UNIQUE (tenant_id, speaker_id)
);

COMMENT ON TABLE  voiceprint IS '声纹特征向量（3D-Speaker 提取）；仅内部识别使用，不对外导出';
COMMENT ON COLUMN voiceprint.embedding_dim IS '特征维度；量化（float32→int8）后传输量降 4x';
```

### 6.2 动态表（collection，复用 NocoBase 引擎）

| collection | 关键字段 | 用途 |
|---|---|---|
| `iot_firmware` | version, board, sha256, size, url, min_version, released_at | 固件版本管理 |
| `iot_ota_task` | firmware_id, strategy(GROUP/PERCENT/ALL), group_id, rollout_pct, status, progress | OTA 任务 |
| `iot_ota_target` | task_id, device_id, status(PENDING/SUCCESS/FAILED), error, retried | OTA 进度明细 |
| `iot_voice` | name, provider, provider_voice_id, gender, language, sample_url | 音色资源 |
| `iot_provider_binding` | capability(ASR/LLM/TTS/VAD/**VOICEPRINT**), provider, model, priority, fallback_order, api_key_ref, timeout_ms, extra(jsonb) | **多厂商 + 降级链 + 声纹策略** |
| `iot_device_group` | name, description, device_count | 设备分组 |
| `iot_analysis_template` | name, scene, prompt_template, output_schema, enabled | **分析模板可配** |
| `iot_voiceprint_policy` | enabled, similarity_threshold, unknown_speaker_policy, retention_days | **声纹策略** |
| `iot_hotword` | word, weight, scope | ASR 热词（企业术语） |

### 6.3 复用现有表（只加列）

```sql
-- V85__ai_agent_voice.sql
ALTER TABLE ai_agent ADD COLUMN device_id UUID;                    -- 绑定的语音设备
ALTER TABLE ai_agent ADD COLUMN voice_mode VARCHAR(16) DEFAULT 'both';  -- off/speak_only/both
ALTER TABLE ai_agent ADD COLUMN system_prompt TEXT;                 -- 角色设定
ALTER TABLE ai_agent ADD COLUMN kb_ids VARCHAR(512);                -- 绑定知识库（逗号分隔）
COMMENT ON COLUMN ai_agent.voice_mode IS 'off=只在IM回答；speak_only=只对音箱说话不落IM（隐私场景）；both=两者都做';
```

---

## 七、API 契约

### 7.1 设备侧（不走 JWT，走设备凭据）

```
GET/POST /xiaozhi/ota/                       → OTA 检查（下发 websocket + firmware + recording_enabled）
GET     /xiaozhi/ota/download/{fwId}/{file}  → 固件下载（MinIO 预签名直链）
WS      /xz/ws                               → 音频通道（裸 token / Bearer 双兼容）
POST    /api/iot/devices/{deviceId}/recordings/upload         → 分片上传（断点续传）
POST    /api/iot/devices/{deviceId}/recordings/{rid}/complete → 完成并触发分析
```

**`/xiaozhi/ota/` 响应示例**：
```json
{
  "websocket": {
    "url": "wss://voice.example.com/xz/ws",
    "token": "<设备token明文，仅此处下发>",
    "version": 3,
    "recording_enabled": "true"
  },
  "firmware": {
    "version": "1.2.3",
    "url": "https://voice.example.com/xiaozhi/ota/download/xxx/firmware.bin"
  },
  "server_time": { "timestamp": 1762156800, "timezone_offset": 0 }
}
```

### 7.2 控制台侧（JWT + ACL + 审计）

```
# 设备管理
GET    /api/iot/devices?status=&agentId=&groupId=&keyword=
POST   /api/iot/devices                       { name, deviceId, model, groupId }
GET    /api/iot/devices/{id}
PATCH  /api/iot/devices/{id}                  { name, groupId, agentId, recordingEnabled }
DELETE /api/iot/devices/{id}
POST   /api/iot/devices/{id}/bind-agent       { agentId }
GET    /api/iot/devices/{id}/sessions?from=&to=

# 设备凭据（企业级）
GET    /api/iot/devices/{id}/credentials
POST   /api/iot/devices/{id}/credentials/issue
POST   /api/iot/devices/{id}/credentials/{cid}/revoke
POST   /api/iot/devices/{id}/credentials/{cid}/rotate
POST   /api/iot/devices/batch/revoke          { deviceIds[] }

# Agent
GET    /api/iot/agents
POST   /api/iot/agents                       { name, systemPrompt, deviceIds[], voiceMode, providerBindings }
PATCH  /api/iot/agents/{id}
POST   /api/iot/agents/{id}/publish          → 热生效（Redis Pub/Sub）

# OTA
GET    /api/iot/firmware
POST   /api/iot/firmware                     （上传 MinIO）
POST   /api/iot/ota/tasks                    { firmwareId, strategy, groupId, rolloutPct }
GET    /api/iot/ota/tasks/{id}/progress

# ★ 录音转日志模式
POST   /api/iot/devices/{id}/recording/mode    { enabled: true }
POST   /api/iot/devices/{id}/recording/start   { maxSeconds, reason, templateId }
POST   /api/iot/devices/{id}/recording/stop
GET    /api/iot/devices/{id}/recording/status
GET    /api/iot/recordings?deviceId=&sessionType=&from=&to=
GET    /api/iot/recordings/{id}
GET    /api/iot/recordings/{id}/audio        → 播放（MinIO 预签名直链）
POST   /api/iot/recordings/{id}/re-analyze
DELETE /api/iot/recordings/{id}
POST   /api/iot/audio/upload-and-analyze     multipart(file, deviceId?, channelId?)

# ★ AI 分析日志
GET    /api/iot/voice-analysis?deviceId=&templateId=&from=&to=&sentiment=&intent=
GET    /api/iot/voice-analysis/{id}
POST   /api/iot/voice-analysis/search        → 语义检索（pgvector）

# ★ 声纹
GET    /api/iot/speakers?speakerId=&from=
POST   /api/iot/speakers/enroll               { speakerId, name, description, sessionId|recordingId }
DELETE /api/iot/speakers/{id}
POST   /api/iot/speakers/{id}/revoke
GET    /api/iot/speakers/{id}/samples         → 试听样本
GET    /api/iot/voiceprint/policy
PATCH  /api/iot/voiceprint/policy             { enabled, similarityThreshold, unknownSpeakerPolicy, retentionDays }

# Provider
GET    /api/iot/providers
POST   /api/iot/providers/test               → 延迟测试（照抄上游 performance_tester）

# 语音通知出口
POST   /api/notifications/voice              { deviceId, text }
```

### 7.3 voiceprint-api 服务（独立，端口 8005）

```
GET    /voiceprint/health?key=<key>          → { status:"healthy", total_voiceprints }
POST   /voiceprint/identify                  → { speaker_id, score }
       Authorization: Bearer <key>
       multipart: speaker_ids, file
POST   /voiceprint/register                  → 注册（multipart: speaker_id, file）
DELETE /voiceprint/{speaker_id}              → 删除（合规必需）
POST   /voiceprint/enroll-from-session       # ★ DBCool 扩展：从已有会话录音注册
```

### 7.4 错误码（对齐 `contracts/errors.yaml`）

| 场景 | code | http | 说明 |
|---|---|---|---|
| 参数缺失/非法 | 1000 | 400 | `INVALID_PARAMS` |
| 设备凭据无效/过期 | 1001 | 401 | `UNAUTHORIZED`（复用全局码） |
| 跨租户访问设备/声纹 | 1002 | 403 | `FORBIDDEN` |
| 设备/录音/声纹不存在 | 1003 | 404 | `NOT_FOUND` |
| 配额超限（语音分钟/字符/token） | 2xxx | 429 | **本方案新增**，建议 `IOT_QUOTA_EXCEEDED` |
| 声纹未授权 | 2xxx | 403 | **本方案新增**，建议 `VOICEPRINT_NOT_CONSENTED` |
| 录音模式未启用 | 2xxx | 409 | **本方案新增**，建议 `RECORDING_DISABLED` |

> 新增业务错误码需同步更新 `contracts/errors.yaml`（该文件注明「业务错误码由 Cline 在跨栈任务中定义」）。

---

## 八、核心实现（九大模块）

### 8.1 设备身份与凭据治理（企业级地基）

```java
// backend-java/src/main/java/com/nocobase/iot/DeviceCredentialService.java
public interface DeviceCredentialService {
    /** 签发凭据；明文 token 仅在返回值中出现一次，库中只落 SHA-256 */
    String issue(UUID tenantId, String deviceId);

    /** 校验：查哈希 + 未吊销 + 未过期；成功则更新 last_used_at */
    boolean verify(String rawToken);

    /** 轮换：旧版本保留 24h 宽限，避免瞬间批量掉线 */
    void rotate(UUID tenantId, String deviceId);

    /** 吊销：秒级生效 */
    void revoke(UUID tenantId, String deviceId);

    /** 批量禁用（设备丢失/被盗场景） */
    int revokeBatch(UUID tenantId, List<String> deviceIds);
}
```

实现要点：
- token 明文**仅签发时返回一次**，数据库只存 `SHA-256(token)` 十六进制
- 轮换采用**双凭据并存 + 24h 宽限**（新旧 `credential_version` 同时有效）
- `verify` 更新 `last_used_at` → 用于识别僵尸设备
- 全部动作（签发/轮换/吊销/批量禁用/改绑 Agent）接入 `AuditService`
- 语义与既有 `KeyRingService`（rotate/revoke/findByKid）保持一致，**不造第二套密钥体系**

### 8.2 @提及自动触发（核心整合点）

**现状**：`MessageService.send()` 解析 mentions 后仅调用 `notificationService.createMentionNotification(...)`，**不触发 Agent**；只有 `/ai` 斜杠命令会走 `AgentService.executeInChannel`。

```java
// 改动点：MessageService.send() 内，解析 mentions 之后
parsed.mentions().forEach(mention -> {
    notificationService.createMentionNotification(tenantId, mention.userId(), ...);  // 现有逻辑保留
    voiceAgentDispatcher.onMention(tenantId, channelId, mention.userId(), content);   // ★ 新增
});
```

```java
// backend-java/src/main/java/com/nocobase/iot/VoiceAgentDispatcher.java
@Component
public class VoiceAgentDispatcher {

    @Async   // 异步，绝不阻塞 IM 主链路
    public void onMention(String tenantId, UUID channelId, UUID userId, String content) {
        try {
            // 1. 查该频道绑定的 Agent 是否挂了语音设备 → 无则直接返回（现有行为完全不变）
            // 2. 有 → agentService.executeInChannel(tenantId, channelId, userId, content)
            // 3. Agent 输出经 SpeakDeviceTool 推给设备 + MessageService.send 落 IM
        } catch (Exception e) {
            // 4. 异常必须吞掉 + 记审计，绝不影响 IM 主链路
            auditService.log(...);
        }
    }
}
```

> **验收标准**：群里 @小智 → 音箱说话 → IM 出现文字回复，且**未绑定设备时行为与改造前完全一致**（零回归）。

### 8.3 语音出口工具（实现既有 SPI）

```java
// backend-java/src/main/java/com/nocobase/iot/tool/SpeakDeviceTool.java
public class SpeakDeviceTool implements AgentTool {

    @Override public String name() { return "speak_to_device"; }

    @Override public String description() {
        return "把一段文本用语音播报给指定的语音设备（音箱）";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> params, AgentToolContext ctx) {
        // 1. 从 ctx 取 tenantId（跨租户防护，不能跨租户播报）
        // 2. 查设备 → 在线状态 → 绑定 Agent
        // 3. text → TTS → Opus → 经 io-gateway 下行
        // 4. MessageService.send() 落 IM 文本（受 ai_agent.voice_mode 控制）
        return Map.of("deviceId", deviceId, "delivered", true, "durationMs", durationMs);
    }
}
```

> **收益**：DBCool 现有 6 个 `AgentTool`（`QueryDatabaseTool` / `SearchWikiTool` / `CreateTaskTool` / `CreatePageTool` / `SendDingTool` / `SummarizeChannelTool`）**全部免费继承给音箱**。音箱一开口就能「查客户欠款多少」「把这条任务派给张三」。

### 8.4 Provider 降级熔断

```java
// backend-java/src/main/java/com/nocobase/iot/ProviderChain.java
public TtsProvider resolve(String tenantId, Capability cap) {
    IotProviderBinding binding = bindingRepo.findFirstByTenantIdAndCapability(tenantId, cap)
            .orElseThrow(() -> new AllProvidersFailedException(cap));
    for (String p : binding.getFallbackOrder().split(">")) {   // 例：aliyun>edge>custom
        if (circuitBreaker.isOpen(p)) continue;                 // 熔断中的跳过
        return registry.get(p);
    }
    throw new AllProvidersFailedException(cap);
}
```

降级链配置（存 `iot_provider_binding`）：

| capability | 建议降级链 |
|---|---|
| `TTS` | `aliyun_stream` > `edge` > `custom`（本地 CosyVoice） |
| `ASR` | `fun_local` > `doubao_stream` > `openai` |
| `LLM` | `openai`(兼容任意厂商) > `qwen` |
| `VAD` | `silero`（本地，唯一实现） |
| `VOICEPRINT` | `http_api` > `local_3dspeaker`（二期） |

**密钥要求**：`api_key_ref` 必须指向 `integration/credential` 的 AES-GCM 密文（`TenantCredentialService` 已实现），**禁止明文配置**。

### 8.5 会话状态只走 Redis（严禁内存 Map）

```java
// backend-java/src/main/java/com/nocobase/iot/DeviceSessionStore.java
// Redis key: iot:sess:{tenantId}:{deviceId} → JSON(sessionId,type,speakerId,agentId,channelId,startedAt)
// Redis key: iot:route:{deviceId}          → JSON(gatewayInstanceId,updatedAt)
```

> **依据**：仓库 `COMPREHENSIVE_PLATFORM_ASSESSMENT.md` 已记录 `HuddleSignalingHandler` 使用 `Map<String, Set<WebSocketSession>>` 内存路由、而 K8s 配 `replicas:3` + HPA 到 10 → 「生产多副本下语音必然时好时坏」。**本模块不重蹈覆辙。**

### 8.6 声纹：并行识别 + 策略过滤

```python
# backend-python/src/nocobase_py/voice/session.py
class VoiceSession:
    async def handle_utterance(self, pcm: bytes) -> None:
        # ★ 并行：声纹识别 与 ASR 转写 同时进行（上游同此做法）
        vp_task  = asyncio.create_task(self.voiceprint.identify(pcm, self.speaker_ids))
        asr_task = asyncio.create_task(self.asr.transcribe(pcm))
        vp_result, asr_text = await asyncio.gather(vp_task, asr_task, return_exceptions=True)

        # 声纹失败不阻断对话
        match = vp_result if isinstance(vp_result, SpeakerMatch) else None
        decision = self.policy.apply(match)          # strict | warn | anonymous
        if decision.blocked:
            await self.speak("抱歉，未识别到已注册用户")
            return

        # 注入 LLM 上下文：LLM 知道在对谁说话
        ctx = f"当前说话人：{match.name}（{match.description}）" if match else ""
        await self.llm.chat(asr_text, context=ctx)
```

**Provider 目录**（与既有 Provider 体系一致）：

```
backend-python/src/nocobase_py/providers/voiceprint/
  base.py              ← VoiceprintProvider 抽象（签名与上游对齐，便于直接替换）
  http_api.py          ← 调用自建 voiceprint-api（默认）
  cloud.py             ← 云厂商声纹 API（可选）
  local_3dspeaker.py   ← 本地 ONNX 推理（二期，目标 <50ms）
```

**与上游的差异改进**：

| 上游 `voiceprint_provider.py` | 本方案改造 |
|---|---|
| `identify_speaker()` 返回字符串名 | 返回结构化 `SpeakerMatch(speaker_id, name, score, description)` |
| 同步 `requests` 做健康检查 | 改 `aiohttp` 异步 |
| 无租户概念 | 加 `tenant_id` |
| 识别失败返回 `"未知说话人"` | 返回 `None` + 原因枚举（供策略决策） |
| 阈值固定 0.4 | 可配（`iot_voiceprint_policy.similarity_threshold`，默认 **0.45**） |

**策略配置**（存 `iot_voiceprint_policy`）：

| 策略 | 行为 | 适用 |
|---|---|---|
| `strict` | 未注册 → **拒绝对话**，播报「请先注册」 | 高安全（设备含敏感数据） |
| `warn` | 未注册 → 播报「未识别到已注册用户」+ 不返回敏感数据 | **企业默认** |
| `anonymous` | 未注册 → 正常对话，无个性化 | 公共区域 |

### 8.7 声纹数据的企业级要求（**最易忽略、最关键**）

> 声纹 = **生物识别特征**，敏感度**高于普通个人信息**（《个人信息保护法》敏感个人信息；GDPR 特殊类别数据）。

| 要求 | 实现 | 复用 |
|---|---|---|
| **加密存储** | 特征向量加密存储；不落明文日志 | `integration/credential` AES-GCM 模式 |
| **明确授权** | 注册需显式同意 + 留痕（`consent_at` / `consent_record_id`） | `iot_speaker` + `audit/` |
| **可删除** | 用户数据导出/删除必须覆盖声纹 | `compliance/UserDataErasureService`（**要补这一项**） |
| **保留期** | 默认 90 天，可配；离职/退租自动失效 | `expires_at` + 定时任务 |
| **不可导出** | 特征**不应可导出**（不可逆生物特征） | `exportUserData` 需**排除或脱敏** |
| **访问审计** | 谁注册/删除/查看了声纹 | `AuditService` |
| **租户隔离** | A 租户不能识别 B 租户说话人 | `tenant_id` + 补 `tenant-isolation-e2e-verify.py` 场景 |
| **禁用开关** | 租户级总开关 | `iot_voiceprint_policy.enabled` |
| **未授权不参与** | `consent_at` 为空 → 不纳入 `speaker_ids` | Provider 侧过滤 |

> **建议**：控制台「隐私与合规」页明确展示「已注册声纹列表 + 授权状态 + 删除入口」——这是企业采购必过项。

### 8.8 录音 → AI 分析 → 日志

```java
// backend-java/src/main/java/com/nocobase/iot/RecordingWriter.java
@Component
public class RecordingWriter {
    // 会话开始 → createRecording(sessionId, deviceId)
    // 每收到一帧 → 流式写 MinIO（不全量进内存）
    // 会话结束 → closeRecording() → 发 RecordingReadyEvent
}
```

**关键实现细节**：
- **流式写 MinIO**：1 小时会议 PCM 约 230MB，**不可全量入内存**
- **只存 Opus 原始流**：16k/24k mono ≈ 3KB/s → 1 小时 ≈ 10.8MB
- 需要 WAV 时**离线转码**，不在实时路径做格式转换
- 存 `sha256` 用于去重与完整性校验
- `expires_at` = now + 租户保留期

```python
# backend-python/src/nocobase_py/voice/analysis.py
class VoiceAnalysisPipeline:
    async def analyze(self, recording_key: str, opts: AnalysisOptions) -> VoiceAnalysis:
        # 1. 从 MinIO 拉取音频（Opus）
        # 2. ASR 转写（复用 providers/asr：sherpa_onnx_local / fun_local / openai）
        # 3. LLM 结构化分析（模板来自 iot_analysis_template）
        # 4. 落库 iot_voice_analysis
        # 5. 触发四出口（见 §8.9）
```

**Prompt 模板**（存 `iot_analysis_template`，租户可定制）：

```text
你是一位企业会议/客服质检分析助手。请分析以下对话转写，输出严格的 JSON：
{
  "summary": "不超过150字的摘要",
  "key_points": ["要点1", "要点2"],
  "action_items": [{"task": "任务描述", "owner": "负责人(可空)", "due": "截止时间(可空)"}],
  "sentiment": "POSITIVE|NEUTRAL|NEGATIVE",
  "intent": "意图分类标签",
  "risk_flags": ["发现的风险点"],
  "qa_pairs": [{"q": "问题", "a": "回答"}]
}

对话转写：
{transcript}
```

**可配分析场景**：

| 场景模板 | 分析重点 |
|---|---|
| 会议纪要 | 决议、待办、责任人 |
| 客服质检 | 响应时长、情绪、共情表达、违规话术 |
| 设备巡检 | 异常声音、故障描述、环境噪音等级 |
| 培训考核 | 知识点覆盖、标准话术偏离度 |
| 安全巡检 | 风险项、违规行为、区域 |

### 8.9 分析结果四出口

```
① iot_voice_analysis 表  → 结构化数据（可查、可统计、可做 BI）
② Wiki 页面              → 自动生成纪要文档，纳入 pgvector 语义检索
③ IM 消息回帖            → 摘要贴回原频道（受 ai_agent.voice_mode 控制）
④ 工作流触发             → iot.voice.analysis.completed → 派单/告警/钉钉/语音播报
```

> **第 ④ 条最有价值**（`workflow` 引擎现成）：
> ```
> 触发器: iot.voice.analysis.completed
> 条件:   risk_flags 非空
> 动作:   创建任务(指派安全负责人) + 钉钉通知 + 语音播报(设备在线时)
> ```

### 8.10 语音数据保留期与合规

```java
// backend-java/src/main/java/com/nocobase/iot/IotRetentionJob.java
@Scheduled(fixedDelay = 3600_000)
public void cleanup() {
    // 0. 取 Redis 分布式锁（照抄 workflow/WorkflowScheduler 模式，避免多副本重复执行）
    // 1. 查 expires_at 过期的 iot_recording → 删 MinIO 对象 + 删记录
    // 2. 查 expires_at 过期的 iot_speaker → 吊销 + 删 voiceprint 特征
    // 3. 记 UserDataErasureLog（compliance 模块）
    // 4. 保留期：录音 30 天 / 声纹 90 天 / 质检 7 天（按租户可配）
}
```

**顺手修既有假实现**：`compliance/UserDataErasureService.eraseAiConversations()` 当前只 `log.warn` + `incrementConversationsErased(0)` → 改为真实删除，并覆盖 `iot_recording` / `iot_utterance` / `iot_voice_analysis` / `iot_speaker` / `voiceprint`。

---

## 九、企业级能力清单

| 能力域 | DBCool 现状 | 需补 | 优先级 | 复用来源 |
|---|---|---|---|---|
| 多租户隔离 | ✅ Schema 级 | 设备/声纹绑定租户 | P0 | `tenant/` |
| **设备身份** | ❌ | **凭据签发/轮换/吊销/批量禁用** | **P0** | `KeyRingService` 语义 |
| **API 限流** | ✅ `GlobalRateLimitFilter` | 设备维度 + 录音模式限流 | P0 | `ratelimit/` |
| **配额** | ✅ 请求次数 | **语音分钟/字符/token + 硬上限** | **P0** | `quota/` |
| **计费** | ✅ 订阅/发票 | 语音用量套餐 | P0 | `billing/` |
| **密钥托管** | ✅ AES-GCM | 模型厂商 + 声纹服务 key | P0 | `integration/credential` |
| **合规删除** | ⚠️ 假实现 | 音频/声纹/会话删除 + 保留期 | **P0** | `compliance/` |
| **声纹合规** | ❌ | 授权/可删/不可导出/审计 | **P0** | 新增 + `audit/` |
| 审计 | ✅ 39+ 调用点 | 语音事件 + 录音调听 + 声纹变更 | P1 | `audit/` |
| **降级熔断** | ❌ | Provider fallback 链 | **P1** | 新增 |
| **可观测** | ✅ Prometheus/Grafana | 语音专属指标看板 | P1 | `observability/` |
| 固件灰度 | ✅ 蓝绿 | **设备分组 OTA 灰度** | P1 | `workflow/` |
| 设备影子/边缘 | ❌ | 客户侧边缘网关兜底 | P2 | — |
| 固件签名 | ❌ | 安全启动 | P2 | `docs/secrets-management.md` |

### 9.1 语音专属指标（加到 `observability/prometheus.yml` + `alerts.yml`）

| 指标 | 类型 | 用途 |
|---|---|---|
| `iot_active_sessions` | Gauge | 并发会话数（容量核心） |
| `iot_session_duration_seconds` | Histogram | 会话时长分布 |
| `iot_asr_latency_ms` | Histogram | ASR 延迟 |
| `iot_tts_first_byte_ms` | Histogram | **首字延迟（语音产品生死线）** |
| `iot_e2e_latency_ms` | Histogram | 唤醒→出声端到端 |
| `iot_provider_error_total` | Counter | 按厂商错误数 |
| `iot_sessions_aborted_total` | Counter | 打断率（体验异常信号） |
| `iot_voiceprint_score` | Histogram | 声纹相似度分布 |
| `iot_voiceprint_unknown_total` | Counter | **未识别率（噪音/误触发监控）** |

### 9.2 建议告警规则（`observability/alerts.yml`）

```yaml
- alert: IotTtsFirstByteHigh
  expr: histogram_quantile(0.95, rate(iot_tts_first_byte_ms_bucket[5m])) > 2000
  for: 5m
  labels: { severity: warning }
  annotations:
    summary: "TTS 首字延迟 P95 > 2s"

- alert: IotProviderErrorRateHigh
  expr: |
    sum(rate(iot_provider_error_total[5m])) by (provider)
      / sum(rate(iot_sessions_total[5m])) by (provider) > 0.05
  for: 5m
  labels: { severity: critical }
  annotations:
    summary: "模型厂商 {{ $labels.provider }} 错误率 > 5%"

- alert: IotVoiceprintUnknownRateHigh
  expr: rate(iot_voiceprint_unknown_total[10m]) / rate(iot_sessions_total[10m]) > 0.3
  for: 10m
  labels: { severity: warning }
  annotations:
    summary: "声纹未识别率 > 30%，疑似环境噪音过高或设备被误唤醒"
```

---

## 十、实施路线

### 10.1 阶段总表

| 阶段 | 时长 | 交付 | 硬件 | 验收（可证伪） |
|---|---|---|---|---|
| **P0 协议固化** | 2 天 | `docs/XIAOZHI_PROTOCOL_SPEC.md` + `scripts/xiaozhi-sim/` 模拟器 | ❌ | 模拟器完成 hello→listen→上传 Opus→收 TTS 全程 |
| **P0b 企业级地基** | 3 天 | `DeviceCredentialService` + V78 迁移 + 审计接入 | ❌ | 签发/校验/轮换/吊销/批量禁用 5 条流程全通 + 审计可查 |
| **P1 接入层** | 5 天 | `OtaController` + `DeviceGateway` + `DeviceSessionStore`(Redis) + V77 迁移 | ❌ | 模拟器/真机连上，音频双向通，`iot_device` 状态实时更新 |
| **P1b 真机验证** | 3 天 | ESP32-S3-BOX-3 接入 | ✅ | 设备串口日志出现 `Connecting to websocket server: wss://... with version: 3` |
| **P2 AI 编排** | 7 天 | `backend-python` voice 模块 + ASR/LLM/TTS Provider 抽象 | ❌ | 首字延迟 <1.5s，端到端 <3s（本地引擎） |
| **P2b 录音旁路** | 2 天 | `RecordingWriter` + V81 迁移 | ❌ | 对话音频落 MinIO，`iot_recording.status=DONE` |
| **P2c 声纹服务** | 3 天 | Fork `voiceprint-api` → PG + 多租户 + Docker 化 | ❌ | identify/register 可用，跨租户识别被拒 |
| **P2d 声纹集成** | 2 天 | `providers/voiceprint/` + 并行 ASR + 策略引擎 | ❌ | 未注册说话人按策略被过滤，对话不中断 |
| **P3 IM 整合** | 5 天 | `VoiceAgentDispatcher` + `SpeakDeviceTool` + IM 回写 | ❌ | 群里 @小智 → 音箱说话 → IM 出文字 |
| **P3b 声纹注册 UI** | 3 天 | 从会话录音注册 + 声纹管理页 | ❌ | 可注册/试听/删除，授权状态可见 |
| **P4 控制台** | 7 天 | 设备/Agent/OTA/会话/录音 5 页 + 上传即分析 | ❌ | 非技术用户能独立完成「配 Agent → 设备说话 → OTA 升级」 |
| **P5 企业级收口** | 5 天 | 保留期任务 + 配额硬上限 + 熔断降级 + 指标看板 | ❌ | 厂商故障自动降级；超配额拒绝新会话 |
| **P6 AI 分析日志** | 3 天 | `VoiceAnalysisPipeline` + 模板 + 四出口 | ❌ | 录音 → 结构化日志 → Wiki/IM/工作流三处可见 |
| **P7 声纹合规** | 2 天 | 授权 + 删除 + 不可导出 + 审计 | ❌ | 用户数据删除覆盖声纹与录音 |
| **P8 录音新模式** | 5 天 | 固件 `kDeviceStateRecording` + MCP 工具 + 4 触发 | ✅ | 定时录音生效，平台可远程启动 |
| **P9 设备端存储** | 8-12 天 | 固件 `xz_recorder` + TF 卡 + 分片上传 | ✅ | 定时录音→断点续传→自动分析 |
| **P10 增强** | 持续 | MQTT+UDP、本地 ONNX 声纹、多型号、边缘网关 | ✅ | — |

### 10.2 并行路径

```
第1周: P0(协议+模拟器) ──┐
        P0b(设备凭据)    ├──► P1(接入层) ──► P1b(真机) ──► P2(AI编排) ──► P2b(录音旁路)
第2周: P2c(声纹服务) ◄── P2                    P5前置(Provider/熔断框架)
第3周: P3(IM整合) ◄── P2   P3b(声纹UI) ◄── P2d    P4(控制台) ◄── P1
第4周: P5(企业级收口) + P6(AI分析日志) + P7(声纹合规)
第5周+: P8/P9(固件能力，可选)
```

### 10.3 MVP 范围（4-5 周必达）

| 做 | 不做 |
|---|---|
| WebSocket 通道 | MQTT+UDP |
| 2-3 种硬件（BOX-3 / 第三方 C3 / CoreS3） | 138 变体 |
| ASR/LLM/TTS 各 2 家云 API + 1 家本地 | 全量 40+ Provider |
| @提及触发 + 语音出口 + IM 回写 | 声纹（可并行推进） |
| **服务端旁路录音 + 上传即分析 + AI 日志** | 设备端 TF 卡录音（P9） |
| 设备凭据治理 + 审计 | 设备影子/边缘网关 |
| 5 页控制台 | 完整 BI 报表 |
| 多租户隔离 | — |

### 10.4 设备端录音（P9）技术细节

#### 硬件事实（实测 `boards/waveshare/esp32-s3-epaper-1.54`）✅

| 项 | 实测值 |
|---|---|
| 变体 | v1（4MB Flash / 2MB PSRAM）、**v2（8MB / 8MB）** ← 推荐 |
| 屏幕 | 200×200 e-paper，SPI（DC=10, CS=11, SCK=12, MOSI=13, RST=9, BUSY=8） |
| 麦克风 | **ES8311**，I2S（MCLK=14, WS=38, BCLK=15, DIN=16, DOUT=45），I2C(47/48) |
| **采样率** | **24000 Hz**（注意！非 16k） |
| **电源域** | **三个独立可关**：EPD(GPIO6) / Audio(GPIO42) / VBAT(GPIO17) |
| 已有低功耗 | 长按电源键 → `PowerAudioOff + PowerEpdOff + VbatPowerOff` |
| TF 卡槽 | **官方 board 目录无 SDMMC 驱动** → 需实物确认 + 自定义引脚 |

> ⚠️ **引脚冲突**：GPIO 8-13(EPD SPI) / 14-16,38,45(I2S) / 46-48(I2C) / 17,18,42,6(电源) 已占。TF 卡需 4 个额外 GPIO，可能需改板级定义。

#### 存储容量（必须用 Opus —— 固件已有编码器可复用）

| 格式 | 码率 | 1 小时 | 32GB TF 卡 |
|---|---|---|---|
| WAV 24kHz 16bit mono | 384 kbps | 173 MB | ~3 小时 ❌ |
| **Opus 24kHz** | 24 kbps | **10.8 MB** | **~49 小时** ✅ |

#### ⚠️ 功耗现实（决定策略）

| 项 | 数据 |
|---|---|
| 2000mAh 电池 | 7.4 Wh |
| 录音（屏幕关、Wi-Fi 关） | ~80-120mA → **约可录 15-18 小时** |
| 上传（弱网） | 每段 5 分钟 Opus ≈ 900KB，耗电显著 |
| **结论** | **必须「定时唤醒 → 录 N 分钟 → 深睡 → 下次唤醒上传」**，不能常开 |

> **存储不是瓶颈，电量和弱网才是。** 建议：每天定点录 3-5 段 × 3-5 分钟，或**声音阈值触发**。

#### TF 卡目录结构与元数据

```
/xz/rec/2026-11/03/
  20261103_142530_001.opus    ← 分段录音，每段 ≤ 5 分钟
  20261103_142530_001.json    ← 元数据
  index.json                  ← 索引（段数/时长/状态）
```

```json
{
  "recording_id": "uuid", "device_id": "mac",
  "started_at": "2026-11-03T14:25:30Z", "duration_ms": 300000,
  "sample_rate": 24000, "format": "opus",
  "segments": [{"index": 0, "size": 900000, "sha256": "abc...", "uploaded": false}],
  "trigger": "timer|button|mcp|remote", "status": "pending_upload"
}
```

**分段理由**：避免大文件上传失败、支持断点续传、支持边录边传。

#### 上传协议（**不用小智 WS 音频通道传文件** —— 那是实时 Opus 流，语义不同）

```
POST /api/iot/devices/{deviceId}/recordings/upload
Content-Type: application/octet-stream
X-Recording-Id: <uuid>          X-Segment-Index: <n>
Content-Range: bytes <start>-<end>/<total>
→ 204 / 206
```
断点续传天然支持、可重试、不占实时音频通道。

#### 固件组件

```
components/xz_recorder/
  xz_recorder.h/.cc     录音状态机（RECORDING → UPLOADING → IDLE）
  xz_storage.h/.cc      存储抽象（SD / 不支持）
  xz_uploader.h/.cc     断点续传上传
  xz_manifest.h/.cc     索引文件
```

---

## 十一、风险登记

| # | 风险 | 等级 | 概率 | 缓解措施 | 触发兜底 |
|---|---|---|---|---|---|
| R1 | 固件协议演进失配 | 🔴 高 | 中 | 锁固件 `version=3`；订阅 upstream release；协议一致性测试集做 CI 门禁 | 降级 v1 裸 Opus |
| R2 | 语音并发下延迟劣化 | 🔴 高 | 高 | 压测前置；Provider 超时熔断；本地 TTS 降级 | 限制单租户并发会话数 |
| R3 | **公网带宽成本超预期** | 🔴 高 | 中 | 上下行分离限速；租户配额；TTS 本地化 | 按分钟计费封顶 |
| R4 | 厂商故障导致全站不可用 | 🟡 中 | 中 | Provider fallback 链 + 熔断器 + 告警 | 固定话术兜底 |
| R5 | 多租户设备/声纹越权 | 🔴 高 | 低 | 凭据绑定租户；网关校验 tenant；补 `tenant-isolation-e2e-verify.py` 设备与声纹场景 | — |
| R6 | 语音数据合规不过法务 | 🔴 高 | 中 | 录音模式默认关 + 显式开启 + 声光提示；保留期；声纹授权/可删/不可导出；修 `eraseAiConversations` 假实现 | — |
| R7 | 账单爆炸（恶意唤醒/死循环） | 🟡 中 | 中 | 配额硬上限（分钟/字符/token 三维度） | 超限拒绝新会话 |
| R8 | 网关单点 | 🟡 中 | 低 | 会话状态 Redis；设备路由表 Redis；网关可水平扩容 | 设备自动重连其他实例 |
| R9 | **噪音环境唤醒失败** | 🔴 高 | 高 | **优先换双麦硬件**（贡献 >60%）；服务端声纹过滤 + 关键词兜底；量化实测唤醒率 | 引导用户走近设备 / 改用按键 |
| R10 | 声纹误识别（认错人） | 🟡 中 | 中 | 阈值提高至 0.45+；`warn` 策略不泄露敏感数据；识别不确定时不个性化 | 询问用户确认身份 |
| R11 | 固件分叉维护负担 | 🟡 中 | 高 | 只对 1-2 种板型做分支；其余继续用官方固件 | 放弃 P8/P9，走旁路方案 |
| R12 | e-paper 板 TF 卡引脚冲突 | 🟡 中 | 中 | 先买 1 台实物验证引脚可用性 | 改用 `esp32-s3-audio-board` |
| R13 | 设备端录音功耗不达标 | 🟡 中 | 中 | 定时唤醒 + 深睡；分段上传；事件触发录音 | 只保留旁路 + 人工上传 |
| R14 | 契约门禁漂移导致 CI 红 | 🟢 低 | 中 | 每次新增端点重跑 `export-backend-mappings.py` + `extract-frontend-mappings.py` | — |

---

## 十二、采购清单

| 优先级 | 物品 | 价量级 | 用途 |
|---|---|---|---|
| 🥇 | **ESP32-S3-BOX-3**（乐鑫官方，双麦 + 带屏） | ¥150-250 | 主力开发；**双麦对噪音唤醒最关键**；文档最全、社区问题好查 |
| 🥇 | **ESP32-S3-KORVO-2-V3.0**（2 麦专用语音板） | ¥400+ | **噪音场景对比测试** |
| 🥇 | **微雪 ESP32-S3-ePaper-1.54 v2**（8MB Flash + 8MB PSRAM） | ¥120-200 | 低功耗录音节点（**单麦，噪音场景不推荐**）；验证 TF 卡槽可行性 |
| 🥇 | TF 卡 32GB class10 | ¥25 | 录音存储 |
| 🥈 | 淘宝第三方 ESP32-C3 板 ×2 | ¥30×2 | 多设备并发测试 |
| 🥈 | 微雪 ESP32-S3-Audio-Board | ¥100-150 | TF 卡槽冲突时的备选 |
| 🥈 | 声学测试工具（分贝仪 / 声级计） | ¥50-200 | **量化测噪音环境唤醒率** |
| 🥉 | M5Stack CoreS3 | ¥200-400 | 带屏验证 IM 消息显示 |

> **强烈建议**：**同时买 BOX-3 和 KORVO-2**，用同一套测试量化对比唤醒率 —— 这比任何软件优化都直观。
>
> **采购前必查**：e-paper 板是否有 TF 卡槽（官方 board 目录无 SDMMC 驱动代码，需查商品页实物图）。

### 12.1 噪音环境唤醒率实测方法论

```
1. 准备：音箱置于目标场景（如 60dB 车间），距离 1m / 3m 各一组
2. 噪声源：固定音量白噪声 / 播放他人语音 / 空调噪声（分别记录 dB）
3. 唤醒尝试：每组 30 次，记录成功次数与首次响应延迟
4. 对比：BOX-3 vs KORVO-2 vs 单麦第三方板
5. 指标：召回率 = 成功/总；误触发率 = 非指令语音触发/总
6. 验收目标：60dB 环境 ≥1m 召回率 ≥90%，误触发率 ≤5%
```

---

## 十三、需拍板事项

| # | 问题 | 我的推荐 | 状态 |
|---|---|---|---|
| 1 | 从哪开始？ | **P0 协议固化**（不依赖硬件、不返工） | 待定 |
| 2 | 网关同进程 vs 独立？ | **同进程**，会话状态从第一天存 Redis | 待定 |
| 3 | 模拟器选哪个？ | `py-xiaozhi`（3.5k star，与 `backend-python` 同栈） | 待定 |
| 4 | 设备凭据算法？ | SHA-256 哈希 + 双凭据宽限轮换 | 待定 |
| 5 | 录音模式默认开/关？ | **默认关**，租户级开关 + 声光提示（隐私优先） | 待定 |
| 6 | 录音格式？ | **Opus 原始流**，WAV 离线转码 | 待定 |
| 7 | 默认保留期？ | 录音 30 天 / 声纹 90 天 / 质检 7 天 | 待定 |
| 8 | 分析模板固定/可配？ | **可配**（`iot_analysis_template`） | 待定 |
| 9 | 声纹服务自建还是云？ | **自建 Fork**（Apache-2.0，数据不出域，合规必需） | 待定 |
| 10 | 声纹存储 PG 还是独立 MySQL？ | **PG**（复用 DBCool，多租户 schema 隔离） | 待定 |
| 11 | 未注册说话人策略默认？ | **`warn`**（企业可用性优先），高安全租户切 `strict` | 待定 |
| 12 | 声纹相似度阈值默认？ | **0.45**（比上游 0.4 更严，减少误识别） | 待定 |
| 13 | 声纹是否可导出？ | **不可导出**（生物特征不可逆） | 待定 |
| 14 | 固件改不改？ | **先不改**（P2b 旁路 + P4 上传），验证价值后再决定 P8/P9 | 待定 |
| 15 | `voice_mode` 默认？ | `both`（音箱说话 + IM 留痕） | 待定 |
| 16 | 会话数据是否合并存储？ | **不合并**：IM 用 `ai_conversation`，语音用 `iot_session`，`conversationId` 关联 | 待定 |
| 17 | 噪音唤醒投入方向？ | **先换硬件**（贡献 >60%），软件做服务端兜底 | 待定 |

---

## 十四、验收标准

### 14.1 分阶段验收（可证伪）

| 阶段 | 验收命令/动作 | 通过标准 |
|---|---|---|
| P0 | `python3 scripts/xiaozhi-sim/sim.py --scenario full` | 模拟器完成 hello→listen→上传 Opus→收 TTS，**退出码 0** |
| P0b | `mvn -o test -Dtest=DeviceCredential*` + 审计查询 | 5 条流程全通；审计表可查到签发/轮换/吊销记录 |
| P1 | `mvn -o test -Dtest=IotOtaController*,DeviceGateway*` | OTA 响应结构正确；裸 token 与 Bearer 均能通过握手 |
| P1b | 真机串口日志 | 出现 `Connecting to websocket server: wss://<域名>/xz/ws with version: 3` |
| P2 | 压测 + 指标 | 首字延迟 P95 <1.5s；端到端 P95 <3s |
| P2b | `GET /api/iot/recordings?deviceId=xxx` | `status=DONE`；MinIO 对象存在；`sha256` 可校验 |
| P2c | `curl -H "Authorization: Bearer $KEY" -F file=@a.wav $VP/voiceprint/identify` | 返回 `speaker_id` 与 `score`；跨租户请求返回 403 |
| P2d | 对未注册说话人说话 | 按策略被过滤；对话不中断；`iot_session.speaker_id` 为空 |
| P3 | 群内 @小智 | 音箱在 3s 内说话；IM 出现文字回复；**未绑定设备时行为与改造前一致** |
| P4 | 人工验收 | 非技术用户 10 分钟内完成「配 Agent → 设备说话 → OTA 升级」 |
| P5 | 手动 kill 一个 Provider 进程 | 自动降级到备用 Provider；触发告警 |
| P6 | 上传 10 分钟录音 | 生成结构化日志；Wiki/IM/工作流三处均可见 |
| P7 | 删除用户数据 | 录音、声纹、特征向量、会话全部清除；`UserDataErasureLog` 有记录 |

### 14.2 CI 门禁（必须全绿，阈值见 `docs/CI_GATES.md`）

```bash
# Java
cd backend-java && mvn -o test            # 0 failures / 0 errors
cd backend-java && mvn -o verify           # JaCoCo BUNDLE ≥ 82%
# 前端
cd frontend && npm run test:run           # 全部通过
cd frontend && npx tsc --noEmit           # 0 errors
cd frontend && npx playwright test        # E2E
# Python
cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q
# 契约与专项
python3 scripts/api-contract-verify.py       # 0 漂移
python3 scripts/tenant-isolation-e2e-verify.py   # 全 PASS（含新增设备/声纹场景）
python3 scripts/smoke.py                     # 11/11
```

### 14.3 新增契约端点登记（必须）

| 项 | 内容 |
|---|---|
| 新增后端端点数 | 见 §7.2（设备 ~25 + 凭据 5 + Agent 4 + OTA 4 + 录音 8 + 分析 3 + 声纹 7 + Provider 2） |
| 新增前端调用数 | 待前端实现后统计 |
| 登记动作 | `python3 scripts/export-backend-mappings.py` → `python3 scripts/extract-frontend-mappings.py` → `python3 scripts/api-contract-verify.py` |
| 错误码登记 | 在 `contracts/errors.yaml` 补 3 个业务码（`IOT_QUOTA_EXCEEDED` / `VOICEPRINT_NOT_CONSENTED` / `RECORDING_DISABLED`） |

### 14.4 新增验证脚本

| 脚本 | 覆盖 |
|---|---|
| `scripts/xiaozhi-sim/sim.py` | 协议模拟器（hello/listen/Opus/TTS） |
| `scripts/iot-e2e-verify.py` | 设备接入 → 会话 → 录音 → 分析 → 日志全链路 |
| `scripts/voiceprint-e2e-verify.py` | 声纹注册 → 识别 → 策略过滤 → 删除 |

### 14.5 上线验收清单（对齐 `docs/acceptance-checklist.md`）

```
□ 生产环境已启用备份（pg_dump + WAL 归档）并演练过一次恢复
□ 模型厂商与声纹服务密钥已走 integration/credential（AES-GCM），无明文配置
□ 录音模式默认关闭，租户显式开启；设备有声光提示
□ 保留期策略已配置（录音 30 天 / 声纹 90 天）
□ 声纹授权与删除入口在控制台可见
□ 设备凭据轮换已配置（rotate_at）
□ 配额硬上限已配置（分钟/字符/token）
□ Provider 降级链已配置并验证
□ 语音指标看板已接入，告警规则已生效
□ 契约门禁 0 漂移，CI 全绿
```

---

## 十五、固件改动清单（二期，需自建分支）

> 仅 P8 / P9 需要。**主线（P0-P7）零固件改动。**

### 15.1 P8：新增录音模式

| 文件 | 改动 | 位置 |
|---|---|---|
| `main/device_state.h` | 新增枚举值 `kDeviceStateRecording` | `enum DeviceState` 内，`kDeviceStateNotifying` 之后 |
| `main/device_state_machine.cc` | `IsValidTransition` 补 3 条迁移 | 与既有 `kDeviceStateNotifying` 迁移规则并列 |
| `main/protocols/protocol.h` | 新增 `enum RecordingMode` | 与 `enum ListeningMode` 同处 |
| `main/application.h` | 新增 `ContinueOpenRecordingChannel()` / `StartRecording()` / `StopRecording()` | 私有方法区，`ContinueOpenAudioChannel` 附近 |
| `main/application.cc` | `OnStateChanged` 增加 `kDeviceStateRecording` 分支；`HandleToggleChatEvent` 增加互斥 | 现有 `kDeviceStateNotifying` 分支旁 |
| `main/mcp_server.cc` | `AddUserOnlyTools` 增加 `self.recorder.start/stop/status` | 与 `self.reboot` 同类 |
| `main/boards/waveshare/esp32-s3-epaper-1.54/*.cc` | 按键长按触发（可选） | `InitializeButtons()` |

**状态迁移图（新增部分）**：
```
[*] → Idle → Recording → Idle
                  └→ Listening   (随时切回对话)
```

### 15.2 P9：设备端存储

| 文件 | 改动 |
|---|---|
| `main/idf_component.yml` | 新增 `espressif/esp_vfs_fatfs`、SDMMC 依赖 |
| `components/xz_recorder/` | 新增 4 个文件对（recorder / storage / uploader / manifest） |
| `main/boards/<board>/config.h` | 新增 TF 卡 SDMMC 引脚定义（需避开已占 GPIO） |
| `main/boards/<board>/<board>.cc` | 初始化 SDMMC、挂载 FATFS |

### 15.3 固件分支策略

| 规则 | 说明 |
|---|---|
| **只对 1-2 种板型做分支** | 其余继续用官方固件，避免维护 171 变体矩阵 |
| **保留官方 OTA 通道** | 私有分支需自行实现 OTA，或 OTA 到你的私有 OTA 地址（`ota_url` 可写） |
| **CI 要求** | 编译 + 烧录 + 至少 1 条真机回归（唤醒/对话/录音） |
| **回退预案** | 保留官方固件烧录路径，一条命令回退 |

---

## 十六、附录

### 16.1 协议速查表

| 项 | 值 |
|---|---|
| OTA 检查路径 | `GET/POST /xiaozhi/ota/` |
| WebSocket 路径 | `WS /xz/ws` |
| 握手头 | `Authorization`（裸 token **或** `Bearer <token>`）、`Protocol-Version`、`Device-Id`、`Client-Id` |
| 帧版本 | v1 裸 Opus / v2 16 字节头 / **v3 4 字节头（默认）** |
| 上行音频 | 16kHz mono 60ms Opus |
| 下行音频 | 24kHz mono 60ms Opus |
| 会话超时 | 120s 无包（协议侧 `IsTimeout()`） |
| hello 等待 | 10s（`WEBSOCKET_PROTOCOL_SERVER_HELLO_EVENT`） |
| 消息类型 | `hello` `listen` `abort` `stt` `tts` `llm` `mcp` `system` `alert` `custom` `notify` `goodbye` |
| MCP 方法 | `initialize` / `tools/list`（含 cursor 分页）/ `tools/call` / `notifications/*` |

### 16.2 设备端 MCP 工具全集（`main/mcp_server.cc` 实测）

```
self.get_device_status          self.audio_speaker.set_volume
self.screen.set_brightness      self.screen.set_theme
self.camera.take_photo
self.get_system_info  ★user-only self.reboot  ★user-only
self.upgrade_firmware ★user-only self.screen.get_info  ★user-only
self.screen.snapshot  ★user-only self.screen.preview_image ★user-only
self.assets.set_download_url ★user-only
```

### 16.3 术语表

| 术语 | 含义 |
|---|---|
| **OTA 下发地址** | 固件通过 OTA 检查接口获取服务器地址并写入 NVS 的机制 —— 本方案的接入杠杆 |
| **NVS** | ESP-IDF 的非易失存储，`Settings` 类封装 |
| **旁路录制** | 不改固件，服务端在音频流经时同步落盘 |
| **监听模式** | `ListeningMode`：AutoStop / ManualStop / Realtime |
| **AFE** | Audio Front End，音频前端处理（AEC/NS/AGC/VAD） |
| **AEC** | Acoustic Echo Cancellation，回声消除（设备端或服务端二选一） |
| **VAD** | Voice Activity Detection，语音活动检测 |
| **RAG** | Retrieval-Augmented Generation，检索增强生成 |
| **KWS** | Keyword Spotting，关键词唤醒（服务端降级手段） |
| **barge-in** | 打断，用户说话时中断 TTS 播报 |
| **Provider 降级链** | `fallback_order` 定义的模型厂商优先级序列 |

### 16.4 参考仓库

| 用途 | 仓库 |
|---|---|
| 固件与协议 SSOT | `https://github.com/78/xiaozhi-esp32` |
| 开源控制台对标 | `https://github.com/xinnan-tech/xiaozhi-esp32-server` |
| 协议模拟器 | `https://github.com/huangjunsen0406/py-xiaozhi` |
| 声纹服务（Fork 源） | `https://github.com/xinnan-tech/voiceprint-api` |
| Java 版服务端参考 | `https://github.com/joey-zhou/xiaozhi-esp32-server-java` |
| Go 版服务端参考 | `https://github.com/hackers365/xiaozhi-esp32-server-golang` |
| 嵌入式 Linux 版 | `https://github.com/100askTeam/xiaozhi-linux` |
| 语音助手架构参考 | `https://github.com/OpenVoiceOS/OpenVoiceOS` |

---

## 十七、变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v2.2 | 2026-11-03 | 首版成文。纳入：五项差异化护城河、录音转日志新模式（服务端旁路 / 新增设备状态 / MCP 触发三层）、噪音环境唤醒四层防线与「声纹 ≠ 降噪唤醒」认知纠正、voiceprint-api Fork 改造清单（PG + 多租户 + 保留期 + 量化）、8 张硬表与 9 张动态表、完整 API 契约、17 阶段路线、13 条风险、14 章验收标准、固件改动清单 |

---

> **下一步**：从 **P0 协议固化** 开始。
> 1. 拉取四个仓库（`78/xiaozhi-esp32` / `xinnan-tech/xiaozhi-esp32-server` / `huangjunsen0406/py-xiaozhi` / `xinnan-tech/voiceprint-api`）
> 2. 产出 `docs/XIAOZHI_PROTOCOL_SPEC.md`
> 3. 落地 `com.nocobase.iot` 包骨架 + V77/V78 迁移
> 4. 编写 `scripts/xiaozhi-sim/sim.py` 并跑通
>
> **最高杠杆的单个动作**：先花 2 天用模拟器把协议跑通 —— 不依赖硬件、不产生返工，且能立即证伪或证实整条技术路径。
