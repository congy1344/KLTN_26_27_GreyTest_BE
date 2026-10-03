# User Tiers (Free / Pro) & Quota Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Free and Pro user tiers with a 100-request monthly limit for Free tier, a 1,000-request monthly limit for Pro tier, an upgrade modal for end-users when their quota is exhausted, and tier management in the Admin Users view.

**Architecture:**
- **Backend:** 
  - Add `UserTier` enum (`FREE`, `PRO`) and persist `tier` in `auth_user`.
  - Add Flyway migration `V25__add_user_tier.sql`.
  - Configure `greytest.usage.pro-monthly-llm-quota: 1000` (alongside existing `default-monthly-llm-quota: 100`).
  - Provide `POST /api/user/upgrade-pro` for current users to self-upgrade to Pro and increase quota to 1,000.
  - Provide `PATCH /api/admin/users/{userId}/tier` for admins to switch user tiers.
  - Include `tier` in `AuthUserDto`, `UserSummaryDto`, `UserDetailDto`.
  - Log tier changes via `UserActivityService`.
- **Frontend:**
  - Create `UpgradeToProModal` that listens to global quota exceeded events (`window.addEventListener('quota-exceeded')`) and can also be opened manually.
  - Intercept HTTP 429 `USAGE_QUOTA_EXCEEDED` in `api-client.ts` to dispatch `quota-exceeded`.
  - Display Tier badge in `AdminUsersPage` with a dropdown/action for admins to switch between Free and Pro.
  - Show Tier badge and upgrade option in user UI.

