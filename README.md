<div align="center">
  <img src="src/main/resources/assets/coreprotect_meltdown/icon.png" alt="CoreProtect Meltdown Logo" width="160" height="160" />
  <h1>CoreProtect Meltdown</h1>
  <p><strong>Keep CoreProtect fast and lag-free on busy Minecraft 26.2–26.3 Fabric servers.</strong></p>
</div>

> [!IMPORTANT]
> **Requires CoreProtect (Fabric Edition)**  
> This mod is a lightweight add-on and contains **no CoreProtect code**. You must install `coreprotect-fabric-1.3.jar` (available from the official **[CoreProtect Patreon](https://www.patreon.com/coreprotect)**) alongside this mod.

---

## What It Does

On active servers, big redstone machines, hopper lines, and mob farms can flood CoreProtect with thousands of logs per second—causing database lag and slow lookups. **CoreProtect Meltdown** fixes this automatically:

* **Stops Farm & Contraption Spam**: Filters out repetitive machine and mob-farm noise while **keeping 100% of real player actions** (block edits, chest access, griefing logs) safe.
* **Instant Lookups**: Optimizes the SQLite database so `/co near` and `/co lookup` respond in milliseconds, even on servers with millions of logged events.
* **Smarter Commands**:
  * `/co near` works across older history without cutting off after 24 hours, and supports simple numbers like `/co near 15`.
  * `/co lookup <player>` searches all dimensions and all time by default, including past usernames.
* **Lag Hotspot Finder**: Shows which areas are spamming logs the most, with a one-click **`[Teleport]`** button in chat to inspect them immediately.

---

## Requirements

* **Minecraft**: `26.2` – `26.3`
* **Fabric Loader**: `>=0.19.3` (with `fabric-api`)
* **Java**: `>=25`
* **Required Mod**: **CoreProtect (Fabric Edition)** `1.3`

---

## Commands & Permissions

| Command | Description |
| :--- | :--- |
| `/co debug status` | View queue health, memory usage, and filtered spam stats |
| `/co debug heatmap` | List top log-spam locations with clickable **`[Teleport]`** buttons |
| `/co debug config` | View current `coreprotect-meltdown.properties` settings |
| `/co debug lang [auto\|en_us\|ko_kr]` | Switch display language (`auto` detects client language) |
| `/co debug reload` | Reload config without restarting the server |

* **Permissions**: `coreprotect.debug` (status & heatmap), `coreprotect.meltdown.admin` (one-click teleport & admin actions)

---

## Build from Source

```bash
./gradlew build
```

The compiled JAR is generated in `build/libs/`.

---

## Notes & License

* Developed with the assistance of **Google Antigravity**.
* Licensed under the **[MIT License](./LICENSE)** (applies to CoreProtect Meltdown's own code only).
