package org.myweb.flowmat.global.history;

import java.util.List;

/** Newest first; pass nextCursor back unchanged, or stop when it is null. No count query is needed. */
public record HistoryPageResponse<T>(List<T> entries, String nextCursor) {
    public HistoryPageResponse {
        entries = List.copyOf(entries);
    }
}
