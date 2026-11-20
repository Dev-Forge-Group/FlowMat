package org.myweb.flowmat.domain.integration.api.dto.request;

/**
 * Switching a job's schedule on or off. {@code cron} is five fields in the project's time zone; it is required to
 * switch on and kept when switching off without one. {@code version} is the job's version as read.
 */
public record IntegrationScheduleRequest(String cron, Boolean enabled, Integer version) {
}
