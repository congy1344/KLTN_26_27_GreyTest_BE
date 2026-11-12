# Relevant policy: pagination
- Do not mock static helpers, mapping helpers, `Page`, or `Pageable`. Execute static helpers with real fixtures and use `new PageImpl<>(...)` with `PageRequest.of(...)` for Spring Data pagination.
