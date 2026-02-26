#!/bin/bash

# Qwen3 ASR/TTS 语音服务管理脚本
# 用法: ./voice_manager.sh {setup|download|start|stop|restart|status|logs|tail|test} [asr|asr-rt|tts|all]
#
# asr    : qwen-asr-serve（vLLM 后端，HTTP API，文件/整段识别）
# asr-rt : 实时流式 ASR WebSocket（VAD 端点检测 + 逐句转写）
# tts    : qwen-tts + FastAPI（HTTP API，语音合成）

# ==================== 配置区 ====================

BASE_DIR="/home/model/voice-server"
LOG_DIR="${BASE_DIR}/logs"
TTS_SERVER_SCRIPT="${BASE_DIR}/tts_server.py"
ASR_RT_SERVER_SCRIPT="${BASE_DIR}/asr_realtime_server.py"

# 模型目录 (HuggingFace 缓存统一指向此处)
MODEL_DIR="/home/model"

# Conda 配置
CONDA_PATH="/root/miniconda3"
CONDA_ENV="voice"
PYTHON_VERSION="3.12"

# ASR 配置
ASR_MODEL="Qwen/Qwen3-ASR-1.7B"       # 可改为 Qwen/Qwen3-ASR-0.6B (更快、更省显存)
ASR_PORT="8003"
ASR_GPU="2"
ASR_GPU_UTIL="0.15"                     # vLLM GPU 显存占比 (0.15 ≈ 7.3GB, 1.7B 模型足够)

# ASR 实时配置（WebSocket + VAD，不占 GPU，纯 CPU 代理）
ASR_RT_PORT="8005"
ASR_RT_VAD_LEVEL="2"                    # VAD 灵敏度 0(宽松)~3(激进)
ASR_RT_SILENCE_MS="600"                 # 静音多久判定句尾 (毫秒)
ASR_RT_PARTIAL_INTERVAL="1.5"           # 中间结果间隔 (秒)

# TTS 配置
TTS_MODEL="Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice"  # CustomVoice 版本（预设说话人）
TTS_PORT="8004"
TTS_GPU="2"

ALL_SERVICES=("asr" "asr-rt" "tts")

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# ==================== 工具函数 ====================

log_info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_debug() { echo -e "${BLUE}[DEBUG]${NC} $1"; }

ensure_dirs() {
    mkdir -p "${LOG_DIR}"
}

# 初始化 Conda
init_conda() {
    if [ -f "${CONDA_PATH}/etc/profile.d/conda.sh" ]; then
        source "${CONDA_PATH}/etc/profile.d/conda.sh"
    else
        log_error "找不到 conda: ${CONDA_PATH}/etc/profile.d/conda.sh"
        return 1
    fi
}

# 获取 conda 环境下的可执行路径
get_env_bin() {
    echo "${CONDA_PATH}/envs/${CONDA_ENV}/bin"
}

# 获取日志文件路径（带日期）
get_log_file() {
    local service=$1
    local today=$(date +%Y%m%d)
    echo "${LOG_DIR}/${service}_${today}.log"
}

# 获取服务 PID
get_pid() {
    local service=$1
    case "$service" in
        asr)    pgrep -f "qwen.asr.serve.*Qwen3-ASR" | head -1 ;;
        asr-rt) pgrep -f "asr_realtime_server.py.*--port ${ASR_RT_PORT}" | head -1 ;;
        tts)    pgrep -f "tts_server.py.*--port ${TTS_PORT}" | head -1 ;;
    esac
}

