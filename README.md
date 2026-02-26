# ai-voice-service 语音服务

集成百炼（DashScope）全系列 ASR / TTS 模型，通过引擎抽象 + 模型路由实现「改配置即切换模型」。支持**云 API**与**本地自部署**（Qwen3-ASR/TTS）双模式，通过 `ai.voice.local.enabled` 切换。

## 架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        前端 / 客户端                                     │
│         WS /api/voice/asr/realtime          REST /api/voice/*            │
└─────┬────────────────────────────────────────────┬──────────────────────┘
      │                                              │
┌─────▼──────────┐                    ┌─────────────▼──────────┐
│AsrRealtimeHdlr │                    │   ApiVoiceController   │
└─────┬──────────┘                    └───┬─────────────┬──────┘
      │                                    │             │
      │         ┌──────────────────────────┼─────────────┼──────────────────────────┐
      │         │ 配置: local.enabled       │             │                          │
      ▼         ▼                          ▼             ▼                          ▼
┌─────────────────────────┐    ┌─────────────────────────────────────────────────────────┐
│  IAsrEngine (实时)      │    │  IAsrFileEngine (文件)        │  ITtsEngine              │
├─────────────────────────┤    ├───────────────────────────────┼─────────────────────────┤
│ false → DashScopeAsr    │    │ false → DashScopeAsrFile      │ false → DashScopeTts     │
│   OmniRealtime          │    │   Recognition / Transcription │   cosyvoice 等           │
│   Recognition           │    │   QwenTranscr / MultiModal    │                         │
│   GummyRealtime/Chat    │    ├───────────────────────────────┼─────────────────────────┤
│ true  → LocalAsrEngine  │    │ true  → LocalAsrFileEngine     │ true  → LocalTtsEngine  │
│   → ws://...:8005       │    │   → POST ...:8003/transcriptions│   → POST ...:8004/speech│
└──────────┬──────────────┘    └───────────────┬───────────────┴──────────┬─────────────┘
           │                                    │                           │
           │         local.enabled=true 时请求转发至 ai-voice-model          │
           ▼                                    ▼                           ▼
┌──────────────────────────────────────────────────────────────────────────────────────┐
│  ai-voice-model（GPU 服务器）                                                           │
│  asr_realtime_server :8005 (WS)     qwen-asr-serve :8003 (HTTP)     tts_server :8004    │
│  VAD + 句尾检测 → 转写                 文件/整段识别                      文本→语音       │
└──────────────────────────────────────────────────────────────────────────────────────┘

  ITextCorrectionService（仅云模式可配）：qwen-turbo 纠错
```

- **云 API 模式**（`local.enabled=false`）：实时/文件 ASR 与 TTS 均由 DashScope 各引擎实现，可选纠错。
- **本地模式**（`local.enabled=true`）：由 Local* 引擎接管，请求转发至自部署的 [ai-voice-model](#ai-voice-model本地模型部署)（qwen-asr-serve、asr_realtime_server、tts_server）。

## 接口一览

| 类型 | 方法 | 路径 | 说明 |
|------|------|------|------|
| TTS | POST | `/api/voice/tts` | 文本转语音，返回 MP3 流 |
| 文件 ASR | POST | `/api/voice/asr/file` | 上传音频文件，返回识别+纠错文本 |
| 实时 ASR | WebSocket | `/api/voice/asr/realtime` | 流式识别，协议见下 |

服务默认端口：`28085`（可通过 `SERVER_PORT` 覆盖）。经网关转发时以网关域名/路径为准。

## 模型适配一览（改配置即生效）

### 实时识别（WebSocket）- `asr.model`

| model                       | 路由到的 SDK API                  | 说明        |
|-----------------------------|-------------------------------|-----------|
| `qwen3-asr-flash-realtime`  | OmniRealtimeConversation      | 千问 ASR 实时 |
| `qwen3-asr-turbo-realtime`  | OmniRealtimeConversation      | 千问 ASR 实时 |
| `fun-asr-realtime`          | Recognition                   | 多语种/方言，推荐 |
| `paraformer-realtime-v2`    | Recognition                   | 会议/直播     |
| `paraformer-realtime-8k-v2` | Recognition                   | 电话/8kHz   |
| `gummy-realtime-v1`         | TranslationRecognizerRealtime | 多语种实时     |
| `gummy-chat-v1`             | TranslationRecognizerChat     | 一句话识别     |

### 文件识别（REST 上传）- `asr-file.model`

| model                       | 路由到的 SDK API                  | 备注         |
|-----------------------------|-------------------------------|------------|
| `fun-asr-realtime`          | Recognition.call(param, File) | 同步，推荐      |
| `paraformer-realtime-v2`    | Recognition.call(param, File) | 同步         |
| `fun-asr`                   | Transcription API             | 异步，需文件 URL |
| `paraformer-v2`             | Transcription API             | 异步，需文件 URL |
| `sensevoice-v1`             | Transcription API             | 异步，需文件 URL |
| `qwen3-asr-flash-filetrans` | QwenTranscription API         | 异步，需文件 URL |
| `qwen3-asr-flash`           | MultiModalConversation        | 同步，需文件 URL |

> 标注"需文件 URL"的离线模型，需要音频文件可通过公网 URL 访问（如 OSS）。
> 建议文件识别优先使用 `fun-asr-realtime` 或 `paraformer-realtime-v2`（支持本地文件）。

## WebSocket 协议

### 客户端 → 服务端

```json
{
  "audio": "base64 PCM"
}    // 或直接发送二进制 PCM
{
  "action": "end"
}           // 结束识别
```

### 服务端 → 客户端（固定格式，换模型不影响）

```json
{
  "text": "你好",
  "sentenceEnd": false,
  "final": false
}
{
  "text": "你好世界",
  "sentenceEnd": true,
  "final": false
}
{
  "text": "你好，世界",
  "sentenceEnd": true,
  "final": true
}
{
  "error": "连接失败"
}
```

## 配置

### 云 API 模式（`local.enabled: false`）

```yaml
ai:
  voice:
    local:
      enabled: false
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:}
      region: cn-beijing
      asr:
        model: fun-asr-realtime        # 实时识别模型
        sample-rate: 16000
        language: zh
      asr-file:
        model: fun-asr-realtime        # 文件识别模型（可不同于实时）
      tts:
        model: cosyvoice-v3-flash
        voice: longanyang
      llm:
        model: qwen-turbo              # ASR 纠错用
```

### 本地自部署模式（`local.enabled: true`）

```yaml
ai:
  voice:
    local:
      enabled: true
      asr-url: http://<GPU服务器>:8003
      asr-file-model: qwen3-asr         # 与 qwen-asr-serve 的 model 参数一致，按实际服务调整
      asr-realtime-url: ws://<GPU服务器>:8005
      tts-url: http://<GPU服务器>:8004
      tts-voice: vivian                 # 本地 TTS 仅支持: aiden, dylan, eric, ono_anna, ryan, serena, sohee, uncle_fu, vivian
      timeout-ms: 30000
```

本地需先部署 **ai-voice-model** 下的模型服务（见下一节）。

### 文本纠错（LLM 系统提示词）

纠错用的系统提示词可在 `application.yml` 中配置，便于调整语气与规则；若启用 Nacos，也可在 Nacos 中配置并动态刷新：

```yaml
ai:
  voice:
    text-correction:
      system-prompt: |
        你是一个语音识别文本纠错助手。用户发来的文本是...
        1. 修正明显的识别错误（错别字、同音字、漏字、多字）
        2. 保持用户的原始意图...
        3. 只输出纠错后的文本，不要输出任何解释
```

### 可选 Nacos 配置中心

未配置 Nacos 时应用**不会连接、不注册**，仅使用本地 `application.yml`。需要从 Nacos 拉取/刷新配置时再启用：

- 设置环境变量：`NACOS_ENABLED=true`、`NACOS_SERVER_ADDR=192.168.1.1:8848`（可选：`NACOS_NAMESPACE`、`NACOS_GROUP`）
- 或在 `bootstrap.yml` 中配置 `spring.cloud.nacos.config.enabled=true` 与 `server-addr`
- 启用后，Nacos 中的 `ai-voice-service.yml`（或对应 dataId）可覆盖/刷新本地配置，例如纠错提示词、`dashscope.api-key` 等

---

## ai-voice-model：本地模型部署

本目录提供 **Qwen3-ASR / Qwen3-TTS** 在 GPU 服务器上的部署脚本，供 **ai-voice-server** 在 `local.enabled=true` 时将 ASR/TTS 请求转发到此端。

### 目录结构

| 文件 | 说明 |
|------|------|
| `voice_manager.sh` | 统一管理脚本：环境搭建、模型下载、服务启停、状态与日志、API 测试 |
| `asr_realtime_server.py` | 实时 ASR WebSocket 服务（VAD 句尾检测 + 调用 qwen-asr-serve 转写） |
| `tts_server.py` | TTS HTTP 服务（FastAPI，OpenAI 兼容 `/v1/audio/speech`） |

ASR 文件/整段识别由 **qwen-asr-serve**（`qwen-asr[vllm]` 包）提供，通过 `voice_manager.sh` 启动，无需单独脚本。

### 环境要求

- **系统**：Linux（推荐 Ubuntu/CentOS）
- **运行环境**：Conda，Python 3.12
- **GPU**：ASR、TTS 各需 GPU（脚本内可配置 GPU 编号）；实时 ASR WebSocket 为 CPU（VAD + 转发）
- **依赖**：sox、ffmpeg（TTS 出 MP3 时）、CUDA、FlashAttention（可选，见脚本说明）

### 部署步骤

在 GPU 服务器上进入 `ai-voice-model` 目录，按顺序执行：

```bash
./voice_manager.sh setup          # 创建 Conda 环境、安装 qwen-asr / qwen-tts / FastAPI 等
./voice_manager.sh download all    # 预下载模型（可选，首次启动也会自动下载）
./voice_manager.sh start all       # 启动 asr → asr-rt → tts
./voice_manager.sh test all        # 验证各服务 API 可用
./voice_manager.sh status          # 查看运行状态与端口
```

### 服务与端口（默认）

| 服务 | 端口 | 说明 |
|------|------|------|
| **asr** | 8003 | qwen-asr-serve，HTTP。文件/整段识别：`POST /v1/audio/transcriptions`、`POST /v1/chat/completions` |
| **asr-rt** | 8005 | 实时流式 WebSocket，VAD 句尾检测。`ws://host:8005/ws/asr`，协议与 ai-voice-server 一致 |
| **tts** | 8004 | TTS HTTP。`POST /v1/audio/speech`，`GET /v1/audio/voices` |

端口、GPU、模型路径、VAD 灵敏度等均在 **voice_manager.sh 顶部配置区** 修改；修改后需 `restart` 对应服务。

### 与 ai-voice-server 对接

在 ai-voice-server 的 `application.yml` 中配置本地模式时，将上述端口指向 GPU 服务器地址即可：

```yaml
evangelion:
  voice:
    local:
      enabled: true
      asr-url: http://<GPU服务器IP>:8003
      asr-file-model: qwen3-asr
      asr-realtime-url: ws://<GPU服务器IP>:8005
      tts-url: http://<GPU服务器IP>:8004
```

### 常用命令

| 命令 | 说明 |
|------|------|
| `./voice_manager.sh start all` | 启动全部服务 |
| `./voice_manager.sh stop all` | 停止全部服务 |
| `./voice_manager.sh restart asr-rt` | 仅重启实时 ASR |
| `./voice_manager.sh logs asr 100` | 查看 asr 最近 100 行日志 |
| `./voice_manager.sh tail tts` | 实时跟踪 TTS 日志 |
| `./voice_manager.sh help` | 查看完整帮助与 API 示例 |

---

## 前端对接说明

以下示例假设语音服务基础地址为 `BASE = 'http(s)://your-host:28085'`（或网关转发后的前缀），且请求已携带鉴权（如 Token）。

### 1. 文本转语音（TTS）

- **请求**：`POST BASE/api/voice/tts`，`Content-Type: application/json`
- **请求体**：`{ "text": "要合成的文本", "voice": "可选音色，不传用服务默认" }`
- **成功**：`Content-Type: application/octet-stream`，body 为 MP3 二进制，可直接播放或下载。
- **失败**：HTTP 200 + body 为统一封装 `R`（如 `{ "code": -1, "msg": "语音合成失败..." }`）。

```javascript
// 示例：fetch 请求 TTS，并播放
const res = await fetch(`${BASE}/api/voice/tts`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ text: '你好，世界', voice: 'vivian' }),
});
if (!res.ok) throw new Error(res.statusText);
const contentType = res.headers.get('Content-Type');
if (contentType && contentType.includes('application/json')) {
  const json = await res.json();
  throw new Error(json.msg || 'TTS 失败');
}
const blob = await res.blob();
const url = URL.createObjectURL(blob);
const audio = new Audio(url);
audio.play();
```

### 2. 文件转写（ASR 文件）

- **请求**：`POST BASE/api/voice/asr/file`，`Content-Type: multipart/form-data`
- **表单字段**：`file` — 音频文件（支持 WAV、MP3 等）
- **成功**：JSON 统一封装，如 `{ "code": 0, "data": { "text": "识别并纠错后的完整文本" } }`
- **失败**：`{ "code": -1, "msg": "错误原因" }`

```javascript
// 示例：上传文件并取识别结果
const form = new FormData();
form.append('file', audioFile); // File 对象，如 input[type=file].files[0]
const res = await fetch(`${BASE}/api/voice/asr/file`, {
  method: 'POST',
  body: form,
});
const json = await res.json();
if (json.code !== 0) throw new Error(json.msg);
console.log('识别结果:', json.data.text);
```

### 3. 实时语音识别（WebSocket）

- **地址**：`WS_BASE/api/voice/asr/realtime`（将 `http(s):` 换为 `ws(s):` 即可，若经网关需确认网关支持 WebSocket）
- **发送**：
  - 发送 PCM：二进制帧，或 JSON `{ "audio": "base64 编码的 PCM" }`
  - 结束识别：JSON `{ "action": "end" }`
- **接收**：服务端推送 JSON，格式固定：
  - 中间/句末结果：`{ "text": "当前识别文本", "sentenceEnd": true/false, "final": false }`
  - 最终结果（含纠错）：`{ "text": "全文", "sentenceEnd": true, "final": true }`
  - 错误：`{ "error": "错误信息" }`

**音频格式**：16kHz、16bit、单声道 PCM（与 DashScope 实时识别一致）。前端采集时需按此格式重采样后再发送。

```javascript
// 示例：建立 WebSocket，发送 base64 PCM，并打印结果
const wsUrl = BASE.replace(/^http/, 'ws') + '/api/voice/asr/realtime';
const ws = new WebSocket(wsUrl);

ws.onmessage = (e) => {
  const msg = JSON.parse(e.data);
  if (msg.error) {
    console.error('ASR 错误:', msg.error);
    return;
  }
  console.log('识别:', msg.text, 'sentenceEnd:', msg.sentenceEnd, 'final:', msg.final);
  if (msg.final) {
    console.log('最终全文:', msg.text);
  }
};

ws.onopen = () => {
  // 示例：发送一帧 base64 PCM（实际应从麦克风持续采集并重采样为 16k 16bit 单声道）
  // ws.send(JSON.stringify({ audio: base64PcmChunk }));
  // 结束识别时发送：
  // ws.send(JSON.stringify({ action: 'end' }));
};
```

### 统一响应格式（R）

REST 接口除 TTS 成功返回二进制流外，均使用统一结构：

```json
{
  "code": 0,
  "msg": "success",
  "data": { ... }
}
```

成功时 `code` 为 0，失败时 `code` 为 -1（或其它非 0），`msg` 为错误说明，`data` 可为 null。

## 扩展引擎

实现 `IAsrEngine` / `IAsrFileEngine` / `ITtsEngine` + `@ConditionalOnProperty` 即可切换，前端协议不变、零改动。
