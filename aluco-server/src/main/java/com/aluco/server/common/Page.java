package com.aluco.server.common;

import java.util.List;

/** Unified paged response shape (spec 5.5): { list, total, page, size }. */
public record Page<T>(List<T> list, long total, int page, int size) {

    public static <T> Page<T> of(List<T> list, long total, int page, int size) {
        return new Page<>(list, total, page, size);
    }
}
