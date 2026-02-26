package com.ai.voice.newapi;

import com.alibaba.dashscope.utils.JsonUtils;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewApiChatUtils {

    @Resource
    private RestTemplate restTemplate;

    /**
     * NewApi 配置Url
     */
    @Value("${ai.newapi.base-url:}")
    private String baseUrl;
    /**
     * NewApi 配置Key
     */
    @Value("${ai.newapi.api-key:}")
    private String apiKey;



    /**
     * 大模型单次对话
     *
     * @param model        模型code
     * @param systemPrompt 系统提示词
     * @param message      用户输入
     * @return 模型输出
     */
    public String chat(String model, String systemPrompt, String message) {

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", StringUtils.isNotBlank(systemPrompt) ? List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", message)
                ) : List.of(Map.of("role", "user", "content", message)),
                "stream", false,
                "enable_thinking", false
        );
        String url = baseUrl + "/v1/chat/completions";

        // 构建请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Authorization", "Bearer " + apiKey );


        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> responseBody = response.getBody();

                // 解析响应
                List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
                Map<String, Object> responseMessage = (Map<String, Object>) choices.get(0).get("message");


                return (String) responseMessage.get("content");

            }

            log.info("chat 请求失败 请求参数: url:{} body:{} headers:{} 返回参数 resp:{}",
                    url,
                    JsonUtils.toJson(requestBody),
                    JsonUtils.toJson(headers),
                    JsonUtils.toJson(response.getBody()));
            return null;

        } catch (RestClientException e) {
            log.error("chat 请求异常 请求参数: url:{} body:{} headers:{} 异常信息 e.getMessage:{}",
                    url,
                    JsonUtils.toJson(requestBody),
                    JsonUtils.toJson(headers),
                    e.getMessage());
            return null;
        }
    }
}
