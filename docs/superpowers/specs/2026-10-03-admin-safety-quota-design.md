# Admin Safety and Quota Clarity Design

**Status:** Approved for implementation on 2026-10-03

## Goal

Make the existing admin user-management flow safe for role/status changes and make monthly LLM quota unambiguous. This is deliberately a small extension of the existing AdminService, activity log, quota table, and React admin page.

## Scope

- Protect role changes and account locks in both backend and UI.
- Keep a usable audit trail in the existing `user_activity_log` table.
- Treat an unlimited monthly quota as `NULL`, not `999999`.
- Show the monthly reset date, total generation requests, and latest activity in the user list.
- Preserve the current search, role/status filters, sorting, pagination, and per-user quota editor.

## Non-goals

- No session table, token blacklist, quota packages, bulk actions, daily quota, concurrent-job limits, billing, LLM cost dashboard, prompt versions, generation-job persistence, or source-retention workflow.
- No change to real deployment accounts. The reported test-email accounts are not seeded by the checked-in application/migrations; their cleanup is an operational deployment task.

## Backend design

### Safe administrative mutations

`AdminService` remains the sole owner of the policy; controls in React are only a convenience.

- A current admin cannot lock their own account or change their own role to `USER`.
- Locking an enabled admin or changing an enabled admin to `USER` is rejected when it would remove the last enabled `ADMIN` account.
- A lock request requires a nonblank reason of at most 500 characters. Unlocking does not require a reason.
- The existing stateless token needs no blacklist: `AuthService.currentUser()` reloads the user and rejects a disabled account on every authenticated request. Thus all existing tokens lose access at their next request after a lock.

The existing `ADMIN_STATUS_CHANGE` and `ADMIN_ROLE_CHANGE` activity actions are retained. Their JSON metadata contains the actor through `user_id` and, for the target, `targetUserId`, previous value, new value, and the lock reason when applicable. This gives the activity screen a complete audit statement without a second audit-table abstraction.

### Unlimited quota

Flyway migration `V24` will make `usage_quota.quota_limit` nullable and convert legacy `999999` values to `NULL`. `0` retains its existing meaning: no LLM call is available for the current month.

`UsageQuotaService` allows a call when `quotaLimit` is `NULL`; finite quotas keep the existing limit check and lazy monthly reset. Admin quota update accepts an integer greater than or equal to zero or `null`.

`QuotaDto` exposes nullable `limit` and `remaining`, an `exceeded` flag that is always false for an unlimited quota, and `resetDate = periodStart.plusMonths(1)`. No `isUnlimited` field is added because `limit == null` is the source of truth.

### User-list metrics

`totalActivities` is replaced by `totalGenerationRequests`: it counts only the existing generation actions (`GENERATE_BUSINESS_RULE`, `GENERATE_TEST_PLAN`, `GENERATE_TEST_CASE`, `GENERATE_UNIT_TEST`, `COVERAGE_REFINEMENT`). A repository lookup also supplies `lastActivityAt` for each user summary. The detail page follows the renamed metric.

## Frontend design

- `AdminUsersPage` obtains the authenticated user and disables that user's role selector and lock button. Server-side policy still protects direct API requests.
- The current role confirmation remains. The status confirmation becomes a small form with a required reason field only when locking.
- The quota editor has a `Không giới hạn` checkbox. When checked, it sends `quotaLimit: null`, disables the numeric field, and the table/detail view show `Không giới hạn` without a progress bar.
- Finite quotas show usage, remaining calls, and `Reset ngày …`; zero quota remains visibly exhausted.
- The table header becomes `Tổng lượt sinh test`; the final column shows `Hoạt động gần nhất` as a timestamp or `Chưa có`.
- Badges and row actions use `whitespace-nowrap`, preventing the two-line layout from the current page.
- The activity formatter renders audit metadata as a readable before/after change with lock reason.

## Errors and validation

The backend returns its existing bad-request response for self-mutation, final-admin protection, invalid quota, and missing lock reason. The current page's `getErrorMessage` remains responsible for showing it. Mutations invalidate the existing admin query key, so the list, details, and activity log refresh after a successful change.

## Verification

Backend tests are written before production changes and cover:

1. self-lock and self-demotion rejection;
2. final enabled admin protection;
3. audit metadata for role and status changes, including a lock reason;
4. nullable quota permitting real-provider calls and never reporting an exceeded/unbounded bar;
5. reset date and generation-only/last-activity values in an admin user summary.

Frontend tests cover disabling self-actions, required lock reason, unlimited quota rendering/submission, and the renamed metric/reset label. The full backend Maven test suite and frontend test suite run after the focused tests.
