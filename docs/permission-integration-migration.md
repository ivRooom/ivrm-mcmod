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

CI must prove that the existing implementation compiles against `.81`. If it does not, do not upgrade production just to make this migration pass. Instead, identify the exact API incompatibility and either adapt the implementation to `.81` or make a separate runtime-upgrade decision.

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
   - pickup/drop denied
6. Test an approved member with existing LuckPerms nodes.
7. Restart the server and repeat a minimal smoke test.
8. Verify Main and Resource separately when both load the integration.

## Cutover

Only after validation:

1. Deploy the target JAR generated from `ivrm-mcmod`.
2. Update the modpack manifest/SHA lock in `ivrm-dailytech-adventure`.
3. Record deployed SHA-256 and source commit.
4. Keep the previous known-good JAR for rollback.
5. Remove `ivrm-dailytech-adventure/mods/ivrm-permission-control` in a separate PR.
6. Remove stale CI/docs references to the old source path.

## Rollback

If permission behavior changes unexpectedly:

1. Stop the affected Minecraft server.
2. Restore the previous known-good JAR.
3. Restore the previous manifest/SHA lock if it had already changed.
4. Start the server.
5. Verify LuckPerms-backed approved users and default-denied users.
6. Keep the source cleanup PR unmerged until the cause is understood.
