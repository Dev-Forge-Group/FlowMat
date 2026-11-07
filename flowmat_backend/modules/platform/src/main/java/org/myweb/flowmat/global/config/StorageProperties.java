package org.myweb.flowmat.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@org.springframework.validation.annotation.Validated
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    private String type = "local";
    private String s3Bucket;
    private String s3Region = "us-east-1";
    private String s3Endpoint;
    private boolean s3PathStyle = false;
    private String uploadDir = "./uploads";
    @jakarta.validation.constraints.Min(1)
    @jakarta.validation.constraints.Max(2147483646L)
    private long maxFileSizeBytes = 10 * 1024 * 1024;
    private String allowedExtensions = "png,jpg,jpeg,gif,webp,pdf,txt,csv";
    private String allowedMimeTypes =
        "image/png,image/jpeg,image/gif,image/webp,application/pdf,text/plain,text/csv";
}
