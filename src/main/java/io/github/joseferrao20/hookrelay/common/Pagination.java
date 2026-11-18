package io.github.joseferrao20.hookrelay.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class Pagination {

  private Pagination() {}

  /** Newest first, with the id as a stable tie-breaker. */
  public static Pageable newestFirst(int page, int size) {
    return PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
  }
}