# 检查服务是否运行
is_running() {
    local pid=$(get_pid $1)
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

# 获取运行时长
get_uptime() {
    local pid=$1
    if [ -n "$pid" ] && [ -d "/proc/$pid" ]; then
        local start_time=$(stat -c %Y /proc/$pid 2>/dev/null)
        if [ -n "$start_time" ]; then
            local now=$(date +%s)
            local uptime=$((now - start_time))
            local days=$((uptime / 86400))
            local hours=$(((uptime % 86400) / 3600))
            local mins=$(((uptime % 3600) / 60))
            if [ $days -gt 0 ]; then echo "${days}d ${hours}h ${mins}m"
            elif [ $hours -gt 0 ]; then echo "${hours}h ${mins}m"
            else echo "${mins}m"; fi
        fi
    fi
}

# ==================== 环境搭建 ====================

setup_env() {
    log_info "=========================================="
    log_info "搭建 Qwen3 ASR/TTS 运行环境"
    log_info "=========================================="

    init_conda || return 1

    if conda env list | grep -q "^${CONDA_ENV} "; then
        log_warn "Conda 环境 '${CONDA_ENV}' 已存在，跳过创建"
    else
        log_info "创建 Conda 环境: ${CONDA_ENV} (Python ${PYTHON_VERSION})"
        conda create -n "${CONDA_ENV}" python="${PYTHON_VERSION}" -y
    fi

    conda activate "${CONDA_ENV}"

    # --- 系统依赖 ---
    log_info "安装系统依赖 (sox)..."
    if command -v apt &>/dev/null; then
        NEEDRESTART_MODE=l apt install -y sox libsox-fmt-all
    elif command -v yum &>/dev/null; then
        yum install -y sox
    else
        log_warn "请手动安装 sox: http://sox.sourceforge.net/"
    fi

    # --- Python 依赖 ---
    log_info "安装 qwen-asr (含 vLLM 后端)..."
    pip install -U "qwen-asr[vllm]"

    log_info "安装 modelscope (vLLM 依赖)..."
    pip install -U "modelscope>=1.18.1"

    log_info "安装 qwen-tts..."
    pip install -U qwen-tts

    log_info "安装 FastAPI + Uvicorn + 音频处理 + VAD 依赖..."
    pip install -U fastapi uvicorn soundfile httpx webrtcvad

    log_info "固定 numpy 版本 (numba 兼容性)..."
    pip install "numpy>=2.0,<2.3"

    # --- FlashAttention 2 (预编译 wheel，不用源码编译) ---
    log_info "安装 FlashAttention 2..."
    local TORCH_VER=$(python -c "import torch; v=torch.__version__.split('+')[0].rsplit('.',1)[0]; print(v)" 2>/dev/null)
    local CUDA_VER=$(python -c "import torch; print(torch.version.cuda.replace('.',''))" 2>/dev/null)
    local PY_VER=$(python -c "import sys; print(f'cp{sys.version_info.major}{sys.version_info.minor}')" 2>/dev/null)
    local WHEEL_URL="https://github.com/mjun0812/flash-attention-prebuild-wheels/releases/download/v0.7.16/flash_attn-2.8.3+cu${CUDA_VER}torch${TORCH_VER}-${PY_VER}-${PY_VER}-linux_x86_64.whl"

    log_debug "尝试预编译 wheel: ${WHEEL_URL}"
    if ! pip install "${WHEEL_URL}" 2>/dev/null; then
        log_warn "预编译 wheel 不可用，尝试 GitHub 代理..."
        if ! pip install "https://ghfast.top/${WHEEL_URL}" 2>/dev/null; then
            log_warn "FlashAttention 安装跳过，不影响功能，仅推理稍慢。可后续手动安装。"
        fi
    fi

    log_info "✓ 环境搭建完成"
    log_info "  Conda 环境: ${CONDA_ENV}"
    log_info "  Python: $(python --version 2>&1)"
    python -c "import torch; print(f'  PyTorch: {torch.__version__}  CUDA: {torch.version.cuda}')" 2>/dev/null
    python -c "import flash_attn; print(f'  FlashAttention: {flash_attn.__version__}')" 2>/dev/null || log_warn "  FlashAttention: 未安装"

    log_info ""
    log_info "下一步: ./voice_manager.sh download all"
}

# ==================== 模型下载 ====================

download_models() {
    local service=$1

    init_conda || return 1
    conda activate "${CONDA_ENV}"

    case "$service" in
        asr)
            log_info "下载 ASR 模型: ${ASR_MODEL} -> ${MODEL_DIR}"
            log_info "（首次启动 qwen-asr-serve 时也会自动下载，此步骤可选）"
            huggingface-cli download "${ASR_MODEL}" --local-dir "${MODEL_DIR}/${ASR_MODEL}"
            log_info "✓ ASR 模型下载完成: ${MODEL_DIR}/${ASR_MODEL}"
            ;;
        tts)
            log_info "下载 TTS 模型: ${TTS_MODEL} -> ${MODEL_DIR}"
            huggingface-cli download "${TTS_MODEL}" --local-dir "${MODEL_DIR}/${TTS_MODEL}"
            log_info "✓ TTS 模型下载完成: ${MODEL_DIR}/${TTS_MODEL}"
            ;;
        all)
            download_models asr
            echo ""
            download_models tts
            ;;
    esac
}

