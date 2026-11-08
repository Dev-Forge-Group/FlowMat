package org.myweb.flowmat.domain.production.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "work_order_item")
public class WorkOrderItem {

    @Id
    private String workOrderItemId;

    private String workOrderId;
    private String itemId;
    private String processId;
    private String bomLineId;
    private String direction;
    private BigDecimal requiredQty;
    private String unit;
}
