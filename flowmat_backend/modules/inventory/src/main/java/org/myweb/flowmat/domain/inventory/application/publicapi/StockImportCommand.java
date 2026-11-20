package org.myweb.flowmat.domain.inventory.application.publicapi;

import java.util.List;
import java.util.Map;
import org.myweb.flowmat.global.importing.ImportOutcome;

/**
 * Stock received from outside FlowMat by exactly the rules of the spreadsheet import (docs/domain/stock-import.md), for
 * integration jobs (docs/architecture/adr/ADR-038-integration-jobs.md). Each row names the import's fields as the JSON
 * API does (itemCode, location, lotNo, quantity, expiryDate, packs); a field it does not know is refused. The import
 * checks that the current user may write to the project.
 */
public interface StockImportCommand {

    /** The field names a row may use. */
    List<String> fields();

    ImportOutcome importStock(String projectId, List<Map<String, String>> rows, String note, boolean dryRun, List<Integer> skipRows);
}