# ==================== 启动服务 ====================

start_asr() {
    if is_running asr; then
        local pid=$(get_pid asr)
        log_warn "ASR 服务已在运行 (PID: ${pid})"
        return 0
    fi

    init_conda || return 1
    local bin=$(get_env_bin)
    local log_file=$(get_log_file asr)

    log_info "启动 ASR 服务..."
    log_debug "模型: ${ASR_MODEL}"
    log_debug "端口: ${ASR_PORT}  GPU: ${ASR_GPU}  显存占比: ${ASR_GPU_UTIL}"

    CUDA_VISIBLE_DEVICES=${ASR_GPU} nohup "${bin}/qwen-asr-serve" \
        "${MODEL_DIR}/${ASR_MODEL}" \
        --gpu-memory-utilization "${ASR_GPU_UTIL}" \
        --max-model-len 4096 \
        --host 0.0.0.0 \
        --port "${ASR_PORT}" \
        >> "${log_file}" 2>&1 &

    sleep 5

    if is_running asr; then
        local pid=$(get_pid asr)
        log_info "✓ ASR 服务启动成功 (PID: ${pid})"
        log_info "  日志: ${log_file}"
        log_info "  API:  POST http://localhost:${ASR_PORT}/v1/audio/transcriptions"
        log_info "  API:  POST http://localhost:${ASR_PORT}/v1/chat/completions"
    else
        log_error "✗ ASR 启动失败，查看日志: tail -50 ${log_file}"
        return 1
    fi
}

start_asr_rt() {
    if is_running asr-rt; then
        local pid=$(get_pid asr-rt)
        log_warn "ASR 实时服务已在运行 (PID: ${pid})"
        return 0
    fi

    if [ ! -f "${ASR_RT_SERVER_SCRIPT}" ]; then
        log_error "ASR 实时服务脚本不存在: ${ASR_RT_SERVER_SCRIPT}"
        return 1
    fi

    if ! is_running asr; then
        log_warn "ASR 核心服务 (qwen-asr-serve) 未运行，asr-rt 依赖它。建议先: $0 start asr"
    fi

    init_conda || return 1
    local bin=$(get_env_bin)
    local log_file=$(get_log_file asr-rt)

    log_info "启动 ASR 实时 WebSocket 服务..."
    log_debug "端口: ${ASR_RT_PORT}  VAD: level=${ASR_RT_VAD_LEVEL} silence=${ASR_RT_SILENCE_MS}ms"
    log_debug "后端: http://localhost:${ASR_PORT} (qwen-asr-serve)"

    nohup "${bin}/python" "${ASR_RT_SERVER_SCRIPT}" \
        --asr-url "http://localhost:${ASR_PORT}" \
        --host 0.0.0.0 \
        --port "${ASR_RT_PORT}" \
        --vad-aggressiveness "${ASR_RT_VAD_LEVEL}" \
        --silence-ms "${ASR_RT_SILENCE_MS}" \
        --partial-interval "${ASR_RT_PARTIAL_INTERVAL}" \
        >> "${log_file}" 2>&1 &

    sleep 3

    if is_running asr-rt; then
        local pid=$(get_pid asr-rt)
        log_info "✓ ASR 实时服务启动成功 (PID: ${pid})"
        log_info "  日志: ${log_file}"
        log_info "  WebSocket: ws://localhost:${ASR_RT_PORT}/ws/asr"
        log_info "  协议: 二进制 PCM (16kHz/16bit/mono) | {\"audio\":\"base64\"} | {\"action\":\"end\"}"
    else
        log_error "✗ ASR 实时服务启动失败，查看日志: tail -50 ${log_file}"
        return 1
    fi
}

