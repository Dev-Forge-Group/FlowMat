package org.myweb.flowmat.global.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@ConditionalOnProperty(prefix="app.storage",name="type",havingValue="s3")
public class S3StorageConfiguration {
    @Bean(destroyMethod="close") S3Client instructionS3Client(StorageProperties p) {
        if(p.getS3Bucket()==null || p.getS3Bucket().isBlank()) throw new IllegalStateException("app.storage.s3-bucket is required for S3 storage.");
        var builder=S3Client.builder().region(Region.of(p.getS3Region())).forcePathStyle(p.isS3PathStyle())
            .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(30)))
            .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(45)));
        if(p.getS3Endpoint()!=null && !p.getS3Endpoint().isBlank()) {
            URI endpoint=URI.create(p.getS3Endpoint());
            if(endpoint.getHost()==null || endpoint.getUserInfo()!=null || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme())))
                throw new IllegalStateException("app.storage.s3-endpoint must be an http(s) endpoint without embedded credentials.");
            builder.endpointOverride(endpoint);
        }
        return builder.build();
    }
}
