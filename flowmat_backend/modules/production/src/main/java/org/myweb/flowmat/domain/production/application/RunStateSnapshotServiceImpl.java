package org.myweb.flowmat.domain.production.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.RunStateSnapshotCreateRequest;
import org.myweb.flowmat.domain.production.api.dto.response.RunStateSnapshotResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.RunStateSnapshot;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.RunStateSnapshotRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RunStateSnapshotServiceImpl implements RunStateSnapshotService {

    private static final String NOT_DELETED = "N";
    private static final JsonFactory SNAPSHOT_JSON = new JsonFactory();
    private static final int JSONB_INTEGER_DIGITS = 131072;
    private static final int JSONB_FRACTIONAL_DIGITS = 16383;

    private final RunStateSnapshotRepository runStateSnapshotRepository;
    private final ProductionRunRepository productionRunRepository;
    private final ProjectAccessService projectAccessService;
    private final IdGenerator idGenerator;

    @Override
    public List<RunStateSnapshotResponse> listSnapshots(String productionRunId) {
        ProductionRun run = findActiveRun(productionRunId);
        projectAccessService.requireProjectReadAccess(run.getProjectId());
        return runStateSnapshotRepository.findAllByProductionRunIdOrderByCreatedAtDesc(productionRunId).stream()
            .map(RunStateSnapshotServiceImpl::toResponse)
            .toList();
    }

    @Override
    @Transactional
    public RunStateSnapshotResponse createSnapshot(RunStateSnapshotCreateRequest request) {
        ProductionRun run = findActiveRun(request.productionRunId());
        projectAccessService.requireProjectWriteAccess(run.getProjectId());

        requireStorableText(request.snapshotName(), "snapshotName");
        requireStorableText(request.snapshotType(), "snapshotType");
        requireStorableText(request.note(), "note");
        String name = checkedText(trimToNull(request.snapshotName()), "snapshotName", 100);
        String type = checkedText(defaultIfBlank(request.snapshotType(), "manual"), "snapshotType", 30);
        String note = checkedText(trimToNull(request.note()), "note", 0);
        String data = checkedSnapshotData(request.snapshotData());
        RunStateSnapshot snapshot = new RunStateSnapshot();
        snapshot.setRunStateSnapshotId(idGenerator.generate());
        snapshot.setProductionRunId(run.getProductionRunId());
        snapshot.setSnapshotName(name);
        snapshot.setSnapshotType(type);
        snapshot.setSnapshotData(data);
        snapshot.setNote(note);
        snapshot.setCreatedBy(projectAccessService.requireCurrentUserId());
        return toResponse(runStateSnapshotRepository.save(snapshot));
    }

    @Override
    public RunStateSnapshotResponse getSnapshot(String runStateSnapshotId) {
        RunStateSnapshot snapshot = runStateSnapshotRepository.findByRunStateSnapshotId(runStateSnapshotId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectReadAccess(findActiveRun(snapshot.getProductionRunId()).getProjectId());
        return toResponse(snapshot);
    }

    private ProductionRun findActiveRun(String productionRunId) {
        return productionRunRepository.findByProductionRunIdAndDeletedYn(productionRunId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static RunStateSnapshotResponse toResponse(RunStateSnapshot snapshot) {
        return new RunStateSnapshotResponse(
            snapshot.getRunStateSnapshotId(),
            snapshot.getProductionRunId(),
            snapshot.getSnapshotName(),
            snapshot.getSnapshotType(),
            snapshot.getSnapshotData(),
            snapshot.getNote(),
            snapshot.getCreatedBy(),
            snapshot.getCreatedAt()
        );
    }

    private static String trimToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static String checkedText(String value, String field, int limit) {
        if (value == null) {
            return null;
        }
        requireStorableText(value, field);
        if (limit > 0 && value.codePointCount(0, value.length()) > limit) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " must be at most " + limit + " characters.");
        }
        return value;
    }

    private static String checkedSnapshotData(String value) {
        if (value == null || value.isBlank()) {
            throw invalidSnapshotData();
        }
        // Check every token, including overwritten duplicate keys, without rewriting the caller's numbers.
        try (JsonParser parser = SNAPSHOT_JSON.createParser(value)) {
            int depth = 0;
            boolean complete = false;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (complete) {
                    throw invalidSnapshotData();
                }
                if (token == JsonToken.FIELD_NAME || token == JsonToken.VALUE_STRING) {
                    requireStorableText(parser.getText(), "snapshotData");
                } else if (token.isNumeric()) {
                    BigDecimal number = parser.getDecimalValue();
                    if ((number.signum() != 0 && (long) number.precision() - number.scale() > JSONB_INTEGER_DIGITS)
                        || number.scale() > JSONB_FRACTIONAL_DIGITS) {
                        throw new BusinessException(ErrorCode.BAD_REQUEST,
                            "snapshotData contains a number outside PostgreSQL jsonb's range.");
                    }
                }
                if (token.isStructStart()) {
                    depth++;
                } else if (token.isStructEnd()) {
                    complete = --depth == 0;
                } else if (depth == 0 && token.isScalarValue()) {
                    complete = true;
                }
            }
            if (!complete) {
                throw invalidSnapshotData();
            }
        } catch (IOException | NumberFormatException exception) {
            throw invalidSnapshotData();
        }
        return value.trim();
    }

    private static void requireStorableText(String value, String field) {
        if (value == null) return;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == 0 || Character.isLowSurrogate(character)
                || (Character.isHighSurrogate(character)
                    && (++index == value.length() || !Character.isLowSurrogate(value.charAt(index))))) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    field + " contains a character PostgreSQL cannot store.");
            }
        }
    }

    private static BusinessException invalidSnapshotData() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "snapshotData must contain one valid JSON value.");
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return value != null && !value.isBlank() ? value.trim().toLowerCase(Locale.ROOT) : defaultValue;
    }
}
