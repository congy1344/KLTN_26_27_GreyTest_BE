# Relevant policy: datetime
- `atStartOfDay()` belongs to `LocalDate`, never `LocalDateTime`; `LocalTime.atDate(...)` already returns `LocalDateTime`. For a timestamp set to now, assert it is non-null and before `LocalDateTime.now().plusMinutes(1)` when a time bound is useful.
- For `cancelledAt` and status changes, assert the approved expected value, including null when required by the approved Test Case, even if current production mutates it differently.
