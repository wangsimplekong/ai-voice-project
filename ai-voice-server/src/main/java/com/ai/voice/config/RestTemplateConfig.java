package com.ai.voice.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 全局 RestTemplate 配置
 * <p>
 * - 统一超时配置
 * - 作为全局 Bean 提供给各处注入使用
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        RequestConfig config = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMinutes(2)) // 60秒，更简洁
                .build();
        CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(config)
                .build();
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(client));
    }

    @Bean
    public ClientHttpRequestFactory clientHttpRequestFactory() {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        // 连接超时（毫秒）
        factory.setConnectTimeout(5000);
        // 从连接池获取连接超时（毫秒）
        factory.setConnectionRequestTimeout(5000);
        return factory;
    }
}
