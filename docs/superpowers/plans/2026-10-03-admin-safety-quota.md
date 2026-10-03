# Admin Safety and Quota Clarity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Secure administrator role/account actions and make monthly LLM quota and user-list metrics unambiguous.

**Architecture:** Keep mutation policy in `AdminService`, reuse `user_activity_log` for audit metadata, and represent unlimited quota in the existing nullable column. React prevents accidental self-actions and presents API state; it never substitutes for service-layer authorization.

**Tech Stack:** Spring Boot 3, Java 17, Spring Data JPA, Flyway/PostgreSQL, JUnit 5/Mockito, React 18, TypeScript, TanStack Query, Vitest, React Testing Library.

**Spec:** `docs/superpowers/specs/2026-10-03-admin-safety-quota-design.md`

## Global Constraints

- Reuse `user_activity_log`; do not add audit/session/quota-package tables.
- `usage_quota.quota_limit = NULL` means unlimited; `0` remains finite with no calls available.
- Protect self-actions and the final enabled administrator in `AdminService`, not only in React.
- A lock needs a Vietnamese validation message and a reason no longer than 500 characters.
- Use constructor injection, service Javadoc, DTO validation, and Vietnamese comments only for business rationale.
- Do not stage or revert the pre-existing unrelated working-tree edits.

---

## File structure

| File | Responsibility |
|---|---|
| `src/main/resources/db/migration/V24__allow_unlimited_usage_quota.sql` | Convert legacy unlimited sentinel values and permit a nullable quota limit. |
| `src/main/java/com/greytest/entity/UsageQuota.java` | Map a nullable quota limit. |
| `src/main/java/com/greytest/service/UsageQuotaService.java` | Enforce finite quotas only and accept nullable admin limits. |
| `src/main/java/com/greytest/dto/admin/UpdateQuotaRequest.java` | Validate a non-negative finite quota while accepting `null`. |
| `src/main/java/com/greytest/dto/admin/UpdateUserStatusRequest.java` | Receive the lock reason. |
| `src/main/java/com/greytest/dto/admin/AdminDtos.java` | Expose unlimited quota/reset date and generation/last-activity summary fields. |
| `src/main/java/com/greytest/repository/AuthUserRepository.java` | Count enabled administrators. |
| `src/main/java/com/greytest/repository/UserActivityLogRepository.java` | Query generation-only totals and latest activity. |
| `src/main/java/com/greytest/service/AdminService.java` | Apply mutation policy, audit before/after values, and map the new summary. |
| `src/main/java/com/greytest/controller/AdminController.java` | Pass status reason to the service. |
| `src/test/java/com/greytest/service/UsageQuotaServiceTest.java` | Prove nullable quota behavior. |
| `src/test/java/com/greytest/service/AdminServiceTest.java` | Prove admin safety, audit content, reset date, and user-list metrics. |
| `../KLTN_26_27_GreyTest_FE/src/features/admin/types.ts` | Match nullable quota and changed user-summary JSON. |
| `../KLTN_26_27_GreyTest_FE/src/features/admin/api/admin-api.ts` | Send nullable quota and lock reason. |
| `../KLTN_26_27_GreyTest_FE/src/features/admin/hooks/useAdmin.ts` | Type mutation payloads. |
| `../KLTN_26_27_GreyTest_FE/src/features/admin/pages/AdminUsersPage.tsx` | Confirm a reasoned lock, disable self-actions, and render quota/list state. |
| `../KLTN_26_27_GreyTest_FE/src/features/admin/pages/AdminUserDetailPage.tsx` | Render the renamed metric and unlimited quota safely. |
| `../KLTN_26_27_GreyTest_FE/src/features/admin/utils/activity-presentation.ts` | Render audit before/after metadata. |
| `../KLTN_26_27_GreyTest_FE/src/features/admin/pages/AdminUsersPage.test.tsx` | Cover self-action, lock-reason, and unlimited quota UI behavior. |
| `../KLTN_26_27_GreyTest_FE/src/features/admin/utils/activity-presentation.test.ts` | Cover readable audit metadata. |

