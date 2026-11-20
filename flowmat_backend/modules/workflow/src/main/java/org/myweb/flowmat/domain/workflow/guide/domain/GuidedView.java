package org.myweb.flowmat.domain.workflow.guide.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.myweb.flowmat.global.common.BaseTimeEntity;

/** One chapter of a workflow's guided views (ADR-037): a name, a note and the processes it shows, in reading order. */
@Getter
@Setter
@Entity
@Table(name = "workflow_guided_view")
public class GuidedView extends BaseTimeEntity {

    @Id
    @Column(name = "guided_view_id")
    private String guidedViewId;

    private String workflowId;
    private String projectId;
    private int sortOrder;
    private String label;
    private String note;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "focus_process_ids", columnDefinition = "jsonb")
    private List<String> focusProcessIds = new ArrayList<>();

    private int version = 1;
    private String createdBy;
    private String updatedBy;
}