**Tech Stack:** Spring Boot 3, Java 17, Spring Data JPA, Flyway/PostgreSQL, React 18, TypeScript, Tailwind CSS, Lucide icons, Vitest.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/resources/db/migration/V25__add_user_tier.sql` | Add `tier` column to `auth_user` with default `'FREE'`. |
| `src/main/java/com/greytest/entity/enums/UserTier.java` | Enum `FREE`, `PRO`. |
| `src/main/java/com/greytest/entity/AuthUser.java` | Add `tier` field mapped with `@Enumerated(EnumType.STRING)`. |
| `src/main/java/com/greytest/entity/enums/ActivityAction.java` | Add `USER_UPGRADE_TIER`, `ADMIN_TIER_CHANGE`. |
| `src/main/java/com/greytest/dto/AuthUserDto.java` | Include `tier` in auth response. |
| `src/main/java/com/greytest/dto/admin/AdminDtos.java` | Include `tier` in `UserSummaryDto` and `UserDetailDto`. |
| `src/main/java/com/greytest/dto/admin/UpdateUserTierRequest.java` | DTO for admin updating user tier. |
| `src/main/java/com/greytest/service/UsageQuotaService.java` | Support `proLimit` configuration and tier-based quota adjustments. |
| `src/main/java/com/greytest/service/UserService.java` | Handle current user profile and tier upgrades. |
| `src/main/java/com/greytest/controller/UserController.java` | Expose `POST /api/user/upgrade-pro` and `GET /api/user/quota`. |
| `src/main/java/com/greytest/service/AdminService.java` | Handle `updateTier` for admin and map `tier` into summary. |
| `src/main/java/com/greytest/controller/AdminController.java` | Expose `PATCH /api/admin/users/{userId}/tier`. |
| `src/test/java/com/greytest/service/UserServiceTest.java` | Unit tests for user tier upgrade. |
| `src/test/java/com/greytest/service/AdminServiceTierTest.java` | Unit tests for admin tier management. |
| `../KLTN_26_27_GreyTest_FE/src/features/auth/types.ts` | Add `tier: 'FREE' \| 'PRO'` to `AuthUser`. |
| `../KLTN_26_27_GreyTest_FE/src/features/admin/types.ts` | Add `tier: 'FREE' \| 'PRO'` to admin user types. |
| `../KLTN_26_27_GreyTest_FE/src/features/admin/api/admin-api.ts` | Add API call for updating user tier. |
| `../KLTN_26_27_GreyTest_FE/src/features/admin/hooks/useAdmin.ts` | Add mutation for updating user tier. |
| `../KLTN_26_27_GreyTest_FE/src/features/admin/pages/AdminUsersPage.tsx` | Render Tier badge and tier switcher in admin users table. |
| `../KLTN_26_27_GreyTest_FE/src/shared/components/UpgradeToProModal.tsx` | Modal presenting Pro features, quota exhaustion notice, and instant upgrade. |
| `../KLTN_26_27_GreyTest_FE/src/shared/api/api-client.ts` | Intercept 429 `USAGE_QUOTA_EXCEEDED` and dispatch event. |
| `../KLTN_26_27_GreyTest_FE/src/App.tsx` | Mount `UpgradeToProModal` globally. |

---

## Tasks

### Task 1: Backend Database & Entities (UserTier & Migration)
- [ ] Create `UserTier.java` with enum constants `FREE`, `PRO`.
- [ ] Add `tier` field to `AuthUser.java` with default `UserTier.FREE`.
- [ ] Add `USER_UPGRADE_TIER` and `ADMIN_TIER_CHANGE` to `ActivityAction.java`.
- [ ] Create Flyway migration `V25__add_user_tier.sql`:
  ```sql
  ALTER TABLE auth_user ADD COLUMN tier VARCHAR(20) NOT NULL DEFAULT 'FREE';
  ```
- [ ] Update `AuthUserDto` and `AdminDtos.UserSummaryDto` to include `tier`.

### Task 2: Backend Service & APIs (Upgrade to Pro & Admin Tier Management)
- [ ] Update `UsageQuotaService`:
  - Add `proLimit` (`@Value("${greytest.usage.pro-monthly-llm-quota:1000}") int proLimit`).
  - Add method `upgradeToPro(Long userId)` which sets quota limit to `proLimit`.
  - Add method `downgradeToFree(Long userId)` which sets quota limit to `defaultLimit`.
- [ ] Create `UserService`:
  - Method `upgradeCurrentToPro(AuthUser user)`: updates `user.setTier(UserTier.PRO)`, calls `quotaService.upgradeToPro(user.getId())`, records activity `USER_UPGRADE_TIER`.
- [ ] Create `UserController`:
  - Endpoint `POST /api/user/upgrade-pro`.
- [ ] Update `AdminService`:
  - Add `updateTier(AuthUser admin, Long userId, UserTier tier)`.
  - Update `summary(AuthUser user)` to pass `user.getTier()`.
- [ ] Update `AdminController`:
  - Add `PATCH /api/admin/users/{userId}/tier`.
- [ ] Write unit tests in `src/test/java/com/greytest/service/UserServiceTest.java`.

### Task 3: Frontend Integration & Upgrade Modal
- [ ] Update `types.ts` in auth and admin to include `tier: 'FREE' | 'PRO'`.
- [ ] In `api-client.ts`, add response interceptor for 429 `USAGE_QUOTA_EXCEEDED` to dispatch `quota-exceeded` CustomEvent.
- [ ] Create `UpgradeToProModal.tsx`:
  - Listen for `quota-exceeded` event or open via prop.
  - Show comparison between Free (100) and Pro (1,000).
  - Call `POST /api/user/upgrade-pro` on click.
  - On success, show toast and invalidate user query.
- [ ] Mount `UpgradeToProModal` in `App.tsx`.

### Task 4: Admin Users Page Tier Management
- [ ] Add tier mutation in `useAdmin.ts` and `admin-api.ts`.
- [ ] In `AdminUsersPage.tsx`:
  - Add column/badge for **Gói (Tier)** with distinct styling (Free: neutral, Pro: brand purple/amber with Crown icon).
  - Allow admin to switch tier between FREE and PRO with confirmation dialog.
- [ ] Verify frontend build and tests (`npm run build` or `npm test`).

### Task 5: Verification & Docker Deployment
- [ ] Compile Java code with Maven in Docker container (`docker compose build backend`).
- [ ] Restart backend container (`docker compose up -d --no-deps backend`).
- [ ] Test upgrade flow and admin tier switching.
