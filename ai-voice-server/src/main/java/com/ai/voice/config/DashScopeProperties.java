package com.ai.voice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 百炼 DashScope 语音服务配置（ASR / TTS / LLM）
 * 建议在 Nacos 中配置 api-key，勿将密钥写入代码库
 */
@Data
@ConfigurationProperties(prefix = "ai.voice.dashscope")
public class DashScopeProperties {

    /**
     * 百炼 API Key，必填
     */
    private String apiKey = "";

    /**
     * 地域：cn-beijing | ap-southeast-1（新加坡）
     */
    private String region = "cn-beijing";

    private Asr asr = new Asr();
    private AsrFile asrFile = new AsrFile();
    private Tts tts = new Tts();
    private Llm llm = new Llm();

    /**
     * OmniRealtime 专用 WebSocket（qwen3-asr 系列）
     */
    public String getRealtimeWsUrl() {
        return "cn-beijing".equalsIgnoreCase(region)
                ? "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
                : "wss://dashscope-intl.aliyuncs.com/api-ws/v1/realtime";
    }

    /**
     * Recognition / Gummy / TTS 共用 WebSocket
     */
    public String getInferenceWsUrl() {
        return "cn-beijing".equalsIgnoreCase(region)
                ? "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
                : "wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference";
    }

    /**
     * Transcription / QwenTranscription / MultiModal HTTP
     */
    public String getHttpApiUrl() {
        return "cn-beijing".equalsIgnoreCase(region)
                ? "https://dashscope.aliyuncs.com/api/v1"
                : "https://dashscope-intl.aliyuncs.com/api/v1";
    }

    @Data
    public static class Asr {
        /**
         * 实时 ASR 模型（改 model 即自动路由）：
         * qwen3-asr-flash-realtime → OmniRealtime |
         * fun-asr-realtime / paraformer-realtime-v2 → Recognition |
         * gummy-realtime-v1 → Gummy | gummy-chat-v1 → GummyChat
         */
        private String model = "fun-asr-realtime";
        private int sampleRate = 16000;
        private String language = "zh";
    }

    @Data
    public static class AsrFile {
        /**
         * 文件识别模型（可与实时模型不同）：
         * fun-asr-realtime / paraformer-realtime-* → Recognition.call(File) |
         * fun-asr / paraformer-v2 / sensevoice-v1 → Transcription |
         * qwen3-asr-flash-filetrans → QwenTranscription |
         * qwen3-asr-flash → MultiModalConversation
         */
        private String model = "fun-asr-realtime";
    }

    @Data
    public static class Tts {
        /**
         * CosyVoice 全系列：cosyvoice-v1 / v2 / v3-flash / v3-plus
         */
        private String model = "cosyvoice-v3-flash";
        private String voice = "longanyang";
    }

    @Data
    public static class Llm {
        /**
         * ASR 纠错用小模型
         */
        private String model = "qwen-turbo";
    }
}
