package org.myweb.flowmat.global.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.myweb.flowmat.global.config.StorageProperties;
import org.springframework.web.multipart.MultipartFile;

final class StorageFilenamePolicy {

    private static final int MAX_FILENAME_LENGTH = 255;
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("[\\p{Cntrl}]");

    private StorageFilenamePolicy() {
    }

    static Path resolveDirectory(Path uploadRoot, String directory) throws IOException {
        if (directory == null || directory.isBlank()) {
            return uploadRoot;
        }
        String normalizedDirectory = directory.replace('\\', '/');
        if (normalizedDirectory.startsWith("/")
            || normalizedDirectory.startsWith("//")
            || normalizedDirectory.matches("^[A-Za-z]:/.*")
            || CONTROL_CHARACTER.matcher(normalizedDirectory).find()) {
            throw new IOException("Upload directory must be a relative safe path.");
        }
        Path resolved = uploadRoot.resolve(normalizedDirectory).normalize();
        if (!resolved.startsWith(uploadRoot)) {
            throw new IOException("Upload directory escapes the configured upload root.");
        }
        return resolved;
    }

    static String createStoredFilename(MultipartFile file, StorageProperties properties) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IOException("Uploaded file must not be empty.");
        }
        if (file.getSize() > properties.getMaxFileSizeBytes()) {
            throw new IOException("Uploaded file exceeds the configured size limit.");
        }

        String originalFilename = file.getOriginalFilename();
        String basename = basename(originalFilename);
        if (basename.isBlank() || basename.length() > MAX_FILENAME_LENGTH
            || CONTROL_CHARACTER.matcher(basename).find()) {
            throw new IOException("Uploaded filename is invalid.");
        }

        String extension = extensionOf(basename);
        Set<String> allowedExtensions = csv(properties.getAllowedExtensions());
        Set<String> allowedMimeTypes = csv(properties.getAllowedMimeTypes());
        String contentType = file.getContentType() == null
            ? ""
            : file.getContentType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);

        if (extension.isBlank() || !allowedExtensions.contains(extension)) {
            throw new IOException("Uploaded file extension is not allowed.");
        }
        if (contentType.isBlank() || !allowedMimeTypes.contains(contentType)) {
            throw new IOException("Uploaded file MIME type is not allowed.");
        }
        if (!mimeMatchesExtension(extension, contentType)) {
            throw new IOException("Uploaded file extension and MIME type do not match.");
        }

        return UUID.randomUUID() + "." + extension;
    }

    private static String basename(String filename) {
        if (filename == null) {
            return "";
        }
        String normalized = filename.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        return separator >= 0 ? normalized.substring(separator + 1) : normalized;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Set<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .map(item -> item.toLowerCase(Locale.ROOT))
            .filter(item -> !item.isBlank())
            .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean mimeMatchesExtension(String extension, String contentType) {
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg".equals(contentType);
            case "png" -> "image/png".equals(contentType);
            case "gif" -> "image/gif".equals(contentType);
            case "webp" -> "image/webp".equals(contentType);
            case "pdf" -> "application/pdf".equals(contentType);
            case "txt" -> "text/plain".equals(contentType);
            case "csv" -> "text/csv".equals(contentType);
            default -> false;
        };
    }
}
