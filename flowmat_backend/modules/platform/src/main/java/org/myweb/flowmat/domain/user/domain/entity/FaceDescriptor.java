package org.myweb.flowmat.domain.user.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.BaseTimeEntity;

@Getter
@Setter
@Entity
@Table(name = "face_descriptor")
public class FaceDescriptor extends BaseTimeEntity {

    public static final int MAX_VECTORS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "face_id")
    private Long faceId;

    @Column(name = "user_id", nullable = false, unique = true, length = 50)
    private String userId;

    @Column(name = "descriptor", nullable = false, columnDefinition = "TEXT")
    private String descriptor;

    @Column(name = "fail_count", nullable = false)
    private int failCount;

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(OffsetDateTime.now());
    }

    public void recordFailure() {
        failCount++;
        if (failCount >= 5) {
            lockedUntil = OffsetDateTime.now().plusMinutes(10);
        }
    }

    public void resetFailure() {
        failCount = 0;
        lockedUntil = null;
    }
}
