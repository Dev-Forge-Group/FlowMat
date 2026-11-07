package org.myweb.flowmat.domain.inventory.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.request.FefoIssueRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.FefoIssueResponse;
import org.myweb.flowmat.domain.inventory.application.FefoIssueService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Issues an item's stock from its LOTs, first-expiring first (docs/domain/lot-expiry.md "재고 출고 나눠 하기"). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/inventories")
public class FefoIssueController {

    private final FefoIssueService fefoIssueService;

    @PostMapping("/issue-fefo")
    public ApiResponse<FefoIssueResponse> issue(@Valid @RequestBody FefoIssueRequest request) {
        return ApiResponse.ok(fefoIssueService.issue(request));
    }
}
