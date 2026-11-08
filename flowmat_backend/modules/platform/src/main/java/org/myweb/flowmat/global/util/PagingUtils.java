package org.myweb.flowmat.global.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

public final class PagingUtils {

    private PagingUtils() {
    }

    public static Pageable of(int page, int size) {
        return PageRequest.of(Math.max(page - 1, 0), Math.max(size, 1));
    }
}
