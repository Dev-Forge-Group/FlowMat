package org.myweb.flowmat.domain.catalog.application.publicapi;

import java.util.List;
import java.util.Map;
import org.myweb.flowmat.global.importing.ImportOutcome;

/**
 * Items from outside FlowMat by exactly the rules of the spreadsheet import (docs/domain/item-import.md), for integration
 * jobs (docs/architecture/adr/ADR-038-integration-jobs.md). Each row names the import's fields as the JSON API does
 * (itemCode, itemName, unitCode, unitCost, ...); a field it does not know is refused. The import checks that the current
 * user may write to the project.
 */
public interface ItemImportCommand {

    /** The field names a row may use. */
    List<String> fields();

    ImportOutcome importItems(String projectId, List<Map<String, String>> rows, boolean dryRun, List<Integer> skipRows);
}
