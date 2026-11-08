package org.myweb.flowmat.global.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.myweb.flowmat.global.config.StorageProperties;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@Service
@ConditionalOnProperty(prefix="app.storage",name="type",havingValue="s3")
public class S3StorageService implements StorageService {
    private final StorageProperties properties;
    private final S3Client client;
    public S3StorageService(StorageProperties properties,S3Client client) { this.properties=properties; this.client=client; }
    @Override public String type() { return "s3"; }
    @Override public String store(MultipartFile file,String directory) throws IOException {
        UploadValidation.File validated=UploadValidation.validate(file,properties);
        Path root=Path.of(".").toAbsolutePath().normalize();
        String prefix=root.relativize(StorageFilenamePolicy.resolveDirectory(root,directory)).toString().replace('\\','/');
        String key=(prefix.isBlank()?"":prefix+"/")+StorageFilenamePolicy.createStoredFilename(file,properties);
        try {
            client.putObject(PutObjectRequest.builder().bucket(properties.getS3Bucket()).key(key).contentType(validated.contentType()).build(),RequestBody.fromBytes(validated.bytes()));
            return key;
        } catch(RuntimeException e) { throw new IOException("Storage upload failed.",e); }
    }
    @Override public InputStream open(String key) throws IOException {
        try { return client.getObject(GetObjectRequest.builder().bucket(properties.getS3Bucket()).key(safeKey(key)).build()); }
        catch(RuntimeException e) { throw new IOException("Stored file is unavailable.",e); }
    }
    @Override public void delete(String key) throws IOException {
        try { client.deleteObject(DeleteObjectRequest.builder().bucket(properties.getS3Bucket()).key(safeKey(key)).build()); }
        catch(RuntimeException e) { throw new IOException("Storage deletion failed.",e); }
    }
    private String safeKey(String key) throws IOException {
        if(key==null || key.isBlank()) throw new IOException("Storage key is required.");
        Path root=Path.of(".").toAbsolutePath().normalize();
        return root.relativize(StorageFilenamePolicy.resolveDirectory(root,key)).toString().replace('\\','/');
    }
}
