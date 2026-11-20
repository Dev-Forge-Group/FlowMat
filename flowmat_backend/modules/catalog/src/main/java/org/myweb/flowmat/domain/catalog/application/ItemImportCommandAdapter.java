package org.myweb.flowmat.domain.catalog.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemImportRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.ItemImportResponse;
import org.myweb.flowmat.domain.catalog.application.publicapi.ItemImportCommand;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.importing.ImportOutcome;
import org.springframework.stereotype.Component;

/** The spreadsheet import, reached with rows of named fields (ItemImportCommand). */
@Component
@RequiredArgsConstructor
class ItemImportCommandAdapter implements ItemImportCommand {

    private static final ObjectMapper ROWS = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final List<String> FIELDS = Arrays.stream(ItemImportRequest.Row.class.getRecordComponents())
        .map(component -> component.getName()).toList();

    private final ItemImportService service;

    @Override
    public List<String> fields() {
        return FIELDS;
    }

    @Override
    public ImportOutcome importItems(String projectId, List<Map<String, String>> rows, boolean dryRun, List<Integer> skipRows) {
        List<ItemImportRequest.Row> lines = rows.stream().map(ItemImportCommandAdapter::line).toList();
        ItemImportResponse response = service.importItems(new ItemImportRequest(projectId, dryRun, lines, skipRows));
        return new ImportOutcome(response.dryRun(), response.applied(), response.errors(), response.skipped(),
            Map.of("created", response.created(), "updated", response.updated(), "unchanged", response.unchanged()),
            response.rows().stream().map(row -> new ImportOutcome.Row(row.row(), row.itemCode(), row.action(), row.message()))
                .toList());
    }

    private static ItemImportRequest.Row line(Map<String, String> row) {
        try {
            return ROWS.convertValue(row, ItemImportRequest.Row.class);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "An item row names a field the item import does not have.");
        }
    }
}
