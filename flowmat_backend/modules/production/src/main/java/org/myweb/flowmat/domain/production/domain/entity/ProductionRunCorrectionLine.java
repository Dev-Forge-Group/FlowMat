package org.myweb.flowmat.domain.production.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * One change in a run correction: void a recording, add a recording, set the run's output quantity, or cancel or add a
 * setup, running time or worked time.
 */
@Getter
@Setter
@Entity
@Table(name = "production_run_correction_line")
public class ProductionRunCorrectionLine {

    public static final String VOID_ITEM = "void_item";
    public static final String ADD_ITEM = "add_item";
    public static final String SET_OUTPUT_QTY = "set_output_qty";
    /** Setup corrections (docs/domain/equipment-setup-cost.md AS7). */
    public static final String CANCEL_SETUP = "cancel_setup";
    public static final String ADD_SETUP = "add_setup";
    /** Running-time corrections (docs/domain/equipment-setup-cost.md ER10). */
    public static final String CANCEL_RUNNING_TIME = "cancel_running_time";
    public static final String ADD_RUNNING_TIME = "add_running_time";
    /** Worked-time corrections (docs/domain/worker-calendar.md LB9). */
    public static final String CANCEL_LABOR = "cancel_labor";
    public static final String ADD_LABOR = "add_labor";

    @Id
    private String productionRunCorrectionLineId;

    private String productionRunCorrectionId;
    private Integer lineNo;
    private String lineKind;

    /** void_item: the recording to void. */
    private String targetRunItemId;

    /** add_item: what to record. */
    private String direction;
    private String itemId;
    private String inventoryId;
    private BigDecimal qty;
    @Column(length = 20)
    private String unit;

    /** set_output_qty: the run's output quantity when the correction was requested, and the new value. */
    private BigDecimal beforeQty;
    private BigDecimal afterQty;

    /** add_item: the recording created when the correction was applied. */
    private String createdRunItemId;

    /** cancel_setup: the setup it cancels. */
    private String targetRunSetupId;
    /** add_setup: the equipment and whole minutes of the setup it adds, and the setup it made once applied. */
    private String equipmentId;
    private Integer setupMinutes;
    private String createdRunSetupId;

    /** cancel_running_time: the running time it cancels. */
    private String targetRunEquipmentTimeId;
    /** add_running_time: whole minutes on {@link #equipmentId}, and the running time it made once applied. */
    private Integer runningMinutes;
    private String createdRunEquipmentTimeId;

    /** cancel_labor: the worked time it cancels. */
    private String targetRunLaborId;
    /** add_labor: who worked and their whole minutes, and the worked time it made once applied. */
    private String laborUserId;
    private Integer workedMinutes;
    private String createdRunLaborId;
    /** add_labor: the process step it was worked on; null for the whole run (LB17, V100). */
    private String laborStepId;
}
