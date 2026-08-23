# DAMN Prefix Guard

This is a narrowly scoped Project Zomboid 42.20.3 compatibility patch for the
official DAMN Library Workshop item (`3171167894`, mod ID `damnlib`). It allows
the game to finish loading a DAMN file that the game itself already selected as
the active file but later rejects because its allowed-prefix cache is stale.

The patch does not copy, replace, or modify DAMN assets. It fails closed unless
all of these conditions still hold:

- Project Zomboid is exactly 42.20.3.
- ZombieBuddy is exactly 2.3.2.
- `validatePrefix(String)` throws the exact current vanilla exception.
- `getModDir("damnlib")` resolves to Workshop item `3171167894`.
- The rejected path is a regular file inside that exact mod root.
- `isKnownFile(path)` returns true.
- The same real path is an active `ZomboidFileSystem.activeFileMap` value.

Any changed or missing interface preserves the original exception. Re-running
the advice is harmless and does not mutate the game or mod registries.

## Build and test

```powershell
py -3 .\build.py test
py -3 .\build.py package
```

The build is locked to the locally installed ZombieBuddy 2.3.2 JAR. Override
its location with `ZOMBIE_BUDDY_JAR` only when intentionally validating another
copy with the same locked SHA-256.

Deployment is explicit:

```powershell
py -3 .\build.py deploy --target-root "$env:USERPROFILE\Zomboid\mods"
```

ZombieBuddy will require approval for the new JAR hash on the first launch.
