package top.hazenix.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.List;

@Data
public class MomentDTO {

    @ApiModelProperty(value = "标题（可选）")
    @Size(max = 100, message = "标题不能超过100个字符")
    private String title;

    @ApiModelProperty(value = "正文")
    @NotBlank(message = "正文不能为空")
    @Size(max = 2000, message = "正文不能超过2000个字符")
    private String content;

    @ApiModelProperty(value = "图片URL列表（最多9张）")
    @Size(max = 9, message = "最多上传9张图片")
    private List<String> imageUrls;

    @ApiModelProperty(value = "标签ID列表")
    private List<Integer> tagIds;

    @ApiModelProperty(value = "状态[0:正常 | 1:草稿]")
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态最小值为0")
    @Max(value = 1, message = "状态最大值为1")
    private Integer status;
}