## Interface changes

```java
public record UpdateUserStatusRequest(Boolean enabled, String reason) {}
public record UpdateQuotaRequest(Integer quotaLimit) {}
public record QuotaDto(
        Integer limit, int used, Integer remaining,
        LocalDate periodStart, LocalDate resetDate, boolean exceeded) {}
public record UserSummaryDto(
        Long id, String email, String fullName, UserRole role, boolean enabled,
        Instant createdAt, long totalGenerationRequests, Instant lastActivityAt,
        QuotaDto quota) {}
```

```ts
export interface UsageQuota {
  limit: number | null;
  used: number;
  remaining: number | null;
  periodStart: string;
  resetDate: string;
  exceeded: boolean;
}

export interface AdminUser {
  id: number;
  email: string;
  fullName: string;
  role: UserRole;
  enabled: boolean;
  createdAt: string;
  totalGenerationRequests: number;
  lastActivityAt: string | null;
  quota: UsageQuota;
}
```

### Task 1: Represent and enforce unlimited quota

**Files:**
- Create: `src/main/resources/db/migration/V24__allow_unlimited_usage_quota.sql`
- Modify: `src/main/java/com/greytest/entity/UsageQuota.java`
- Modify: `src/main/java/com/greytest/service/UsageQuotaService.java`
- Modify: `src/main/java/com/greytest/dto/admin/UpdateQuotaRequest.java`
- Test: `src/test/java/com/greytest/service/UsageQuotaServiceTest.java`

**Consumes:** Existing integer `usage_quota` records, `UsageQuotaRepository.findByUserIdForUpdate`, and `UsageQuotaExceededException`.

**Produces:** `UsageQuotaService.updateLimit(Long userId, Integer limit)` and `consumeLlmCall` behavior where a null limit cannot exceed.

- [ ] **Step 1: Write the failing quota tests**

Add these tests to `UsageQuotaServiceTest` before changing production code:

```java
@Test
void unlimitedQuotaAllowsCallsForRealProvider() {
    UsageQuota unlimited = new UsageQuota();
    unlimited.setUserId(7L);
    unlimited.setQuotaLimit(null);
    unlimited.setQuotaUsed(100);
    unlimited.setPeriodStart(LocalDate.of(2026, 8, 1));
    AtomicReference<UsageQuota> stored = new AtomicReference<>(unlimited);
    UsageQuotaService service = new UsageQuotaService(repository(stored), null, 2, AUGUST, "real");

    UsageQuota result = service.consumeLlmCall(7L);

    assertThat(result.getQuotaUsed()).isEqualTo(101);
    assertThat(result.getQuotaLimit()).isNull();
}

@Test
void updatesQuotaToUnlimited() {
    UsageQuota finite = new UsageQuota();
    finite.setUserId(7L);
    finite.setQuotaLimit(2);
    finite.setQuotaUsed(1);
    finite.setPeriodStart(LocalDate.of(2026, 8, 1));
    AtomicReference<UsageQuota> stored = new AtomicReference<>(finite);
    UsageQuotaService service = new UsageQuotaService(repository(stored), 2, AUGUST);

    UsageQuota result = service.updateLimit(7L, null);

    assertThat(result.getQuotaLimit()).isNull();
}
```

- [ ] **Step 2: Run the focused test to verify RED**

Run: `mvn test -Dtest=UsageQuotaServiceTest`

Expected: `unlimitedQuotaAllowsCallsForRealProvider` fails with a null-unboxing error from the finite quota comparison; `updatesQuotaToUnlimited` fails because `Math.max` rejects a null limit.

- [ ] **Step 3: Implement the smallest quota change**

Create the migration:

```sql
UPDATE usage_quota SET quota_limit = NULL WHERE quota_limit = 999999;
ALTER TABLE usage_quota ALTER COLUMN quota_limit DROP NOT NULL;
```

Remove `nullable = false` from `UsageQuota.quotaLimit`. Remove `@NotNull` from `UpdateQuotaRequest`; retain `@Min(0)` and `@Max(100000)`, which Bean Validation ignores for `null`.