start_tts() {
    if is_running tts; then
        local pid=$(get_pid tts)
        log_warn "TTS 服务已在运行 (PID: ${pid})"
        return 0
    fi

    if [ ! -f "${TTS_SERVER_SCRIPT}" ]; then
        log_error "TTS 服务脚本不存在: ${TTS_SERVER_SCRIPT}"
        return 1
    fi

    init_conda || return 1
    local bin=$(get_env_bin)
    local log_file=$(get_log_file tts)

    log_info "启动 TTS 服务..."
    log_debug "模型: ${TTS_MODEL}"
    log_debug "端口: ${TTS_PORT}  GPU: ${TTS_GPU}"

    CUDA_VISIBLE_DEVICES=${TTS_GPU} nohup "${bin}/python" "${TTS_SERVER_SCRIPT}" \
        --model "${MODEL_DIR}/${TTS_MODEL}" \
        --host 0.0.0.0 \
        --port "${TTS_PORT}" \
        >> "${log_file}" 2>&1 &

    sleep 8

    if is_running tts; then
        local pid=$(get_pid tts)
        log_info "✓ TTS 服务启动成功 (PID: ${pid})"
        log_info "  日志: ${log_file}"
        log_info "  API:  POST http://localhost:${TTS_PORT}/v1/audio/speech"
        log_info "  说话人列表: GET  http://localhost:${TTS_PORT}/v1/audio/voices"
    else
        log_error "✗ TTS 启动失败，查看日志: tail -50 ${log_file}"
        return 1
    fi
}

start_service() {
    case "$1" in
        asr)    start_asr ;;
        asr-rt) start_asr_rt ;;
        tts)    start_tts ;;
        all)    start_asr; echo ""; start_asr_rt; echo ""; start_tts ;;
    esac
}

# ==================== 停止服务 ====================

stop_service() {
    local service=$1

    if [ "$service" = "all" ]; then
        for s in "${ALL_SERVICES[@]}"; do stop_service "$s"; echo ""; done
        return
    fi

    if ! is_running "$service"; then
        log_warn "${service} 服务未运行"
        return 0
    fi

    local pid=$(get_pid "$service")
    log_info "停止 ${service} 服务 (PID: ${pid})..."

    kill "$pid" 2>/dev/null
    local wait=0
    while is_running "$service" && [ $wait -lt 15 ]; do
        sleep 1; wait=$((wait + 1)); echo -n "."
    done
    echo ""

    if is_running "$service"; then
        log_warn "SIGTERM 未响应，强制终止..."
        kill -9 "$pid" 2>/dev/null
        sleep 2
    fi

    if ! is_running "$service"; then
        log_info "✓ ${service} 服务已停止"
    else
        log_error "✗ ${service} 停止失败"
        return 1
    fi
}

# ==================== 重启 ====================

restart_service() {
    local service=$1
    if [ "$service" = "all" ]; then
        for s in "${ALL_SERVICES[@]}"; do restart_service "$s"; echo ""; done
        return
    fi
    log_info "重启 ${service} 服务..."
    stop_service "$service"
    sleep 3
    start_service "$service"
}

# ==================== 状态 ====================

