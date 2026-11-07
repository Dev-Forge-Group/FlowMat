package org.myweb.flowmat.global.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import org.myweb.flowmat.global.config.StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@ConditionalOnProperty(prefix="app.storage",name="type",havingValue="local",matchIfMissing=true)
public class LocalStorageService implements StorageService {
    private final StorageProperties properties;
    public LocalStorageService(StorageProperties properties) { this.properties=properties; }
    @Override public String type() { return "local"; }
    @Override public String store(MultipartFile file,String directory) throws IOException {
        UploadValidation.File validated=UploadValidation.validate(file,properties);
        Path root=root();
        Path dir=directory==null || directory.isBlank()?root:safe(root,directory);
        Files.createDirectories(dir);
        if(directory!=null && !directory.isBlank()) safe(root,directory);
        Path target=dir.resolve(StorageFilenamePolicy.createStoredFilename(file,properties));
        Files.write(target,validated.bytes(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        return root.relativize(target).toString().replace('\\','/');
    }
    @Override public InputStream open(String key) throws IOException {
        Path target=safe(root(),key);
        if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Stored file is unavailable.");
        return Files.newInputStream(target,LinkOption.NOFOLLOW_LINKS);
    }
    @Override public void delete(String key) throws IOException { Files.deleteIfExists(safe(root(),key)); }
    private Path root() throws IOException {
        Path configured=Path.of(properties.getUploadDir()).toAbsolutePath().normalize();
        if(Files.isSymbolicLink(configured)) throw new IOException("Storage root must not be a symbolic link.");
        Files.createDirectories(configured);
        return configured.toRealPath();
    }
    private Path safe(Path root,String key) throws IOException {
        if(key==null || key.isBlank()) throw new IOException("Storage key or directory is required.");
        Path target=StorageFilenamePolicy.resolveDirectory(root,key);
        Path cursor=root;
        for(Path segment:root.relativize(target)) {
            cursor=cursor.resolve(segment);
            if(Files.isSymbolicLink(cursor) || (Files.exists(cursor,LinkOption.NOFOLLOW_LINKS) && !cursor.toRealPath().startsWith(root)))
                throw new IOException("Stored file path contains a link outside storage.");
        }
        return target;
    }
}