Change the service signature and finite comparison exactly as follows:

```java
public synchronized UsageQuota updateLimit(Long userId, Integer limit) {
    UsageQuota quota = currentForUpdate(userId);
    if (limit != null && limit < 0) throw new IllegalArgumentException("Quota không được âm");
    quota.setQuotaLimit(limit);
    return repository.save(quota);
}

if (!"mock".equalsIgnoreCase(llmProvider)
        && quota.getQuotaLimit() != null
        && quota.getQuotaUsed() >= quota.getQuotaLimit()) {
    throw new UsageQuotaExceededException(
            "Bạn đã sử dụng hết quota LLM tháng này. Vui lòng liên hệ quản trị viên.");
}
```

- [ ] **Step 4: Run the focused test to verify GREEN**

Run: `mvn test -Dtest=UsageQuotaServiceTest`

Expected: all tests in `UsageQuotaServiceTest` pass.

- [ ] **Step 5: Commit only this task's backend files**

```powershell
git add -- src/main/resources/db/migration/V24__allow_unlimited_usage_quota.sql src/main/java/com/greytest/entity/UsageQuota.java src/main/java/com/greytest/service/UsageQuotaService.java src/main/java/com/greytest/dto/admin/UpdateQuotaRequest.java src/test/java/com/greytest/service/UsageQuotaServiceTest.java
git commit -m "feat: support unlimited LLM quota"
```

### Task 2: Protect admin mutations and expose correct summary data

**Files:**
- Modify: `src/main/java/com/greytest/repository/AuthUserRepository.java`
- Modify: `src/main/java/com/greytest/repository/UserActivityLogRepository.java`
- Modify: `src/main/java/com/greytest/dto/admin/UpdateUserStatusRequest.java`
- Modify: `src/main/java/com/greytest/dto/admin/AdminDtos.java`
- Modify: `src/main/java/com/greytest/service/AdminService.java`
- Modify: `src/main/java/com/greytest/controller/AdminController.java`
- Create: `src/test/java/com/greytest/service/AdminServiceTest.java`

**Consumes:** Task 1 nullable `UsageQuota.quotaLimit`; existing `ADMIN_STATUS_CHANGE`, `ADMIN_ROLE_CHANGE`, and generation action enum values.

**Produces:** `AdminService.updateStatus(AuthUser admin, Long userId, boolean enabled, String reason)`; safe `updateRole`; user summaries with `totalGenerationRequests`, `lastActivityAt`, and `resetDate`.

- [ ] **Step 1: Write the failing service tests**

Create `AdminServiceTest` with mocked repositories/services. Use an enabled admin target with `id = 2L`, an enabled acting admin with `id = 1L`, and this test shape:

