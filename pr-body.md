## Summary

After a package update, Android places the app in the **stopped state** until the user launches it, and the stopped-state filter suppresses delivery of the `AlarmManager` broadcast `PendingIntent`s backing persisted trigger notifications. Any trigger whose fire time falls between the update and the next manual app launch is therefore **silently dropped** — the alarm fires at the OS level, the broadcast is filtered, and nothing re-arms it because the library's native recovery only covers reboots (`RebootBroadcastReceiver`) and boot-count cold starts (`InitProvider`).

This matters most for reminder-style schedules: a medication reminder app that auto-updates overnight can miss the morning dose reminder entirely, without any code change to the notification layer.

This PR adds `PackageReplacedBroadcastReceiver`, declared in the library manifest for `ACTION_MY_PACKAGE_REPLACED` (delivered by the system to the updated app at install time, including while the app is stopped; never delivered on fresh installs). It mirrors `RebootBroadcastReceiver`: same `goAsync()` handoff discipline and the same race-guarded `NotifeeAlarmManager.rescheduleNotifications` recovery, so all persisted triggers are re-armed from the library database immediately at update time, without requiring the user to open the app.

## Type of change

- [ ] Bug fix
- [x] Feature
- [ ] Documentation
- [ ] Refactor
- [ ] Test
- [ ] Build / CI / tooling
- [ ] Other

## Related issue

No existing issue found for this gap (searched the tracker). Context: observed on a medication-reminder app built on the fork (PosoZen) after migrating from expo-notifications, whose own library manifest listens to `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` for exactly this recovery.

Closes #

## Scope

- [x] This pull request is focused on one logical change.
- [x] I have not included unrelated cleanup or formatting changes.
- [x] I have not changed public APIs, runtime behavior, native configuration, package metadata, or documentation scope unless explicitly intended.

(No public JS API changes; one new manifest-declared receiver and one new internal Java class. Runtime behavior changes only for the package-replacement case, where persisted triggers are now recovered instead of silently dropped.)

## Validation

Describe the validation performed.

- [x] `yarn format:all`
- [x] `yarn validate:all`
- [x] `yarn test:all`
- [x] Manual Android validation
- [ ] Manual iOS validation
- [x] Not applicable

Manual validation details:

```text
Platform: Android
Device / simulator: physical device (Google Pixel-class), side-by-side install verified via `adb install -r`
OS version: Android 16 (API 36 target)
React Native version: 0.86.3 (New Architecture, bridgeless)
Scenario tested: schedule a TIMESTAMP trigger a few minutes out → `adb install -r` an APK built from a
feature branch of the consumer app (carrying this library change) WITHOUT opening the app → the trigger
fires on time after the update, with no manual launch in between. Also verified the merged manifest of a
clean `expo prebuild` contains the new receiver, and that the recovery log line appears in logcat at
update time. Consumer-side before/after: without this receiver the same scenario drops the reminder
(stopped-state filter) until the app is first launched.
```

Toolchain notes (local machine, not CI): `yarn validate:all` passes (0 ESLint errors; the pre-existing
`./version` TS error only appears before `yarn build:rn`, as in the `precommit` ordering). `yarn test:all`
on a fresh yarn 4 workspace install shows 4 pre-existing `classMethod FAILED` unit-test classes
(`ShadowServiceCompatibilityTest`, `NotifeeGetInitialNotificationTest`, `PermissionRationaleTest`,
`ForegroundServiceTest`) and a mass Jest failure from a missing `react` peer resolution — both reproduce
identically on clean `main` without this change (verified via `git stash`), so they are local-environment
issues, not regressions. The new receiver shares the exact code path covered by the existing
`RebootRecoveryTest` instrumented suite (NOT RUN IN CI).

## Documentation

- [x] Documentation was updated.
- [x] `CHANGELOG.md` was updated under `[Unreleased]`.
- [ ] Documentation changes are not needed.
- [ ] Changelog entry is not needed because this is not a user-facing product change.

(`docs/react-native/triggers.mdx`: "DST and reboot recovery" section renamed to "DST, reboot and app-update recovery" with a paragraph on the new `MY_PACKAGE_REPLACED` recovery.)

## Breaking changes

Does this pull request introduce a breaking change?

- [x] No
- [ ] Yes

If yes, describe the migration path.

## Additional notes

- `MY_PACKAGE_REPLACED` is a protected system broadcast only delivered on update (never fresh install), so no behavior change for new installs.
- The receiver lives in the `app.notifee.core` package to call the package-private `NotifeeAlarmManager.rescheduleNotifications`; the existing process-wide `AtomicBoolean` race guard makes the (unlikely) double recovery pass — e.g. update immediately followed by a cold start — harmless.
- Consumers that previously worked around this gap with an app-side `MY_PACKAGE_REPLACED` receiver can remove it once they upgrade to the release containing this change.
- Happy to adjust naming/placement if you prefer a different class name or to fold the recovery into `ReceiverService`.
