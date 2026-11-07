package org.myweb.flowmat.domain.production.api.admin;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.application.WorkOrderService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/work-orders")
public class AdminWorkOrderController {

    private final WorkOrderService workOrderService;
}