show_status() {
    log_info "=========================================="
    log_info "Qwen3 ASR/TTS 语音服务状态"
    log_info "=========================================="

    printf "%-8s %-12s %-8s %-8s %-6s %-12s %s\n" \
        "服务" "状态" "PID" "端口" "GPU" "运行时长" "模型"
    printf "%s\n" "--------------------------------------------------------------------------------------------"

    for service in "${ALL_SERVICES[@]}"; do
        local port gpu model_name
        case "$service" in
            asr)    port=$ASR_PORT;    gpu=$ASR_GPU; model_name="$ASR_MODEL" ;;
            asr-rt) port=$ASR_RT_PORT; gpu="CPU";    model_name="VAD+WebSocket" ;;
            tts)    port=$TTS_PORT;    gpu=$TTS_GPU; model_name="$TTS_MODEL" ;;
        esac

        if is_running "$service"; then
            local pid=$(get_pid "$service")
            local uptime=$(get_uptime "$pid")
            printf "%-8s ${GREEN}%-12s${NC} %-8s %-8s %-6s %-12s %s\n" \
                "$service" "运行中" "$pid" "$port" "$gpu" "$uptime" "$model_name"
        else
            printf "%-8s ${RED}%-12s${NC} %-8s %-8s %-6s %-12s %s\n" \
                "$service" "已停止" "-" "$port" "$gpu" "-" "$model_name"
        fi
    done

    echo ""

    if command -v nvidia-smi &>/dev/null; then
        log_info "GPU 使用情况:"
        nvidia-smi --query-gpu=index,name,memory.used,memory.total,utilization.gpu \
            --format=csv,noheader | while read line; do echo "  $line"; done
    fi

    echo ""

    log_info "端口监听状态:"
    for service in "${ALL_SERVICES[@]}"; do
        local port
        case "$service" in asr) port=$ASR_PORT ;; asr-rt) port=$ASR_RT_PORT ;; tts) port=$TTS_PORT ;; esac
        if ss -tlnp 2>/dev/null | grep -q ":${port} "; then
            echo -e "  端口 ${port} (${service}): ${GREEN}监听中${NC}"
        else
            echo -e "  端口 ${port} (${service}): ${RED}未监听${NC}"
        fi
    done

    log_info "=========================================="
}

# ==================== 日志 ====================

show_logs() {
    local service=$1
    local lines=${2:-50}
    local log_file=$(get_log_file "$service")

    if [ -f "$log_file" ]; then
        log_info "显示 ${service} 最后 ${lines} 行日志: ${log_file}"
        echo "----------------------------------------"
        tail -n "$lines" "$log_file"
    else
        log_warn "日志文件不存在: ${log_file}"
    fi
}

tail_logs() {
    local service=$1
    local log_file=$(get_log_file "$service")

    if [ -f "$log_file" ]; then
        log_info "实时跟踪 ${service} 日志 (Ctrl+C 退出): ${log_file}"
        echo "----------------------------------------"
        tail -f "$log_file"
    else
        log_warn "日志文件不存在: ${log_file}"
    fi
}

# ==================== API 测试 ====================

test_service() {
    local service=$1

    if [ "$service" = "all" ]; then
        for s in "${ALL_SERVICES[@]}"; do test_service "$s"; echo ""; done
        return
    fi

    if ! is_running "$service"; then
        log_error "${service} 服务未运行"
        return 1
    fi

    case "$service" in
        asr)
            log_info "测试 ASR 服务 (端口 ${ASR_PORT})..."

            # OpenAI Transcription API
            log_info "  > POST /v1/audio/transcriptions (测试音频)"
            local resp
            resp=$(curl -s -w "\n%{http_code}" -X POST \
                "http://localhost:${ASR_PORT}/v1/chat/completions" \
                -H "Content-Type: application/json" \
                -d '{
                    "messages": [{
                        "role": "user",
                        "content": [{
                            "type": "audio_url",
                            "audio_url": {"url": "https://qianwen-res.oss-cn-beijing.aliyuncs.com/Qwen3-ASR-Repo/asr_zh.wav"}
                        }]
                    }]
                }' 2>/dev/null)

            local http_code=$(echo "$resp" | tail -1)
            local body=$(echo "$resp" | head -n -1)

            if [ "$http_code" = "200" ]; then
                log_info "  ✓ ASR 返回 HTTP 200"
                echo "    结果: $(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin)['choices'][0]['message']['content'])" 2>/dev/null || echo "$body")"
            else
                log_warn "  ASR 返回 HTTP ${http_code}，可能仍在初始化中"
            fi
            ;;

        asr-rt)
            log_info "测试 ASR 实时服务 (端口 ${ASR_RT_PORT})..."

            local health
            health=$(curl -s "http://localhost:${ASR_RT_PORT}/health" 2>/dev/null)
            if echo "$health" | grep -q '"ok"'; then
                log_info "  ✓ ASR 实时服务健康: ${health}"
                log_info "  WebSocket 端点: ws://localhost:${ASR_RT_PORT}/ws/asr"
                log_info "  协议: 发送二进制 PCM 或 {\"audio\":\"base64\"}, 收到 {\"text\":\"...\",\"sentenceEnd\":bool}"
            else
                log_warn "  ASR 实时服务未就绪: ${health}"
            fi
            ;;

        tts)
            log_info "测试 TTS 服务 (端口 ${TTS_PORT})..."

            # 健康检查
            local health
            health=$(curl -s "http://localhost:${TTS_PORT}/health" 2>/dev/null)
            log_info "  > 健康检查: ${health}"

            # 说话人列表
            local voices
            voices=$(curl -s "http://localhost:${TTS_PORT}/v1/audio/voices" 2>/dev/null)
            log_info "  > 说话人列表: ${voices}"

            # 合成测试
            log_info "  > POST /v1/audio/speech (合成 '你好，世界')"
            local http_code
            http_code=$(curl -s -o /tmp/tts_test.wav -w "%{http_code}" \
                -X POST "http://localhost:${TTS_PORT}/v1/audio/speech" \
                -H "Content-Type: application/json" \
                -d '{"input":"你好，世界","voice":"","language":"Chinese","response_format":"wav"}' \
                2>/dev/null)

            if [ "$http_code" = "200" ]; then
                local fsize=$(stat -c%s /tmp/tts_test.wav 2>/dev/null || echo "0")
                log_info "  ✓ TTS 合成成功 (HTTP 200, 文件大小: ${fsize} bytes)"
                log_info "    测试文件: /tmp/tts_test.wav"
            else
                log_warn "  TTS 返回 HTTP ${http_code}"
            fi
            ;;
    esac
}