```java
private AuthUserRepository users;
private ProjectRepository projects;
private UnitTestRepository unitTests;
private UserActivityLogRepository activities;
private UsageQuotaRepository quotas;
private UsageQuotaService quotaService;
private UserActivityService activityService;
private AdminService service;

@BeforeEach
void setUp() {
    users = mock(AuthUserRepository.class);
    projects = mock(ProjectRepository.class);
    unitTests = mock(UnitTestRepository.class);
    activities = mock(UserActivityLogRepository.class);
    quotas = mock(UsageQuotaRepository.class);
    quotaService = mock(UsageQuotaService.class);
    activityService = mock(UserActivityService.class);
    service = new AdminService(users, projects, unitTests, activities, quotas, quotaService, activityService);
}

private AuthUser user(Long id, UserRole role, boolean enabled) {
    AuthUser user = new AuthUser();
    user.setId(id);
    user.setRole(role);
    user.setEnabled(enabled);
    user.setEmail("user" + id + "@greytest.dev");
    return user;
}

private AuthUser actor(Long id) {
    return user(id, UserRole.ADMIN, true);
}

@Test
void rejectsLockingTheLastEnabledAdmin() {
    when(users.findById(2L)).thenReturn(Optional.of(user(2L, UserRole.ADMIN, true)));
    when(users.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(1L);

    assertThatThrownBy(() -> service.updateStatus(actor(1L), 2L, false, "Vi phạm quy định"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("admin");

    verify(users, never()).save(any());
}

@Test
void recordsRoleBeforeAndAfterValues() {
    AuthUser target = user(2L, UserRole.USER, true);
    when(users.findById(2L)).thenReturn(Optional.of(target));
    when(users.save(target)).thenReturn(target);

    service.updateRole(actor(1L), 2L, UserRole.ADMIN);

    verify(activityService).record(eq(1L), eq(ActivityAction.ADMIN_ROLE_CHANGE), isNull(),
            argThat(metadata -> metadata.equals(Map.of(
                    "targetUserId", 2L, "previousRole", "USER", "newRole", "ADMIN"))));
}

@Test
void requiresReasonAndRecordsStatusBeforeAndAfterValues() {
    AuthUser target = user(2L, UserRole.USER, true);
    when(users.findById(2L)).thenReturn(Optional.of(target));

    assertThatThrownBy(() -> service.updateStatus(actor(1L), 2L, false, " "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lý do");

    when(users.save(target)).thenReturn(target);
    service.updateStatus(actor(1L), 2L, false, "Tài khoản thử nghiệm");

    verify(activityService).record(eq(1L), eq(ActivityAction.ADMIN_STATUS_CHANGE), isNull(),
            argThat(metadata -> metadata.equals(Map.of(
                    "targetUserId", 2L, "previousEnabled", true, "newEnabled", false,
                    "reason", "Tài khoản thử nghiệm"))));
}
```

Add this `listUsers` test with a finite quota and a latest activity timestamp:

```java
@Test
void listsGenerationRequestsLatestActivityAndQuotaResetDate() {
    AuthUser target = user(2L, UserRole.USER, true);
    target.setCreatedAt(LocalDateTime.of(2026, 8, 1, 0, 0));
    UsageQuota quota = new UsageQuota();
    quota.setQuotaLimit(10);
    quota.setQuotaUsed(3);
    quota.setPeriodStart(LocalDate.of(2026, 8, 1));
    UserActivityLog activity = new UserActivityLog();
    activity.setCreatedAt(LocalDateTime.of(2026, 8, 20, 10, 0));

    when(users.findAll(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(target)));
    when(quotaService.current(2L)).thenReturn(quota);
    when(activities.countByUserIdAndActionTypeIn(eq(2L), anyList())).thenReturn(4L);
    when(activities.findFirstByUserIdOrderByCreatedAtDesc(2L)).thenReturn(Optional.of(activity));

    PageDto<UserSummaryDto> result = service.listUsers(null, null, null, 0, 10, "createdAt", "desc");

    assertThat(result.content().get(0).totalGenerationRequests()).isEqualTo(4L);
    assertThat(result.content().get(0).lastActivityAt()).isNotNull();
    assertThat(result.content().get(0).quota().resetDate()).isEqualTo(LocalDate.of(2026, 9, 1));
    verify(activities, never()).countByUserId(2L);
}
```

- [ ] **Step 2: Run the focused test to verify RED**

Run: `mvn test -Dtest=AdminServiceTest`

Expected: compilation fails because the repository count/query methods, the four-argument `updateStatus`, and new DTO fields do not exist.

- [ ] **Step 3: Implement service policy, queries, DTOs, and controller mapping**

Add repository methods:

```java
long countByRoleAndEnabledTrue(UserRole role);

long countByUserIdAndActionTypeIn(Long userId, List<ActivityAction> actionTypes);
Optional<UserActivityLog> findFirstByUserIdOrderByCreatedAtDesc(Long userId);
```

Change the status request to accept `reason` with `@Size(max = 500, message = "Lý do khóa không được quá 500 ký tự")`. In `AdminService`, load the target before modification, then:

```java
if (admin.getId().equals(userId) && !enabled) {
    throw new IllegalArgumentException("Admin không thể tự khóa tài khoản đang đăng nhập");
}
if (!enabled && (reason == null || reason.isBlank())) {
    throw new IllegalArgumentException("Cần nhập lý do khi khóa tài khoản");
}
if (!enabled && isLastEnabledAdmin(user)) {
    throw new IllegalArgumentException("Không thể khóa admin đang hoạt động cuối cùng");
}
```

