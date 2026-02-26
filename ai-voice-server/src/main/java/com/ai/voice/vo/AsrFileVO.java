package com.ai.voice.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "录音文件识别结果")
public class AsrFileVO {

    @Schema(description = "识别得到的完整文本")
    private String text;
}
