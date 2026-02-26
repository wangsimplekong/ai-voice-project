package com.ai.voice.engine.dashscope;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.transcription.*;
import com.alibaba.dashscope.audio.qwen_asr.*;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.google.gson.*;
import com.ai.voice.config.DashScopeProperties;
import com.ai.voice.engine.IAsrFileEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

/**
 * 百炼文件识别引擎路由器，根据 asr-file.model 自动选择 SDK API：
 * <ul>
 *   <li>fun-asr-realtime / paraformer-realtime-* → Recognition.call(param, File) 同步识别</li>
 *   <li>fun-asr / paraformer-v2 / sensevoice-* → Transcription API（异步，需文件 URL）</li>
 *   <li>qwen3-asr-flash-filetrans → QwenTranscription API（异步，需文件 URL）</li>
 *   <li>qwen3-asr-flash → MultiModalConversation API（同步，需文件 URL）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.voice.local.enabled", havingValue = "false", matchIfMissing = true)
public class DashScopeAsrFileEngine implements IAsrFileEngine {

    private final DashScopeProperties props;

    /**
     * 根据 asr-file.model 选择对应 API 识别音频文件
     *
     * @param audioFile  音频文件
     * @param format     格式（wav/mp3 等）
     * @param sampleRate 采样率
     * @return 识别文本，失败返回 null
     */
    @Override
    public String recognizeFile(File audioFile, String format, int sampleRate) {
        // 1. 校验 API Key
        String apiKey = props.getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            log.warn("DashScope api-key 未配置");
            return null;
        }

        // 2. 按配置的 model 路由到对应 API
        String model = props.getAsrFile().getModel();
        if (isRealtimeRecognitionModel(model)) {
            return recognizeViaRecognition(audioFile, format, sampleRate, model, apiKey);
        }
        if (isTranscriptionModel(model)) {
            return recognizeViaTranscription(audioFile, model, apiKey);
        }
        if (isQwenFiletransModel(model)) {
            return recognizeViaQwenTranscription(audioFile, model, apiKey);
        }
        if (isQwenMultiModalModel(model)) {
            return recognizeViaMultiModal(audioFile, model, apiKey);
        }

        log.warn("文件识别: 不支持的模型 {}，尝试 Recognition API", model);
        return recognizeViaRecognition(audioFile, format, sampleRate, model, apiKey);
    }

    /**
     * Recognition.call(param, File)：同步识别本地文件
     * 适用于 fun-asr-realtime / paraformer-realtime-v2 等实时模型
     */
    private String recognizeViaRecognition(File file, String format, int rate, String model, String apiKey) {
        RecognitionParam param = RecognitionParam.builder()
                .model(model)
                .apiKey(apiKey)
                .format(StringUtils.hasText(format) ? format : "wav")
                .sampleRate(rate > 0 ? rate : props.getAsr().getSampleRate())
                .build();
        Recognition recognizer = new Recognition();
        try {
            String result = recognizer.call(param, file);
            return result;
        } catch (Exception e) {
            log.error("Recognition 文件识别失败: {}", e.getMessage());
            return null;
        } finally {
            try { if (recognizer.getDuplexApi() != null) recognizer.getDuplexApi().close(1000, "bye"); }
            catch (Exception ignored) { }
        }
    }

    /**
     * Transcription API：异步批量识别
     * 适用于 fun-asr / paraformer-v2 / sensevoice-v1
     * 注意：需要文件可通过公网 URL 访问
     */
    private String recognizeViaTranscription(File file, String model, String apiKey) {
        String fileUrl = toAccessibleUrl(file);
        if (fileUrl == null) {
            log.error("Transcription API 需要可公网访问的文件 URL，请将文件上传到 OSS 或配置文件服务");
            return null;
        }

        TranscriptionParam param = TranscriptionParam.builder()
                .apiKey(apiKey)
                .model(model)
                .fileUrls(Collections.singletonList(fileUrl))
                .build();

        try {
            Transcription transcription = new Transcription();
            TranscriptionResult result = transcription.asyncCall(param);
            result = transcription.wait(TranscriptionQueryParam.FromTranscriptionParam(param, result.getTaskId()));
            return extractTranscriptionText(result);
        } catch (Exception e) {
            log.error("Transcription 文件识别失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * QwenTranscription API：异步批量识别
     * 适用于 qwen3-asr-flash-filetrans
     */
    private String recognizeViaQwenTranscription(File file, String model, String apiKey) {
        String fileUrl = toAccessibleUrl(file);
        if (fileUrl == null) {
            log.error("QwenTranscription API 需要可公网访问的文件 URL");
            return null;
        }

        QwenTranscriptionParam param = QwenTranscriptionParam.builder()
                .apiKey(apiKey)
                .model(model)
                .fileUrl(fileUrl)
                .build();

        try {
            QwenTranscription transcription = new QwenTranscription();
            QwenTranscriptionResult result = transcription.asyncCall(param);
            result = transcription.wait(QwenTranscriptionQueryParam.FromTranscriptionParam(param, result.getTaskId()));
            return extractQwenTranscriptionText(result);
        } catch (Exception e) {
            log.error("QwenTranscription 文件识别失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * MultiModalConversation API：同步识别
     * 适用于 qwen3-asr-flash
     */
    private String recognizeViaMultiModal(File file, String model, String apiKey) {
        String fileUrl = toAccessibleUrl(file);
        if (fileUrl == null) {
            log.error("MultiModalConversation API 需要可公网访问的文件 URL");
            return null;
        }

        try {
            MultiModalConversation conv = new MultiModalConversation();
            MultiModalMessage userMsg = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(Collections.singletonList(Collections.singletonMap("audio", fileUrl)))
                    .build();

            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey)
                    .model(model)
                    .message(userMsg)
                    .build();

            MultiModalConversationResult result = conv.call(param);
            return result.getOutput().getChoices().get(0).getMessage().getContent().get(0).get("text").toString();
        } catch (Exception e) {
            log.error("MultiModal 文件识别失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将本地文件转为可公网访问的 URL（当前未实现，返回 null）
     * TODO: 接入 OSS 上传后在此返回公网 URL
     *
     * @param file 本地音频文件
     * @return 公网可访问 URL，当前固定返回 null
     */
    private String toAccessibleUrl(File file) {
        log.warn("离线模型需要可公网访问的文件 URL，当前暂未配置 OSS。建议 asr-file.model 使用实时模型（如 fun-asr-realtime）");
        return null;
    }

    /**
     * 从 Transcription 异步任务结果中提取识别文本
     *
     * @param result 异步识别结果（含结果 URL）
     * @return 拼接后的文本，解析失败返回 null
     */
    private String extractTranscriptionText(TranscriptionResult result) {
        try {
            List<TranscriptionTaskResult> tasks = result.getResults();
            if (tasks == null || tasks.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            Gson gson = new Gson();
            for (TranscriptionTaskResult task : tasks) {
                String url = task.getTranscriptionUrl();
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    JsonObject json = gson.fromJson(reader, JsonObject.class);
                    JsonArray transcripts = json.getAsJsonArray("transcripts");
                    if (transcripts != null) {
                        for (JsonElement t : transcripts) {
                            String text = t.getAsJsonObject().get("text").getAsString();
                            if (StringUtils.hasText(text)) {
                                if (sb.length() > 0) sb.append(" ");
                                sb.append(text.trim());
                            }
                        }
                    }
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            log.error("解析 Transcription 结果失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 QwenTranscription 异步任务结果中提取识别文本
     *
     * @param result 异步识别结果
     * @return 识别文本，解析失败返回 null
     */
    private String extractQwenTranscriptionText(QwenTranscriptionResult result) {
        try {
            QwenTranscriptionTaskResult task = result.getResult();
            if (task == null) return null;
            String url = task.getTranscriptionUrl();
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                JsonObject json = new Gson().fromJson(reader, JsonObject.class);
                if (json.has("text")) return json.get("text").getAsString();
                JsonArray transcripts = json.getAsJsonArray("transcripts");
                if (transcripts != null && transcripts.size() > 0) {
                    return transcripts.get(0).getAsJsonObject().get("text").getAsString();
                }
            }
        } catch (Exception e) {
            log.error("解析 QwenTranscription 结果失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 是否为实时 Recognition 模型（fun-asr-realtime / paraformer-realtime-*）
     *
     * @param m 模型名
     * @return 是否匹配
     */
    private static boolean isRealtimeRecognitionModel(String m) {
        if (m == null) return false;
        String lower = m.toLowerCase();
        return lower.contains("realtime") && !lower.startsWith("qwen") && !lower.startsWith("gummy");
    }

    /**
     * 是否为 Transcription 异步模型（fun-asr / paraformer-v2 / sensevoice-*）
     *
     * @param m 模型名
     * @return 是否匹配
     */
    private static boolean isTranscriptionModel(String m) {
        if (m == null) return false;
        String lower = m.toLowerCase();
        return (lower.startsWith("fun-asr") || lower.startsWith("paraformer") || lower.startsWith("sensevoice"))
                && !lower.contains("realtime");
    }

    /**
     * 是否为 Qwen 文件转写模型（qwen3-asr-flash-filetrans）
     *
     * @param m 模型名
     * @return 是否匹配
     */
    private static boolean isQwenFiletransModel(String m) {
        return m != null && m.toLowerCase().contains("filetrans");
    }

    /**
     * 是否为 Qwen 多模态同步模型（qwen3-asr-flash）
     *
     * @param m 模型名
     * @return 是否匹配
     */
    private static boolean isQwenMultiModalModel(String m) {
        if (m == null) return false;
        String lower = m.toLowerCase();
        return lower.startsWith("qwen") && lower.contains("asr") && !lower.contains("realtime") && !lower.contains("filetrans");
    }
}
