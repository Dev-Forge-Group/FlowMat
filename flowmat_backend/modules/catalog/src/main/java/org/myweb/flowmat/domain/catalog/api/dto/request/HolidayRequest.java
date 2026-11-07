package org.myweb.flowmat.domain.catalog.api.dto.request;

/**
 * A project holiday (docs/domain/equipment-schedule.md "휴일").
 *
 * @param date an ISO date, e.g. 2030-01-01
 * @param name optional, at most 100 characters
 */
public record HolidayRequest(String projectId, String date, String name) {
}
