package org.myweb.flowmat.global.history;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.util.StorableText;
import org.springframework.data.jpa.domain.Specification;

/** Shared filters for append-only histories with savedAt, historyId, action and savedBy columns. */
public record HistorySearchFilter(String action, String savedBy, OffsetDateTime from, OffsetDateTime to, int limit, String cursor) {
    public static HistorySearchFilter parse(String action, String savedBy, String from, String to, String limit, String cursor) {
        if (action != null && !action.equals("saved") && !action.equals("removed")) {
            throw bad("action must be saved or removed.");
        }
        identifier(savedBy, "savedBy");
        identifier(cursor, "cursor");
        OffsetDateTime lower = instant(from, "from");
        OffsetDateTime upper = instant(to, "to");
        if (lower != null && upper != null && !upper.isAfter(lower)) {
            throw bad("to must be after from.");
        }
        int size;
        try {
            size = limit == null ? 100 : Integer.parseInt(limit);
        } catch (NumberFormatException exception) {
            throw bad("limit must be an integer between 1 and 100.");
        }
        if (size < 1 || size > 100) throw bad("limit must be an integer between 1 and 100.");
        return new HistorySearchFilter(action, savedBy, lower, upper, size, cursor);
    }

    /** Scope predicates are supplied by the owning domain; the cursor's row must already be verified in that scope. */
    public <T> Specification<T> specification(OffsetDateTime beforeAt, String beforeId) {
        return (root, query, cb) -> {
            List<Predicate> filters = new ArrayList<>();
            Expression<OffsetDateTime> at = root.get("savedAt");
            if (action != null) filters.add(cb.equal(root.get("action"), action));
            if (savedBy != null) filters.add(cb.equal(root.get("savedBy"), savedBy));
            if (from != null) filters.add(cb.greaterThanOrEqualTo(at, from));
            if (to != null) filters.add(cb.lessThan(at, to));
            if (beforeAt != null) filters.add(cb.or(cb.lessThan(at, beforeAt),
                cb.and(cb.equal(at, beforeAt), cb.lessThan(root.get("historyId"), beforeId))));
            return cb.and(filters.toArray(Predicate[]::new));
        };
    }

    private static void identifier(String value, String field) {
        if (value == null) return;
        if (value.isBlank() || value.length() > 50) throw bad(field + " must contain between 1 and 50 characters.");
        StorableText.require(value, field);
    }

    private static OffsetDateTime instant(String value, String field) {
        if (value == null) return null;
        try {
            if (value.length() > 64) throw new DateTimeParseException("Too long", "", 0);
            OffsetDateTime parsed = OffsetDateTime.parse(value);
            if (parsed.getYear() < 1 || parsed.getYear() > 9999) {
                throw bad(field + " must use a year between 0001 and 9999.");
            }
            return parsed;
        } catch (DateTimeParseException exception) {
            throw bad(field + " must be an ISO date-time with an offset.");
        }
    }

    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
}
