package top.hazenix.controller.web;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import top.hazenix.constant.ErrorCode;
import top.hazenix.constant.MessageConstant;
import top.hazenix.exception.FailUploadException;
import top.hazenix.result.Result;
import top.hazenix.utils.AliOssUtil;

import java.io.IOException;
import java.util.Objects;

@RestController
@RequestMapping("/common")
@Slf4j
@RequiredArgsConstructor
public class WebController {

    private final AliOssUtil aliOssUtil;

    @PostMapping("/upload")
    public Result upload(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            log.warn("上传失败：收到空文件");
            throw new FailUploadException(ErrorCode.B00004, "上传文件为空");
        }

        String originalFilename = Objects.requireNonNullElse(file.getOriginalFilename(), "").trim();
        if (originalFilename.isEmpty() || !originalFilename.contains(".")) {
            log.warn("上传失败：文件名非法，filename={}", originalFilename);
            throw new FailUploadException(ErrorCode.B00004, "文件名不合法");
        }

        log.info("文件上传：filename={}, size={} bytes, contentType={}",
                originalFilename, file.getSize(), file.getContentType());

        String url;
        try {
            url = aliOssUtil.upload(file.getBytes(), originalFilename);
        } catch (IOException e) {
            log.error("读取上传文件失败，filename={}", originalFilename, e);
            throw new FailUploadException(ErrorCode.B00004, MessageConstant.UPLOAD_FAILED);
        }
        return Result.success(url);
    }

}