Apply the same `isLastEnabledAdmin(user)` guard when changing an enabled target from `ADMIN` to `USER`; keep the existing self-demotion check. Record immutable values with the keys asserted above. Pass `request.reason()` from `AdminController.status`.

Change `QuotaDto` and `toQuota` to return `null` remaining for unlimited, `false` exceeded for unlimited, and `quota.getPeriodStart().plusMonths(1)` as reset date. Build a summary with `countByUserIdAndActionTypeIn(userId, GENERATION_ACTIONS)` and the optional latest activity converted by the existing `toInstant` helper. Rename `totalActivities` to `totalGenerationRequests` everywhere in this backend module.

- [ ] **Step 4: Run the focused backend tests to verify GREEN**

Run: `mvn test -Dtest=AdminServiceTest,UsageQuotaServiceTest,AdminControllerTest`

Expected: all named tests pass, including existing authorization controller tests.

- [ ] **Step 5: Commit only this task's backend files**

```powershell
git add -- src/main/java/com/greytest/repository/AuthUserRepository.java src/main/java/com/greytest/repository/UserActivityLogRepository.java src/main/java/com/greytest/dto/admin/UpdateUserStatusRequest.java src/main/java/com/greytest/dto/admin/AdminDtos.java src/main/java/com/greytest/service/AdminService.java src/main/java/com/greytest/controller/AdminController.java src/test/java/com/greytest/service/AdminServiceTest.java
git commit -m "feat: secure admin user management"
```

### Task 3: Present safe admin controls and clear quota state

**Files:**
- Modify: `../KLTN_26_27_GreyTest_FE/src/features/admin/types.ts`
- Modify: `../KLTN_26_27_GreyTest_FE/src/features/admin/api/admin-api.ts`
- Modify: `../KLTN_26_27_GreyTest_FE/src/features/admin/hooks/useAdmin.ts`
- Modify: `../KLTN_26_27_GreyTest_FE/src/features/admin/pages/AdminUsersPage.tsx`
- Modify: `../KLTN_26_27_GreyTest_FE/src/features/admin/pages/AdminUserDetailPage.tsx`
- Modify: `../KLTN_26_27_GreyTest_FE/src/features/admin/utils/activity-presentation.ts`
- Modify: `../KLTN_26_27_GreyTest_FE/src/features/admin/pages/AdminUsersPage.test.tsx`
- Create: `../KLTN_26_27_GreyTest_FE/src/features/admin/utils/activity-presentation.test.ts`

**Consumes:** Task 2 JSON fields and status request body `{ enabled, reason }`.

**Produces:** `updateUserStatus(id, enabled, reason?)`, `updateUserQuota(id, quotaLimit: number | null)`, and a self-action-safe admin table.

- [ ] **Step 1: Write the failing frontend tests**

Mock `../../auth/hooks/useAuth` in the page test to export `useCurrentUser`, returning `{ data: { id: 1, email: 'admin@greytest.dev', fullName: 'Admin', role: 'ADMIN' } }`. Render a row with `id: 1` and assert:

```tsx
expect(screen.getByLabelText('Vai trò của admin@greytest.dev')).toBeDisabled();
expect(screen.getByRole('button', { name: 'Khóa' })).toBeDisabled();
```

Render a non-self user, click `Khóa`, and assert that the confirm button is disabled until `Lý do khóa tài khoản` contains `Tài khoản thử nghiệm`; after submit assert the mutation payload is `{ id: 9, enabled: false, reason: 'Tài khoản thử nghiệm' }`.

Render a user with `quota: { limit: null, used: 34, remaining: null, periodStart: '2026-08-01', resetDate: '2026-09-01', exceeded: false }`. Assert `Không giới hạn` is visible and no progress-bar element is present. Add a separate `activity-presentation.test.ts` assertion:

```ts
expect(formatActivityMetadata({
  targetUserId: 9,
  previousRole: 'USER',
  newRole: 'ADMIN',
})).toBe('Tài khoản #9 · Vai trò USER → ADMIN');
```

