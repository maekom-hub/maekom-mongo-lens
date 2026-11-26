# Maekom Mongo Lens

[![Version](https://img.shields.io/badge/version-v0.2.3-blue.svg)](https://github.com/maekom-hub/maekom-mongo-lens/releases)
[![IntelliJ IDEA](https://img.shields.io/badge/IntelliJ%20IDEA-2024.1%20~%202026.x-purple.svg)](https://www.jetbrains.com/idea/)
[![License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](./LICENSE)

**[English (README.md)](./README.md) | [한국어 (README_KO.md)](./README_KO.md)**

> **Zero-Config MongoDB & AWS DocumentDB Compass-style GUI, Multi-Join View, Read-Only Index Inspector, and Bidirectional Aggregation Pipeline Builder directly inside IntelliJ IDEA (Community & Ultimate).**

---

## Overview

**Maekom Mongo Lens** brings a full-featured MongoDB Compass workflow directly into IntelliJ IDEA without external tools or manual connection setup. It automatically discovers Spring Boot `application*.yml` MongoDB profiles, safeguards production databases with Read-Only protection, and provides production-tested utilities for querying, joining, inspecting indexes, diffing documents, and temporarily decrypting AES-encrypted fields — all 100% compatible with both **MongoDB** and **AWS DocumentDB**.

---

## Key Features

### 1. Zero-Config Spring Boot YAML Auto-Discovery & PRD Protection
- Automatically scans single- and multi-module Spring Boot projects (`**/src/main/resources/application*.yml(yaml)`), including `---` multi-document YAML (`spring.config.activate.on-profile`, `spring.profiles`).
- Supports both `spring.data.mongodb.uri` and individual properties (`host`, `port`, `database`, `username`, `password`, `authentication-database`).
- Resolves `${VAR:default}` placeholders automatically (default value → project `.env` → OS environment variables) and watches YAML edits in real time.
- **Lazy Connection**: Opens zero sockets at IDE startup; connects only when expanding a profile.
- **Production Read-Only Lock (`READ-ONLY 🔒`)**: Automatically protects profiles containing `prod` / `prd` against accidental writes, with optional per-session temporary unlock.

### 2. Compass-Style Multi-Tab Query Workspace & Visual Hierarchy
- **Clear Mode Navigation**: Distinct bottom-accent mode tabs (`Documents (Find)` / `Aggregations` / `Mongo Shell`) and a rounded icon segmented control for **`Tree` | `Table` | `JSON`** result views.
- **Unified Filter / Sort / Project Editors**: Auto-growing JSON editors with syntax highlighting, `{}` auto-wrapping, field & operator autocomplete, and Mongo Shell normalization (`ObjectId(...)`, `ISODate(...)`, `NumberLong(...)`, `NumberDecimal(...)`, unquoted keys).
- **Quick Column Chooser (`SELECT` Mode)**: Right-click any Table header → **`Hide This Column`** or **`Select Visible Columns (Project)...`** to generate `{ "_id": 0, ... }` projections without manual typing.
- **Smart `$in` Builder**: Paste newline/comma/space-separated IDs and auto-detect `ObjectId` / `Number` / `String` types.

### 3. Reorganized 5-Group Context Menu & Multi-Selection Smart Quick Filter / Sort
Right-click any field or multi-selected cells in **Tree** or **Table** view (`Ctrl`/`Shift`-click selection preserved):
1. **Dynamic Quick Filter & Sort** *(merges/upserts cleanly into existing Filter without erasing unrelated conditions)*:
   - **Single scalar value**: `Filter by This Value (=)` / `Exclude This Value (!=)`
   - **Same field + 2+ values**: `Filter by N Selected Values ($in)` / `Exclude N Selected Values ($nin)`
   - **Same field + 2 Date/Timestamp values** (`BsonDateTime` or 13-digit epoch ms): `Filter by Selected Date Range ($gte ~ $lte)` / `Match Selected 2 Dates ($in)`
   - **2+ different fields**: `Match All N Selected Conditions (AND)` / `Match Any of N Selected Conditions ($or)` *(safely wraps existing `$or` in `$and`)*
   - **Nested Object / Array**: `Field Exists ($exists: true)` / `Field Empty or Missing`
   - **Single-click Sort**: `Sort by This Field ▶ Ascending (1) / Descending (-1)`
   - **UTC Time Range Filter**: For `ObjectId` and `Date`, view KST/UTC timestamps inline or in hover tooltips and generate exact UTC `$gte` / `$lte` filters.
2. **Edit**: **`Edit Data...`** (structured Key-Value + Raw JSON editor with unsaved diff prompt).
3. **Copy Submenu**: `Copy Value Only` | `Copy Key:Value (JSON)` | `Copy Field Path`.
4. **Relational Navigation**: `Find in Referenced Collection (New Tab)...` (auto-coerces `ObjectId` ↔ 24-hex String) and `Join with Another Collection...`.
5. **Document Diff**: Compare 2 selected documents or pin a document as a cross-tab/cross-environment **Baseline** (`DiffManager` with canonical key sorting & plaintext-aware comparison).

### 4. Read-Only Index Inspector & ESR Query Filter Guide
- **Accurate MongoDB & AWS DocumentDB `explain()` Detection**: Recursively inspects `winningPlan` / `executionStages` / `$cursor` / `SUBSCAN` / `IXONLY` (ignoring `rejectedPlans` & `allPlansExecution`) with multi-tier fallbacks. Displays **`IXSCAN`**, **`COLLSCAN`**, or **`UNKNOWN`** (with failure reason tooltip — never misclassifying explain failures as `COLLSCAN`).
- **Strictly Read-Only Inspector**: Click the `IXSCAN` / `COLLSCAN` / `UNKNOWN` badge or right-click a collection in Connections tree → **`Inspect Indexes & Query Guide...`**.
  - View index names, key specs, attributes (`UNIQUE`, `TTL`, `SPARSE`), and sizes (shared 60s cache with zero redundant `listIndexes()` calls).
  - Click **`Apply Filter Template from Index Keys`** to populate the `Filter` editor with the selected index keys.
  - Review query diagnostics comparing active `Filter`/`Sort` fields against collection indexes, plus a **copy-only ESR (`Equality → Sort → Range`) `createIndex` recommendation** for DBA/ops review (never executes write commands against DB).

### 5. AWS DocumentDB-Compatible Multi-Join View (`A ⋈ B ⋈ C`)
- **Star Join & Chain Join**: Join a base collection with multiple targets (`A → B`, `A → C`, or chained `A → B → C`) using standard `localField`/`foreignField` `$lookup` (100% AWS DocumentDB compatible, zero `let`/`pipeline`, zero `$facet`, `.allowDiskUse(true)`).
- **Array-of-Objects Join Keys**: Automatically samples nested Object and Array-of-Objects dot-notation paths (e.g., `items.product_id`) in both source and target key dropdowns.
- **Per-Target `WHERE AND` & 1:N Strategies**: Match latest 1 record (`LATEST_ONE`) or unwind all (`UNWIND_ALL`), with `=`, `!=`, `>`, `>=`, `<`, `<=`, `IN`, `NOT NULL`, and `NULL/EMPTY` conditions plus inner-join exclusion.
- **Deterministic Post-Join Paging & Explicit Limit Modes**: Enforces Base `$match` → Deterministic `$sort` (`_id` tie-breaker) → `$lookup` & exclusion `$match` → Post-Join `$skip` / `$limit(N + 1)` → `$project`, eliminating inconsistent page counts. Supports **`20` / `50` / `100` / `500` / `Unlimited (All, 5,000 safety cap)`**.
- **Live Edit & Pipeline Copy**: Modify join targets/conditions/columns in-place via **`Edit Join`**, copy the runnable pipeline JSON via **`Copy Pipeline`**, and see clear `collection.field` origin labels in both Table headers and Tree view.

### 6. Bidirectional Aggregation Builder, Mongo Shell & AES Field Decryption
- **Bidirectional Aggregation Builder**: Real-time sync between `🧩 Stage Builder (UI)` (ON/OFF toggles, reorder, 5-doc stage preview) and `{ } Raw Script`.
- **Mongo Shell Smart Helper**: Execute `mongosh`-style scripts with `{{variable}}` substitution (`{{$now}}`, `{{$today}}`, `{{$objectId}}`), built-in templates, and a searchable syntax cheatsheet.
- **Temporary AES Field Decryption**: Configure per-profile AES/CBC (IV prefix or fixed IV) or AES/ECB keys (with YAML key scanner), decrypt individual Tree fields or batch-decrypt the current **Table** page with auto-masking countdown.
- **In-App GitHub Releases Updater & Real-Time KO/EN UI**: One-click verified update staging and instant Korean/English switching in the top-right `Settings` menu.

---

## Installation

### Install from Disk (GitHub Releases ZIP)
1. Download the latest `maekom-mongo-lens-0.2.3.zip` from [GitHub Releases](https://github.com/maekom-hub/maekom-mongo-lens/releases).
2. In IntelliJ IDEA, open **`Settings / Preferences` → `Plugins` → ⚙️ (Gear Icon) → `Install Plugin from Disk...`**.
3. Select the downloaded `.zip` archive and restart the IDE.
4. Open the **`Maekom Mongo Lens`** tool window at the bottom of the IDE.

> After initial installation, use **`Settings` → `Check Updates`** in the tool window title bar for one-click in-app updates.

---

## Spring Boot YAML Zero-Config Example

```yaml
spring:
  data:
    mongodb:
      authentication-database: admin
      port: 27017

---
spring:
  config:
    activate:
      on-profile: local
  data:
    mongodb:
      uri: ${MONGO_LOCAL_URI:mongodb://localhost:27017/my_service}

---
spring:
  config:
    activate:
      on-profile: prd
  data:
    mongodb:
      host: ${PRD_MONGO_HOST}
      database: my_service_prod
      username: ${PRD_MONGO_USER}
      password: ${PRD_MONGO_PASS}
```

- Profiles whose name or filename includes `prod` or `prd` automatically activate **`READ-ONLY 🔒`** protection.
- Placeholders without defaults (e.g., `${PRD_MONGO_HOST}`) are resolved from the project root `.env` file or OS environment variables.

---

## Shortcuts & Quick Reference

| Action | Shortcut / How |
|---|---|
| Execute Query / Pipeline / Shell | `Ctrl + Enter` (macOS: `Cmd + Enter`) |
| Field & Operator Autocomplete | `Ctrl + Space` (or automatic on `"` / `$`) |
| Multi-Select Smart Quick Filter | `Ctrl` / `Shift`-click cells in Table or nodes in Tree → Right-click |
| Inspect Indexes & Query Guide | Click `IXSCAN` / `COLLSCAN` / `UNKNOWN` badge, or Right-click collection in Connections tree |
| Quick Column Chooser (`SELECT`) | Right-click any Table column header → **`Select Visible Columns (Project)...`** |
| Open Multi-Join View (`A ⋈ B`) | Right-click any field → **`Join with Another Collection...`** |
| Compare 2 Documents (Diff) | `Ctrl` / `Shift`-click 2 rows/docs → Right-click → **`Compare 2 Selected Documents (Diff)`** |
| Search Inside Raw JSON View | Switch to `JSON` segment → `Ctrl + F` (macOS: `Cmd + F`) |
| Switch Language (`KO ↔ EN`) | Top-right **`Settings` → `Language`** |

---

## Build from Source

```bash
./gradlew buildPlugin --no-daemon
```

The distributable plugin archive will be generated at:
`build/distributions/maekom-mongo-lens-0.2.3.zip`

---

## License

This project is licensed under the **Apache License 2.0**. See [LICENSE](./LICENSE) for details.