# ==================== 帮助 ====================

show_help() {
    cat << EOF
${CYAN}Qwen3 ASR/TTS 语音服务管理脚本${NC}

${YELLOW}用法:${NC}
    $0 <命令> [服务名] [参数]

${YELLOW}命令:${NC}
    setup               搭建 Conda 环境并安装依赖（首次使用）
    download [service]   预下载模型权重（可选，首次启动也会自动下载）
    start [service]      启动服务
    stop [service]       停止服务
    restart [service]    重启服务
    status               显示服务状态
    logs <service> [n]   查看最后 n 行日志（默认 50）
    tail <service>       实时跟踪日志
    test [service]       测试 API 可用性

${YELLOW}服务名:${NC}
    asr     Qwen3-ASR 语音识别 HTTP    (GPU ${ASR_GPU}, 端口 ${ASR_PORT})
    asr-rt  ASR 实时流式 WebSocket      (CPU,    端口 ${ASR_RT_PORT}, 依赖 asr)
    tts     Qwen3-TTS 语音合成 HTTP    (GPU ${TTS_GPU}, 端口 ${TTS_PORT})
    all     所有服务（默认）

${YELLOW}首次部署步骤:${NC}
    1. $0 setup               # 创建环境、安装依赖
    2. $0 download all         # 预下载模型（可选）
    3. $0 start all            # 启动所有服务（asr → asr-rt → tts）
    4. $0 test all             # 验证 API 可用
    5. $0 status               # 查看运行状态

${YELLOW}API 端点:${NC}
    ASR 文件识别 (OpenAI 兼容):
      POST http://localhost:${ASR_PORT}/v1/audio/transcriptions   (上传音频文件)
      POST http://localhost:${ASR_PORT}/v1/chat/completions       (audio_url)

    ASR 实时流式 (WebSocket):
      ws://localhost:${ASR_RT_PORT}/ws/asr
      输入: 二进制 PCM (16kHz/16bit/mono) | {"audio":"base64"} | {"action":"end"}
      输出: {"text":"..","sentenceEnd":bool,"final":bool} | {"error":".."}
      VAD 自动检测句尾 → sentenceEnd=true

    TTS (OpenAI 兼容):
      POST http://localhost:${TTS_PORT}/v1/audio/speech            (文本转语音)
      GET  http://localhost:${TTS_PORT}/v1/audio/voices            (说话人列表)

${YELLOW}API 示例:${NC}
    # ASR - 文件识别
    curl -X POST http://localhost:${ASR_PORT}/v1/audio/transcriptions \\
         -F file=@audio.wav -F model=${ASR_MODEL}

    # TTS - 语音合成
    curl -X POST http://localhost:${TTS_PORT}/v1/audio/speech \\
         -H "Content-Type: application/json" \\
         -d '{"input":"你好","voice":"","response_format":"mp3"}' \\
         -o speech.mp3

${YELLOW}GPU 分配总览:${NC}
    GPU 0,1: Qwen3-30B-A3B (tensor-parallel)     [vllm_manager]
    GPU 2:   Embedding-0.6B + Reranker-0.6B       [vllm_manager]
             Qwen3-ASR + Qwen3-TTS                [本脚本, GPU]
    GPU 3:   Embedding-8B + Reranker-8B            [vllm_manager]
    CPU:     ASR 实时 WebSocket (VAD)              [本脚本, 无 GPU]

${YELLOW}端口总览:${NC}
    8000  Qwen3-30B-A3B           [vllm_manager]
    8001  Embedding-8B             [vllm_manager]
    8002  Reranker-8B              [vllm_manager]
    ${ASR_PORT}  Qwen3-ASR (HTTP)        [本脚本]
    ${TTS_PORT}  Qwen3-TTS (HTTP)        [本脚本]
    ${ASR_RT_PORT}  ASR 实时 (WebSocket)    [本脚本]
    8006  Embedding-0.6B           [vllm_manager]
    8007  Reranker-0.6B            [vllm_manager]

${YELLOW}VAD 参数调优:${NC}
    脚本顶部配置区可调:
    ASR_RT_VAD_LEVEL="${ASR_RT_VAD_LEVEL}"       # 0(宽松)~3(激进), 越高越容易判定静音
    ASR_RT_SILENCE_MS="${ASR_RT_SILENCE_MS}"     # 静音多久判定句尾
    ASR_RT_PARTIAL_INTERVAL="${ASR_RT_PARTIAL_INTERVAL}"  # 语音中每隔多久出中间结果

${YELLOW}配置文件:${NC}
    模型/端口/GPU/VAD 等参数在脚本顶部配置区修改

EOF
}

