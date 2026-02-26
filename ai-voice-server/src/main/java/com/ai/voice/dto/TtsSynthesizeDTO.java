package com.ai.voice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "文本转语音请求参数")
public class TtsSynthesizeDTO {

    @NotBlank(message = "文本不能为空")
    @Size(max = 20000, message = "文本长度不能超过 20000 字符")
    @Schema(description = "待合成文本", example = "你好，今天天气怎么样？", requiredMode = Schema.RequiredMode.REQUIRED)
    private String text;

    @Schema(description = "音色，不传则使用服务默认 本地模型支持：'aiden', 'dylan', 'eric', 'ono_anna', 'ryan', 'serena', 'sohee', 'uncle_fu', 'vivian'", example = "vivian")
    private String voice;
}
