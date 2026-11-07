package org.myweb.flowmat.domain.inventory.application;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.response.InventoryTransactionPageResponse;
import org.myweb.flowmat.domain.inventory.domain.entity.InventoryTransaction;
import org.myweb.flowmat.domain.inventory.repository.InventoryTransactionRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The project's movement ledger, filtered and keyset-paged on the server (docs/domain/stock-ledger.md): newest first by
 * (created_at, id), and the cursor is the last row's pair, so movements recorded between pages never repeat or shift
 * rows.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryTransactionSearchService {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final ProjectAccessService projectAccessService;

    /**
     * @param from inclusive; {@code to} exclusive. The client turns its local days into these instants.
     * @param text matched, ignoring case, against the note, the reference and who recorded the movement.
     */
    public InventoryTransactionPageResponse search(
        String projectId,
        String type,
        String itemId,
        OffsetDateTime from,
        OffsetDateTime to,
        String text,
        Integer limit,
        String cursor
    ) {
        String project = trimToNull(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId is required.");
        }
        projectAccessService.requireProjectReadAccess(project);
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "limit must be between 1 and " + MAX_LIMIT + ".");
        }
        Cursor after = Cursor.decode(cursor);
        String typeFilter = trimToNull(type);
        String itemFilter = trimToNull(itemId);
        String needle = trimToNull(text);

        Specification<InventoryTransaction> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("projectId"), project));
            if (typeFilter != null) {
                where.add(cb.equal(root.get("transactionType"), typeFilter));
            }
            if (itemFilter != null) {
                where.add(cb.equal(root.get("itemId"), itemFilter));
            }
            Expression<OffsetDateTime> createdAt = root.get("createdAt");
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(createdAt, from));
            }
            if (to != null) {
                where.add(cb.lessThan(createdAt, to));
            }
            if (needle != null) {
                String pattern = "%" + needle.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                where.add(cb.or(
                    cb.like(cb.lower(cb.coalesce(root.get("note"), "")), pattern, '\\'),
                    cb.like(cb.lower(cb.coalesce(root.get("referenceType"), "")), pattern, '\\'),
                    cb.like(cb.lower(cb.coalesce(root.get("referenceId"), "")), pattern, '\\'),
                    cb.like(cb.lower(cb.coalesce(root.get("createdBy"), "")), pattern, '\\')
                ));
            }
            if (after != null) {
                where.add(cb.or(
                    cb.lessThan(createdAt, after.createdAt()),
                    cb.and(cb.equal(createdAt, after.createdAt()), cb.lessThan(root.get("inventoryTransactionId"), after.id()))
                ));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        Sort newestFirst = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("inventoryTransactionId"));
        List<InventoryTransaction> rows = inventoryTransactionRepository.findBy(spec, q -> q.sortBy(newestFirst).limit(size + 1).all());

        boolean more = rows.size() > size;
        List<InventoryTransaction> page = more ? rows.subList(0, size) : rows;
        InventoryTransaction last = page.isEmpty() ? null : page.get(page.size() - 1);
        String next = more && last != null && last.getCreatedAt() != null
            ? new Cursor(last.getCreatedAt(), last.getInventoryTransactionId()).encode()
            : null;
        return new InventoryTransactionPageResponse(page.stream().map(InventoryTransactionServiceImpl::toResponse).toList(), next);
    }

    /** Where the previous page ended: base64url of "createdAt|id". Opaque to clients. */
    record Cursor(OffsetDateTime createdAt, String id) {

        String encode() {
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(value.trim()), StandardCharsets.UTF_8);
                int bar = raw.indexOf('|');
                if (bar < 1 || bar == raw.length() - 1) {
                    throw new IllegalArgumentException();
                }
                return new Cursor(OffsetDateTime.parse(raw.substring(0, bar)), raw.substring(bar + 1));
            } catch (IllegalArgumentException | DateTimeParseException e) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "The cursor is not valid; start from the first page.");
            }
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