# ==================== 参数校验 ====================

is_valid_service() {
    local s=$1
    for svc in "${ALL_SERVICES[@]}"; do [ "$svc" = "$s" ] && return 0; done
    return 1
}

# ==================== 主程序 ====================

main() {
    local action=$1
    local service=${2:-all}
    local extra=$3

    ensure_dirs

    if [ -z "$action" ]; then
        show_help
        exit 1
    fi

    case "$action" in
        setup)
            setup_env
            ;;

        download)
            if [ "$service" = "all" ] || is_valid_service "$service"; then
                download_models "$service"
            else
                log_error "未知服务: $service"; exit 1
            fi
            ;;

        start)
            if [ "$service" = "all" ] || is_valid_service "$service"; then
                start_service "$service"; echo ""; show_status
            else
                log_error "未知服务: $service"; exit 1
            fi
            ;;

        stop)
            if [ "$service" = "all" ] || is_valid_service "$service"; then
                stop_service "$service"; echo ""; show_status
            else
                log_error "未知服务: $service"; exit 1
            fi
            ;;

        restart)
            if [ "$service" = "all" ] || is_valid_service "$service"; then
                restart_service "$service"; echo ""; show_status
            else
                log_error "未知服务: $service"; exit 1
            fi
            ;;

        status)
            show_status
            ;;

        logs)
            if [ "$service" = "all" ]; then
                log_error "请指定服务名: asr 或 tts"; exit 1
            elif is_valid_service "$service"; then
                show_logs "$service" "${extra:-50}"
            else
                log_error "未知服务: $service"; exit 1
            fi
            ;;

        tail)
            if [ "$service" = "all" ]; then
                log_error "请指定服务名: asr 或 tts"; exit 1
            elif is_valid_service "$service"; then
                tail_logs "$service"
            else
                log_error "未知服务: $service"; exit 1
            fi
            ;;

        test)
            if [ "$service" = "all" ] || is_valid_service "$service"; then
                test_service "$service"
            else
                log_error "未知服务: $service"; exit 1
            fi
            ;;

        help|--help|-h)
            show_help
            ;;

        *)
            log_error "未知命令: $action"; echo ""; show_help; exit 1
            ;;
    esac
}

main "$@"
