package org.myweb.flowmat.global.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import org.myweb.flowmat.global.config.StorageProperties;
import org.springframework.web.multipart.MultipartFile;

/** Validates bounded content before it reaches a storage backend; downloads always use attachment + nosniff. */
public final class UploadValidation {
    private UploadValidation() {}
    public record File(String fileName, String contentType, byte[] bytes, String sha256) {}
    public static File validate(MultipartFile file, StorageProperties properties) throws IOException {
        StorageFilenamePolicy.createStoredFilename(file, properties);
        byte[] bytes;
        try (var in=file.getInputStream()) {
            int limit=(int)Math.min(properties.getMaxFileSizeBytes(),Integer.MAX_VALUE-1);
            bytes=in.readNBytes(limit+1);
            if(bytes.length>limit || bytes.length!=file.getSize()) throw new IOException("file size does not match the allowed upload size.");
        }
        String name=file.getOriginalFilename().replace('\\','/');
        name=name.substring(name.lastIndexOf('/')+1);
        String type=file.getContentType().split(";",2)[0].trim().toLowerCase(Locale.ROOT);
        boolean valid=switch(type) {
            case "image/png" -> starts(bytes,new byte[]{(byte)137,80,78,71,13,10,26,10});
            case "image/jpeg" -> starts(bytes,new byte[]{(byte)255,(byte)216,(byte)255});
            case "image/gif" -> starts(bytes,"GIF87a".getBytes(StandardCharsets.US_ASCII)) || starts(bytes,"GIF89a".getBytes(StandardCharsets.US_ASCII));
            case "image/webp" -> bytes.length>=12 && starts(bytes,"RIFF".getBytes(StandardCharsets.US_ASCII)) && new String(bytes,8,4,StandardCharsets.US_ASCII).equals("WEBP");
            case "application/pdf" -> starts(bytes,"%PDF-".getBytes(StandardCharsets.US_ASCII));
            case "text/plain","text/csv" -> text(bytes);
            default -> false;
        };
        if(!valid) throw new IOException("file content does not match its contentType.");
        try { return new File(name,type,bytes,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static boolean starts(byte[] bytes,byte[] prefix) {
        if(bytes.length<prefix.length) return false;
        for(int i=0;i<prefix.length;i++) if(bytes[i]!=prefix[i]) return false;
        return true;
    }
    private static boolean text(byte[] bytes) {
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return text.codePoints().noneMatch(c->Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t');
        } catch(java.nio.charset.CharacterCodingException e) { return false; }
    }
}