- [ ] **Step 2: Run the focused frontend test to verify RED**

Run from `KLTN_26_27_GreyTest_FE`: `npm test -- AdminUsersPage.test.tsx activity-presentation.test.ts`

Expected: tests fail because `useCurrentUser` is not used, lock requests have no reason, nullable quota is unsupported, and audit before/after text is absent.

- [ ] **Step 3: Implement the smallest typed UI changes**

Update the TypeScript interfaces in the Interface changes section. Change API/hook payloads to:

```ts
export async function updateUserStatus(id: number, enabled: boolean, reason?: string): Promise<AdminUser> {
  return (await apiClient.patch(`/admin/users/${id}/status`, { enabled, reason })).data;
}

export async function updateUserQuota(id: number, quotaLimit: number | null): Promise<UsageQuota> {
  return (await apiClient.patch(`/admin/users/${id}/quota`, { quotaLimit })).data;
}
```

In `AdminUsersPage`, import `useCurrentUser`; derive `isSelf = currentUser?.id === user.id`; disable that row's role select and lock/unlock button. Replace the generic status confirmation only with a local `<dialog>` form: when `statusTarget.enabled` is true, require a `Lý do khóa tài khoản` textarea with `maxLength={500}` and submit the reason; when false, retain a simple unlock confirmation. Do not create a shared dialog component for this single form.

Represent the quota editor as `{ id, name, value, unlimited }`. Its checkbox sets `unlimited`; submit `null` when checked. Finite quota views show the progress bar and `Reset ngày {resetDate}`. Unlimited views render `Không giới hạn`, the reset date, and no progress-bar `<div role="progressbar">`. Add `role="progressbar"` to the finite bar for the test.

Rename the table/detail metric to `Tổng lượt sinh test`, add a `Hoạt động gần nhất` column using `lastActivityAt ?? 'Chưa có'`, and add `whitespace-nowrap` to status badges and row actions. In the activity formatter, prefer `previousRole`/`newRole` and `previousEnabled`/`newEnabled` output; append `Lý do: ${reason}` when metadata contains a nonblank string. For quota audit, render `Quota Không giới hạn` when `quotaLimit === null`.

- [ ] **Step 4: Run focused tests and build to verify GREEN**

Run from `KLTN_26_27_GreyTest_FE`:

```powershell
npm test -- AdminUsersPage.test.tsx activity-presentation.test.ts
npm run build
```

Expected: focused Vitest tests pass and TypeScript/Vite build succeeds.

- [ ] **Step 5: Commit only this task's frontend files**

```powershell
git add -- src/features/admin/types.ts src/features/admin/api/admin-api.ts src/features/admin/hooks/useAdmin.ts src/features/admin/pages/AdminUsersPage.tsx src/features/admin/pages/AdminUserDetailPage.tsx src/features/admin/utils/activity-presentation.ts src/features/admin/pages/AdminUsersPage.test.tsx src/features/admin/utils/activity-presentation.test.ts
git commit -m "feat: clarify admin quota controls"
```

### Task 4: Run regression suites and inspect migrations

**Files:**
- Verify only; no source file changes.

**Consumes:** Tasks 1–3 implementation and both module dependency locks.

**Produces:** Evidence that backend/frontend regressions did not occur and migration is valid SQL for PostgreSQL.

- [ ] **Step 1: Run complete backend tests**

Run from `KLTN_26_27_GreyTest_BE`: `mvn test`

Expected: Maven exits with status `0`.

- [ ] **Step 2: Run complete frontend tests**

Run from `KLTN_26_27_GreyTest_FE`: `npm test`

Expected: Vitest exits with status `0`.

- [ ] **Step 3: Inspect the exact working-tree scope**

Run from each module:

```powershell
git status --short
git diff --check
```

Expected: no whitespace errors. Only this plan's files are staged/committed; any prior user files remain untouched.

- [ ] **Step 4: Commit no regression-only changes**

Run: `git status --short`

Expected: no new regression-only files to commit.
