package org.myweb.flowmat.global.importing;

import java.util.List;
import java.util.Map;

/**
 * What an import of rows did or would do, in the same terms for every kind of import, for callers in other modules
 * (integration jobs, docs/architecture/adr/ADR-038-integration-jobs.md).
 *
 * @param applied true when the rows were saved: not a dry run and no row that was not left out had a problem
 * @param counts the import's own counts, such as created, updated, unchanged or received
 * @param rows one entry per row in the order given
 */
public record ImportOutcome(boolean dryRun, boolean applied, int errors, int skipped, Map<String, Integer> counts,
                            List<Row> rows) {

    public ImportOutcome {
        counts = Map.copyOf(counts);
        rows = List.copyOf(rows);
    }

    /**
     * @param row the row's number among the rows, from 1
     * @param key what names the row, such as an item code
     * @param action what happens to it, such as create, update, receive, error or skipped
     * @param message why, for an error; what changes, where the import says
     */
    public record Row(int row, String key, String action, String message) {
    }
}
