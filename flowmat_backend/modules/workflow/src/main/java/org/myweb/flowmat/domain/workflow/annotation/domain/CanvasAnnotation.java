package org.myweb.flowmat.domain.workflow.annotation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;

@Getter
@Setter
@Entity
@Table(name = "canvas_annotation")
public class CanvasAnnotation extends CreatedUpdatedAuditEntity {

    @Id
    @Column(name = "annotation_id")
    private String annotationId;

    private String workflowId;
    private String projectId;
    private String annotationType;
    private String shapeKind;

    @Column(name = "pos_x")
    private Double posX;

    @Column(name = "pos_y")
    private Double posY;

    private Double width;
    private Double height;
    private Double rotation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "points_json", columnDefinition = "jsonb")
    private String pointsJson;

    private String textContent;
    private String imageAssetUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "style_json", columnDefinition = "jsonb")
    private String styleJson;

    @Column(name = "z_index")
    private String zIndex;

    private String groupId;
    @JdbcTypeCode(SqlTypes.CHAR)
    private String lockedYn = "N";

    @Column(name = "version")
    private int version = 1;

    @Column(name = "version_nonce")
    private long versionNonce = 1L;
}
