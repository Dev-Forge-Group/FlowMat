package org.myweb.flowmat.domain.user.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.myweb.flowmat.domain.user.api.dto.response.FaceMatchResponse;
import org.myweb.flowmat.domain.user.api.dto.response.FaceMatchResponse.FaceMatchedAccount;
import org.myweb.flowmat.domain.user.api.dto.response.UserTokenResponse;
import org.myweb.flowmat.domain.user.domain.entity.FaceDescriptor;
import org.myweb.flowmat.domain.user.domain.entity.User;
import org.myweb.flowmat.domain.user.repository.FaceDescriptorRepository;
import org.myweb.flowmat.domain.user.repository.UserRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FaceAuthServiceImpl implements FaceAuthService {

    private static final double THRESHOLD = 0.37;
    private static final int LOCK_MINUTES = 10;
    private static final Duration MATCH_TOKEN_TTL = Duration.ofMinutes(10);

    private final FaceDescriptorRepository faceDescriptorRepository;
    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;
    private final ObjectMapper objectMapper;
    private final AuthRedisStore authRedisStore;
    private final FaceFailureService faceFailureService;

    @Value("${face.aes-key:FlowMatFaceKey!!FlowMatFaceKey!!}")
    private String aesKeyRaw;

    @Override
    @Transactional
    public void registerDescriptor(String userId, String descriptorJson) {
        validate128d(descriptorJson);
        String encrypted = aesEncrypt(descriptorJson);

        FaceDescriptor descriptor = faceDescriptorRepository.findByUserId(userId).orElse(null);
        if (descriptor == null) {
            descriptor = new FaceDescriptor();
            descriptor.setUserId(userId);
            descriptor.setDescriptor(toJsonArray(List.of(encrypted)));
            descriptor.setFailCount(0);
        } else {
            List<String> existing = fromJsonArray(descriptor.getDescriptor());
            existing.add(encrypted);
            if (existing.size() > FaceDescriptor.MAX_VECTORS) {
                existing = existing.subList(existing.size() - FaceDescriptor.MAX_VECTORS, existing.size());
            }
            descriptor.setDescriptor(toJsonArray(existing));
            descriptor.resetFailure();
        }
        faceDescriptorRepository.save(descriptor);
        log.info("[FACE_REGISTER] userId={} vectors={}", userId, fromJsonArray(descriptor.getDescriptor()).size());
    }

    @Override
    public int getVectorCount(String userId) {
        return faceDescriptorRepository.findByUserId(userId)
            .map(fd -> fromJsonArray(fd.getDescriptor()).size())
            .orElse(0);
    }

    @Override
    @Transactional
    public FaceMatchResponse matchFace(String descriptorJson) {
        validate128d(descriptorJson);
        List<FaceDescriptor> all = faceDescriptorRepository.findAll();
        if (all.isEmpty()) {
            throw new BusinessException(ErrorCode.FACE_NOT_REGISTERED);
        }

        double[] incoming = toDoubleArray(descriptorJson);
        List<FaceDescriptor> unlocked = all.stream().filter(fd -> !fd.isLocked()).toList();
        if (unlocked.isEmpty()) {
            throw new BusinessException(ErrorCode.FACE_ACCOUNT_LOCKED);
        }

        List<FaceDistance> candidates = unlocked.stream()
            .map(fd -> new FaceDistance(fd, minDistance(incoming, fromJsonArray(fd.getDescriptor()))))
            .filter(fd -> fd.distance() < THRESHOLD)
            .sorted(Comparator.comparingDouble(FaceDistance::distance))
            .toList();

        if (candidates.isEmpty()) {
            unlocked.stream()
                .min(Comparator.comparingDouble(fd -> minDistance(incoming, fromJsonArray(fd.getDescriptor()))))
                .ifPresent(fd -> {
                    faceFailureService.recordFailure(fd.getFaceId());
                });
            throw new BusinessException(ErrorCode.FACE_NOT_RECOGNIZED);
        }

        List<FaceMatchedAccount> accounts = new ArrayList<>();
        List<String> userIds = new ArrayList<>();
        for (FaceDistance candidate : candidates) {
            String uid = candidate.descriptor().getUserId();
            userRepository.findByUserId(uid).ifPresent(user -> {
                if (!canLogin(user)) {
                    return;
                }
                int confidence = (int) Math.round((1.0 - candidate.distance() / THRESHOLD) * 100);
                if (confidence < 20) {
                    return;
                }
                accounts.add(FaceMatchedAccount.builder()
                    .userId(uid)
                    .maskedEmail(maskEmail(user.getUserEmail()))
                    .displayName(resolveDisplayName(user))
                    .confidence(Math.max(0, Math.min(100, confidence)))
                    .build());
                userIds.add(uid);
            });
        }

        if (accounts.isEmpty()) {
            throw new BusinessException(ErrorCode.FACE_NOT_RECOGNIZED);
        }

        String matchToken = UUID.randomUUID().toString().replace("-", "");
        authRedisStore.saveFaceMatchToken(matchToken, userIds, MATCH_TOKEN_TTL);
        return FaceMatchResponse.builder()
            .accounts(accounts)
            .matchToken(matchToken)
            .build();
    }

    @Override
    @Transactional
    public UserTokenResponse selectAccount(String matchToken, String userId, String deviceId, String userAgent, String ip) {
        List<String> userIds = authRedisStore.consumeFaceMatchToken(matchToken);
        if (!userIds.contains(userId)) {
            throw new BusinessException(ErrorCode.FACE_NOT_RECOGNIZED);
        }

        User user = userRepository.findByUserId(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found."));
        if (!canLogin(user)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "This account cannot log in.");
        }
        updateLastLoginAt(userId);
        return issueTokens(user, deviceId, userAgent, ip);
    }

    @Override
    @Transactional
    public UserTokenResponse loginByFace(String descriptorJson, String deviceId, String userAgent, String ip) {
        validate128d(descriptorJson);
        List<FaceDescriptor> all = faceDescriptorRepository.findAll();
        if (all.isEmpty()) {
            throw new BusinessException(ErrorCode.FACE_NOT_REGISTERED);
        }

        double[] incoming = toDoubleArray(descriptorJson);
        List<FaceDistance> sorted = all.stream()
            .filter(fd -> !fd.isLocked())
            .map(fd -> new FaceDistance(fd, minDistance(incoming, fromJsonArray(fd.getDescriptor()))))
            .sorted(Comparator.comparingDouble(FaceDistance::distance))
            .toList();

        if (sorted.isEmpty()) {
            throw new BusinessException(ErrorCode.FACE_ACCOUNT_LOCKED);
        }

        FaceDistance best = sorted.get(0);
        if (best.distance() >= THRESHOLD) {
            faceFailureService.recordFailure(best.descriptor().getFaceId());
            throw new BusinessException(ErrorCode.FACE_NOT_RECOGNIZED);
        }
        if (sorted.size() > 1) {
            double margin = sorted.get(1).distance() - best.distance();
            if (margin < 0.05 && best.distance() > 0.45) {
                throw new BusinessException(ErrorCode.FACE_NOT_RECOGNIZED);
            }
        }

        best.descriptor().resetFailure();
        faceDescriptorRepository.save(best.descriptor());

        User user = userRepository.findByUserId(best.descriptor().getUserId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found."));
        if (!canLogin(user)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "This account cannot log in.");
        }
        updateLastLoginAt(user.getUserId());
        return issueTokens(user, deviceId, userAgent, ip);
    }

    @Override
    @Transactional
    public void deleteDescriptor(String userId) {
        faceDescriptorRepository.findByUserId(userId).ifPresent(faceDescriptorRepository::delete);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateLastLoginAt(String userId) {
        userRepository.findByUserId(userId).ifPresent(user -> user.setLastLoginAt(OffsetDateTime.now()));
    }

    private UserTokenResponse issueTokens(User user, String deviceId, String userAgent, String ip) {
        String resolvedDeviceId = deviceId != null && !deviceId.isBlank() ? deviceId.trim() : "face_" + user.getUserId();
        String accessToken = jwtProvider.generateAccessToken(user.getUserId(), user.getUserRole());
        String refreshToken = jwtProvider.generateRefreshToken(user.getUserId());
        authRedisStore.storeRefreshToken(
            jwtProvider.resolveJti(refreshToken),
            user.getUserId(),
            resolvedDeviceId,
            Duration.ofMillis(jwtProvider.getRefreshExpiryMs())
        );
        return new UserTokenResponse(accessToken, refreshToken, resolvedDeviceId, isAdditionalInfoRequired(user));
    }

    private double minDistance(double[] incoming, List<String> encryptedVectors) {
        return encryptedVectors.stream()
            .mapToDouble(v -> euclidean(incoming, toDoubleArray(aesDecrypt(v))))
            .min()
            .orElse(Double.MAX_VALUE);
    }

    private boolean canLogin(User user) {
        return user != null
            && !"Y".equalsIgnoreCase(user.getDeleteYn())
            && !"dormant".equalsIgnoreCase(user.getUserStatus())
            && !"withdrawn".equalsIgnoreCase(user.getUserStatus())
            && !"locked".equalsIgnoreCase(user.getUserStatus());
    }

    private boolean isAdditionalInfoRequired(User user) {
        return user == null
            || user.getUserNickname() == null || user.getUserNickname().isBlank()
            || user.getUserTel() == null || user.getUserTel().isBlank()
            || user.getUserBirth() == null;
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        String[] parts = email.split("@", 2);
        String local = parts[0];
        String domain = parts[1];
        String maskedLocal = local.length() <= 2 ? local : local.substring(0, 2) + "*".repeat(local.length() - 2);
        int dotIndex = domain.lastIndexOf('.');
        String domainMain = dotIndex > 0 ? domain.substring(0, dotIndex) : domain;
        String suffix = dotIndex > 0 ? domain.substring(dotIndex) : "";
        String maskedDomain = domainMain.length() <= 3 ? domainMain : domainMain.substring(0, 3) + "*".repeat(domainMain.length() - 3);
        return maskedLocal + "@" + maskedDomain + suffix;
    }

    private String resolveDisplayName(User user) {
        if (user.getUserNickname() != null && !user.getUserNickname().isBlank()) {
            return user.getUserNickname();
        }
        String name = user.getUserName();
        if (name == null || name.isBlank()) {
            return "user";
        }
        return name.length() <= 2 ? name : name.substring(0, 2) + "*";
    }

    @SuppressWarnings("unchecked")
    private List<String> fromJsonArray(String json) {
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (parsed instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String) {
                return new ArrayList<>((List<String>) list);
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>(List.of(json));
    }

    private String toJsonArray(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize face descriptors.", e);
        }
    }

    private void validate128d(String json) {
        if (toDoubleArray(json).length != 128) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Face descriptor must have 128 dimensions.");
        }
    }

    private double[] toDoubleArray(String json) {
        try {
            List<Double> list = objectMapper.readValue(json, new TypeReference<List<Double>>() {});
            if (list == null || list.size() != 128 || list.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Face descriptor must contain exactly 128 finite numbers.");
            }
            return list.stream().mapToDouble(Double::doubleValue).toArray();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Face descriptor payload is invalid.");
        }
    }

    private double euclidean(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.pow(a[i] - b[i], 2);
        }
        return Math.sqrt(sum);
    }

    private String aesEncrypt(String plain) {
        try {
            byte[] keyBytes = Arrays.copyOf(aesKeyRaw.getBytes(StandardCharsets.UTF_8), 32);
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[16 + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, 16);
            System.arraycopy(encrypted, 0, combined, 16, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Face descriptor encryption failed.", e);
        }
    }

    private String aesDecrypt(String base64) {
        try {
            byte[] keyBytes = Arrays.copyOf(aesKeyRaw.getBytes(StandardCharsets.UTF_8), 32);
            byte[] combined = Base64.getDecoder().decode(base64);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(keyBytes, "AES"),
                new IvParameterSpec(Arrays.copyOfRange(combined, 0, 16))
            );
            return new String(cipher.doFinal(Arrays.copyOfRange(combined, 16, combined.length)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Face descriptor decryption failed.", e);
        }
    }

    @SuppressWarnings("unused")
    private String sha256(String raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record FaceDistance(FaceDescriptor descriptor, double distance) {
    }
}
