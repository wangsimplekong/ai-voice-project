package com.ai.voice.engine.local;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.ai.voice.config.LocalVoiceProperties;
import com.ai.voice.engine.IAsrFileEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.time.Duration;

/**
 * 本地自部署文件 ASR 引擎
 * 调用 qwen-asr-serve 与脚本一致的接口: POST /v1/chat/completions（audio_url/base64），避免 transcriptions 的 model 不匹配问题
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.voice.local.enabled", havingValue = "true")
public class LocalAsrFileEngine implements IAsrFileEngine {

    private final LocalVoiceProperties props;
    private final RestTemplate restTemplate;

    public LocalAsrFileEngine(LocalVoiceProperties props) {
        this.props = props;
        this.restTemplate = createRestTemplate(props.getTimeoutMs());
    }

    /**
     * 创建带连接/读取超时的 RestTemplate
     *
     * @param timeoutMs 连接与读取超时时间（毫秒）
     * @return 配置好超时的 RestTemplate
     */
    private static RestTemplate createRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return new RestTemplate(factory);
    }

    /**
     * 调用本地 qwen-asr-serve 识别音频文件
     *
     * @param audioFile  音频文件
     * @param format     格式（wav/mp3 等）
     * @param sampleRate 采样率
     * @return 识别文本，失败返回 null
     */
    @Override
    public String recognizeFile(File audioFile, String format, int sampleRate) {
        if (audioFile == null || !audioFile.exists()) return null;

        String url = props.getAsrUrl() + "/v1/chat/completions";
        log.debug("本地 ASR 文件识别: {} -> {}", audioFile.getName(), url);

        try {
            byte[] bytes = Files.readAllBytes(audioFile.toPath());
            String base64Audio = Base64.getEncoder().encodeToString(bytes);
            String mime = "audio/wav";
            if (format != null) {
                if (format.toLowerCase().contains("mp3")) mime = "audio/mpeg";
                else if (format.toLowerCase().contains("wav")) mime = "audio/wav";
            }
            String dataUrl = "data:" + mime + ";base64," + base64Audio;

            // 与 voice_manager.sh test 一致：messages + content[].audio_url
            JSONObject audioUrl = new JSONObject();
            audioUrl.put("url", dataUrl);
            JSONObject contentItem = new JSONObject();
            contentItem.put("type", "audio_url");
            contentItem.put("audio_url", audioUrl);
            JSONArray content = new JSONArray();
            content.add(contentItem);
            JSONObject message = new JSONObject();
            message.put("role", "user");
            message.put("content", content);
            JSONArray messages = new JSONArray();
            messages.add(message);
            JSONObject body = new JSONObject();
            body.put("messages", messages);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body.toJSONString(), headers), String.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                String text = extractTextFromChatCompletions(resp.getBody());
                log.debug("ASR 文件识别结果: {}", text);
                return text;
            }

            log.warn("ASR 文件识别返回非 200: {}", resp.getStatusCode());
            return null;
        } catch (HttpClientErrorException e) {
            String errBody = e.getResponseBodyAsString();
            log.error("本地 ASR 文件识别失败: {} {}", e.getStatusCode(), errBody != null ? errBody : "");
            return null;
        } catch (Exception e) {
            log.error("本地 ASR 文件识别失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 chat/completions 响应中取出识别文本
     * 服务端可能返回 "language Chinese<asr_text>你好，今天天气怎么样？" 或带 "</asr_text>"，只保留 <asr_text> 内的正文
     */
    private String extractTextFromChatCompletions(String responseBody) {
        try {
            JSONObject json = JSON.parseObject(responseBody);
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) return null;
            String content = choices.getJSONObject(0).getJSONObject("message").getString("content");
            if (content == null) return null;
            content = content.trim();
            // 有 <asr_text> 则只取标签内正文（兼容无闭合 </asr_text> 的情况）
            int start = content.indexOf("<asr_text>");
            if (start >= 0) {
                content = content.substring(start + "<asr_text>".length());
                int end = content.indexOf("</asr_text>");
                if (end >= 0) content = content.substring(0, end);
                content = content.trim();
            }
            return content.isEmpty() ? null : content;
        } catch (Exception e) {
            log.warn("解析 chat/completions 响应失败: {}", e.getMessage());
            return null;
        }
    }
}
