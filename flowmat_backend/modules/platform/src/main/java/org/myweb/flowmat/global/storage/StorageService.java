package org.myweb.flowmat.global.storage;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.web.multipart.MultipartFile;
public interface StorageService {
    String store(MultipartFile file,String directory) throws IOException;
    InputStream open(String key) throws IOException;
    void delete(String key) throws IOException;
    String type();
}
