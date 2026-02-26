package com.ai.voice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j 统一接口文档配置（OpenAPI 元信息）
 * 访问地址：http://localhost:端口号/doc.html
 * 启用与路径由 application.yml 中 springdoc / knife4j 控制
 */
@Configuration
public class Knife4jConfig {

    @Value("${spring.application.name:}")
    private String applicationName;

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title(applicationName + " API")
                        .description(" 项目 —— " + applicationName + " 模块 API 文档")
                        .version("v0.0.1")
                        .contact(new Contact()
                                .name("开发团队")
                                .email("developer@163.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("http://www.apache.org/licenses/LICENSE-2.0.html")));
    }
}
