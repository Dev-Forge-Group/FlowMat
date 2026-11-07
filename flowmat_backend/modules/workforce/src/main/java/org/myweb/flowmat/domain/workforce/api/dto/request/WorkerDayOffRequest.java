package org.myweb.flowmat.domain.workforce.api.dto.request;

/** Days off from {@code firstDay} to {@code lastDay}, both included, such as 2030-01-09 (WC3). */
public record WorkerDayOffRequest(String firstDay, String lastDay, String reason) {
}
