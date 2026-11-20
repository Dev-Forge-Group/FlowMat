package org.myweb.flowmat.domain.inventory.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.request.StockImportRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.StockImportResponse;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockImportCommand;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.importing.ImportOutcome;
import org.springframework.stereotype.Component;

/** The spreadsheet stock import, reached with rows of named fields (StockImportCommand). */
@Component
@RequiredArgsConstructor
class StockImportCommandAdapter implements StockImportCommand {

    private static final ObjectMapper ROWS = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final List<String> FIELDS = Arrays.stream(StockImportRequest.Row.class.getRecordComponents())
        .map(component -> component.getName()).toList();

    private final StockImportService service;

    @Override
    public List<String> fields() {
        return FIELDS;
    }

    @Override
    public ImportOutcome importStock(String projectId, List<Map<String, String>> rows, String note, boolean dryRun,
                                     List<Integer> skipRows) {
        List<StockImportRequest.Row> lines = rows.stream().map(StockImportCommandAdapter::line).toList();
        StockImportResponse response = service.importStock(new StockImportRequest(projectId, dryRun, note, lines, skipRows));
        return new ImportOutcome(response.dryRun(), response.applied(), response.errors(), response.skipped(),
            Map.of("created", response.created(), "received", response.received(), "newLots", response.newLots()),
            response.rows().stream().map(row -> new ImportOutcome.Row(row.row(), row.itemCode(), row.action(), row.message()))
                .toList());
    }

    private static StockImportRequest.Row line(Map<String, String> row) {
        try {
            return ROWS.convertValue(row, StockImportRequest.Row.class);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "A stock row names a field the stock import does not have.");
        }
    }
}
