# Permission Integration Migration Runbook

## Source and target

Source:

```text
ivRooom/ivrm-dailytech-adventure
└─ mods/ivrm-permission-control
```

Target:

```text
ivRooom/ivrm-mcmod
└─ mods/ivrm-permission-integration
```

Architecture source: `ivRooom/ivrm-platform#4`

Tracking:

- Target: `ivRooom/ivrm-mcmod#3`
- Source cleanup: `ivRooom/ivrm-dailytech-adventure#12`

## Compatibility policy

Initial migration is intentionally behavior-preserving.

- MOD ID: `ivrm_permission_control` — unchanged
- Java package: `jp.ivrm.minecraft.permissioncontrol` — unchanged
- Permission namespace: `ivrm` — unchanged
- Permission nodes — unchanged
  - `ivrm.play.build`
  - `ivrm.play.craft`
  - `ivrm.play.container`
  - `ivrm.play.interact`
  - `ivrm.play.combat`
  - `ivrm.play.pickup`
- Default permission behavior remains fail-closed.
- Denied-message cooldown remains 3 seconds.

`play.craft` and `play.container` remain reserved/registered nodes. This migration does not silently add new interception behavior for them.

## Runtime alignment

The source project was pinned to NeoForge `26.1.2.87`, while the current IVRM Main/Resource and Player Bridge baseline is `26.1.2.81`.

The target module is therefore pinned to:

- Minecraft `26.1.2`
- NeoForge `26.1.2.81`
- Java `25`

CI must prove that the implementation compiles against `.81`. Do not upgrade production only to make this migration pass. If an API moved between the versions, adapt the implementation to the current `.81` baseline or make a separate runtime-upgrade decision.

The block-break event is one confirmed example: the `.81` baseline uses `BreakBlockEvent`, so the migrated implementation uses that API instead of the later/older nested `BlockEvent.BreakEvent` form.

## Validation

### CI

Both artifacts must build independently:

```bash
gradle --no-daemon clean build
gradle -p mods/ivrm-permission-integration --no-daemon clean build
```

### Server validation

Before source cleanup:

1. Back up the currently deployed permission-control JAR and SHA-256.
2. Build the target JAR from `ivrm-mcmod`.
3. Verify MOD ID remains `ivrm_permission_control`.
4. Verify all six permission nodes are gathered.
5. Test an unapproved player:
   - break/place denied
   - block/item/entity interaction denied
   - combat denied
   - pickup denied
   - Q-drop denied without item loss
   - drag-and-drop outside inventory denied without item loss
   - full-inventory edge case preserves every item even if enforcement must fail open for an unrecoverable remainder
6. Test an approved member with existing LuckPerms nodes.
7. Restart the server and repeat a minimal smoke test.
8. Verify Main and Resource separately when both load the integration.

## Cutover

The source and target JARs intentionally declare the same MOD ID. They must never be co-installed.

Only after validation:

1. Record the currently deployed JAR filename, SHA-256 and source commit.
2. Stop the affected Minecraft server.
3. Remove the old permission-control JAR from the runtime `mods` directory.
4. Install the target JAR generated from `ivrm-mcmod`.
5. Before startup, inspect the runtime JAR set and verify **exactly one** JAR declares MOD ID `ivrm_permission_control`.
6. Start the server and run the permission smoke tests.
7. Update the modpack manifest/SHA lock in `ivrm-dailytech-adventure` only after the runtime test succeeds.
8. Record the deployed target SHA-256 and source commit.
9. Keep the previous known-good JAR outside the active `mods` directory for rollback.
10. Remove `ivrm-dailytech-adventure/mods/ivrm-permission-control` source in a separate PR.
11. Remove stale CI/docs references to the old source path.

Do not copy the new JAR beside the old JAR and rely on filename differences. MOD ID uniqueness, not filename uniqueness, is the startup safety condition.

## Rollback

If permission behavior changes unexpectedly:

1. Stop the affected Minecraft server.
2. Remove the target JAR from the active `mods` directory.
3. Restore the previous known-good JAR.
4. Verify **exactly one** active JAR declares MOD ID `ivrm_permission_control` before startup.
5. Restore the previous manifest/SHA lock if it had already changed.
6. Start the server.
7. Verify LuckPerms-backed approved users and default-denied users.
8. Keep the source cleanup PR unmerged until the cause is understood.
