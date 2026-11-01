# CoreProtect Neo

## !!! WARN:THIS MOD WAS MADE BY AI !!!

Chinese documentation: [README.md](README.md)

> A NeoForge 26.2 port of CoreProtect 24.1: action logging, lookups and rollbacks.
>
> The database tables and many configuration and command names follow the original plugin.
> NeoForge metadata has its own encoding and is not interchangeable with Bukkit object data.

---

## 1. What this is

The original CoreProtect is a Paper/Spigot plugin. This project ports it to NeoForge:

- **Original data model adapted**: table names, most columns, language phrases and config keys
  follow the original plugin, with NeoForge-specific item and block-entity metadata.
- **Platform layer replaced**: Bukkit events became NeoForge events, and the Bukkit world/item/block
  APIs were replaced with Minecraft/NeoForge APIs.
- **Messages**: most command responses use the original `Phrase` entries and `lang/*.yml` files.

modid: `coreprotect` | version: `24.1-neoforge-26.2` | package: `net.coreprotect`

---

## 2. Requirements

| Item | Requirement |
|---|---|
| Minecraft | 26.2 |
| NeoForge | 26.2.0.88 |
| Java | 25 |
| Server | **Required** — all logging and commands run server side |
| Client | Optional — the mod has no client-only features |
| Database | Built-in SQLite (default), MySQL optional |

---

## 3. Installation and data folder

1. Put `coreprotect-24.1-neoforge-26.2.jar` into the server's `mods` folder.
2. Start the server once. A `coreprotect/` folder is created in the server directory:

| File / folder | Purpose |
|---|---|
| `config.yml` | Configuration (same keys as the original) |
| `database.db` | SQLite database (not created when MySQL is used) |
| `blacklist.txt` | Optional ignore list |
| `lang/` | Optional custom phrases and translations |

3. After editing `config.yml`, run `/co reload` or restart the server.

---

## 4. Differences from the original

This port is under development. Build and SQLite contract tests cover block-state encoding,
block-entity snapshot storage, and database rollback/restore row selection. An isolated NeoForge
26.2 server smoke test also covers chest inventory and block-property rollback/restore, including
replacement of an occupied block. Make a backup before testing with an existing CoreProtect database.

Known limits:

- WorldEdit and FAWE change logging (no equivalent plugin ecosystem on NeoForge)
- Custom mod changes, including DQM spells that bypass NeoForge player block events, are not logged
  by the current listeners. This has not yet been tested with DQM.
- Existing rows with Bukkit block-entity metadata cannot be restored with NeoForge metadata.
  The rollback skips block-entity placement when it cannot read a NeoForge snapshot.
- The server smoke test uses NeoForge fake-player events. DQM spell paths, real player commands,
  MySQL, and migration from Bukkit databases remain untested.
- bStats metrics (the port sends no telemetry at all)
- AdvancedChests compatibility
- API classes: `LookupFilter`, `LookupOptions`, `UsernameAPI` — they need Bukkit `Material`/`World`
  replaced with registry names and dimension ids
- Dedicated sign and skull recording
- Legacy database upgrade scripts 2.11 to 2.20, which need a rewrite to migrate very old databases;
  the files currently live in `src/pending-java`
- The inspector currently shows a single position's history on right-click; the original offers a
  clickable paginated list with separate interaction and container tabs

---

## 5. Building

```bash
gradlew build
```

Output: `build/libs/coreprotect-24.1-neoforge-26.2.jar`

Build environment:

| Component | Version |
|---|---|
| ModDevGradle | 2.0.148 |
| NeoForge | 26.2.0.88 |
| Gradle wrapper | 9.8.0 |
| JDK | 25 |
| Database drivers | sqlite-jdbc 3.53.0.0, mysql-connector-j 9.1.0, HikariCP 7.0.2 — bundled into the mod jar via jarJar |

---

## 6. Verification

`gradlew build testmodJar` runs the SQLite/NBT contract tests. `node src/test/node/server-smoke.mjs`
installs a fresh NeoForge server under `build/smoke`, exercises block logging and in-world
rollback/restore, then restarts it to verify saved history. The server test also verifies that
canceled break/place events produce no rows and that a temporary database failure keeps queued
actions for a later retry. It uses an isolated loopback server on port 25586.
