package org.myweb.flowmat.domain.payment.application;

import java.util.List;
import java.util.UUID;
import org.myweb.flowmat.domain.payment.api.dto.request.PlanCreateRequest;
import org.myweb.flowmat.domain.payment.api.dto.request.PlanPricingCreateRequest;
import org.myweb.flowmat.domain.payment.api.dto.response.PlanPricingResponse;
import org.myweb.flowmat.domain.payment.api.dto.response.PlanResponse;

public interface PlanService {

    List<PlanResponse> getActivePlans();

    PlanResponse getPlan(UUID planId);

    List<PlanPricingResponse> getActivePricing(UUID planId);
}
