# Maekom Mongo Lens

[![Version](https://img.shields.io/badge/version-v0.2.4-blue.svg)](https://github.com/maekom-hub/maekom-mongo-lens/releases)
[![IntelliJ IDEA](https://img.shields.io/badge/IntelliJ%20IDEA-2024.1%20~%202026.x-purple.svg)](https://www.jetbrains.com/idea/)
[![License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](./LICENSE)

**[English (README.md)](./README.md) | [한국어 (README_KO.md)](./README_KO.md)**

> **Zero-Config, Zero-Overhead MongoDB & AWS DocumentDB Compass-style GUI, Multi-Join View, Read-Only Index Inspector, and Bidirectional Aggregation Pipeline Builder directly inside IntelliJ IDEA (Community & Ultimate).**

---

## Overview

**Maekom Mongo Lens** brings a fast, production-safe MongoDB Compass workflow directly into IntelliJ IDEA without external clients or manual setup. It automatically discovers Spring Boot `application*.yml` MongoDB profiles, locks production (`prd`/`prod`) databases as Read-Only by default, opens massive collections instantaneously with Zero-Overhead preview & Lazy view rendering, and works 100% natively with both **MongoDB** and **AWS DocumentDB**.

---

## Key Features

### 1. Zero-Overhead Performance & Instant UI-First Workspace
- **Zero-Overhead Initial Open**: Opening a collection without a `Filter` or `Sort` skips `explain()` and `listIndexes()` completely, displaying a neutral **`No Filter (Preview)`** badge while fetching data in a single roundtrip (`.limit(N).batchSize(N)`).
- **UI-First Instant Tab Open & Single-Flight Control**: Clicking a collection immediately opens/focuses the tab on the EDT with an animated loading spinner; rapid clicks automatically cancel stale in-flight requests (`AtomicLong` generation + `Future.cancel(true)`) so there is never lag or response overlap.
- **Lazy View Rendering & Safe Display Truncation**: Builds only the currently active view (`Tree`, `Table`, or `JSON`) off the EDT; inactive views build lazily on demand. Huge strings, giant arrays, and binary blobs are safely truncated for display (`... (truncated)`) while preserving 100% intact raw BSON for Copy, Edit, Diff, and Export.
- **60s Metadata Caching & Safe Error Isolation**: Caches O(1) `estimatedDocumentCount()` and `listIndexes()` for 60s per collection (invalidated on manual Refresh or document edits), removes slow `EXECUTION_STATS` scans (`QUERY_PLANNER` + 1,500ms `maxTimeMS` only), and isolates View / restricted-permission metadata errors safely.

### 2. Execution Plan Detection & 100% Read-Only Index Inspector / Filter Guide
- **Accurate MongoDB & AWS DocumentDB `explain()`**: When a `Filter` or `Sort` is specified, asynchronously evaluates `QUERY_PLANNER` plans (`winningPlan`, `SUBSCAN`, `IXONLY`, `$cursor`) and renders **`IXSCAN`**, **`COLLSCAN`**, or **`UNKNOWN`** (with failure summary tooltip).
- **Strictly Read-Only Index Viewer**: Click the execution plan badge (`IXSCAN` / `COLLSCAN` / `No Filter (Preview)` / `UNKNOWN`) or right-click any collection in the Connections tree → **`Inspect Indexes & Query Guide...`**.
  - View index names, key specs, attributes (`UNIQUE`, `TTL`, `SPARSE`), and sizes.
  - Click **`Apply Filter Template from Index Keys`** to populate the `Filter` editor in one click.
  - Review query diagnostics comparing active `Filter`/`Sort` fields against existing indexes, plus a **copy-only ESR (`Equality → Sort → Range`) `createIndex` recommendation** for DBA/ops review (never writes to DB).

### 3. IntelliJ New UI Native Context Menu (`AllIcons`) & Multi-Selection Smart Quick Filter
Right-click any cell/node in **Tree** or **Table** view (preserving `Ctrl`/`Shift` multi-selection) to open a crisp IntelliJ New UI native popup menu organized into **5 logical groups** with 16×16 `AllIcons`:
1. **Dynamic Quick Filter & Sort** *(merges cleanly into existing Filter without erasing unrelated conditions)*:
   - **Single scalar**: `Filter by This Value (=)` / `Exclude This Value (!=)`
   - **Same field + 2+ values**: `Filter by N Selected Values ($in)` / `Exclude N Selected Values ($nin)`
   - **Same field + 2 Date/Timestamp values**: `Filter by Selected Date Range ($gte ~ $lte)` / `Match Selected 2 Dates ($in)`
   - **2+ different fields**: `Match All N Selected Conditions (AND)` / `Match Any of N Selected Conditions ($or)` *(safely wraps existing `$or` in `$and`)*
   - **Nested Object / Array**: `Field Exists ($exists: true)` / `Field Empty or Missing`
   - **Quick Sort & UTC Time Range**: `Sort by This Field ▶ Ascending (1) / Descending (-1)` and `ObjectId`/`Date` KST+UTC `$gte` / `$lte` filters.
2. **Edit**: **`Edit Data...`** (Key-Value + Raw JSON editor with unsaved diff review).
3. **Copy Submenu**: `Copy Value Only` | `Copy Key:Value (JSON)` | `Copy Field Path`.
4. **Relational Navigation**: `Find in Referenced Collection (New Tab)...` and `Join with Another Collection...`.
5. **Document Diff**: Compare 2 selected documents or pin a cross-tab/cross-environment **Baseline**.

### 4. AWS DocumentDB-Compatible Multi-Join View (`A ⋈ B ⋈ C`)
- **Star Join & Chain Join**: Join multiple collections (`A → B`, `A → C`, `A → B → C`) using standard `localField`/`foreignField` `$lookup` + `.allowDiskUse(true)` (zero `let`/`pipeline`, zero `$facet`).
- **Array-of-Objects Join Keys**: Automatically traverses nested Objects and `Array<Object>` fields to offer dot-notation keys (e.g. `items.product_id`) in join key and `WHERE AND` condition dropdowns.
- **Deterministic Post-Join Paging & Limit Modes**: Runs Base `$match` → Deterministic `$sort` (`_id` tie-breaker) → `$lookup`/exclusion `$match` → single post-join `$skip` / `$limit(N + 1)` → `$project`. Supports **`20` / `50` / `100` / `500` / `Unlimited (All, 5,000 safety cap)`**.
- **Live Edit & Pipeline Copy**: Update join specs in-place via **`Edit Join`**, copy runnable pipeline JSON via **`Copy Pipeline`**, and inspect `collection.field` origins in Table headers & Tree nodes.

### 5. Clear Mode/View Navigation, Zero-Config YAML, Aggregation Builder & AES Decryption
- **Intuitive Navigation**: Bottom-accent mode tabs (`Documents (Find)` / `Aggregations` / `Mongo Shell`) and a rounded icon segmented control for **`Tree` | `Table` | `JSON`**.
- **Zero-Config Spring Boot YAML & PRD Lock**: Auto-discovers single/multi-document `application*.yml` with `${VAR:default}` & `.env` resolution and automatic **`READ-ONLY 🔒`** protection for `prd`/`prod`.
- **Bidirectional Aggregation Builder & Mongo Shell**: Sync visual stage cards ↔ raw JSON script, execute `mongosh` scripts with `{{variables}}` (`{{$now}}`, `{{$today}}`, `{{$objectId}}`), and decrypt AES fields temporarily with auto-masking countdown.

---

## Installation

### Install from Disk (GitHub Releases ZIP)
1. Download **`maekom-mongo-lens-0.2.4.zip`** from [GitHub Releases](https://github.com/maekom-hub/maekom-mongo-lens/releases).
2. In IntelliJ IDEA, open **`Settings / Preferences` → `Plugins` → ⚙️ (Gear Icon) → `Install Plugin from Disk...`**.
3. Select the downloaded `.zip` file and restart the IDE.
4. Open the **`Maekom Mongo Lens`** tool window at the bottom of the IDE.

> After installation, use **`Settings` → `Check Updates`** in the tool window title bar for one-click in-app updates.

---

## Quick Start & Usage

1. **Connect Automatically (Zero-Config) or Add Custom URI**
   - Spring Boot `application*.yml` profiles are discovered automatically in the left **Connections** tree (or select **`+ Add Custom URI...`** from the profile dropdown).
   - Profiles containing `prd`/`prod` start safely in **`READ-ONLY 🔒`** mode.
2. **Instant Preview & Conditional Execution Plan**
   - Single-click any collection to open the tab instantaneously with **`No Filter (Preview)`** (zero `explain()` overhead).
   - Enter a `Filter` or `Sort` and press `Ctrl + Enter` (`Cmd + Enter`) to view real-time **`IXSCAN`** / **`COLLSCAN`** analysis. Click the badge anytime to open the Read-Only Index Inspector & Filter Guide.
3. **Multi-Selection Smart Quick Filter**
   - Hold `Ctrl` / `Shift` to select multiple cells in **Table** view (same column for `$in`/`$nin`/Date range, different columns for `AND`/`$or`) or nodes in **Tree** view, then right-click to apply filters immediately.
4. **Collection Join View (`A ⋈ B`)**
   - Right-click a key field (including array-of-objects fields like `items.product_id`) → **`Join with Another Collection...`**, pick target collection(s), `WHERE AND` conditions, columns, and Limit mode (`20`–`500` or `Unlimited`), then click **`Run in Join Tab ▶`**.

---

## Changelog (`v0.2.4`)

- **Zero-Overhead Initial Open**: Skips `explain()` and `listIndexes()` when opening collections without Filter/Sort (`No Filter (Preview)` badge); evaluates `QUERY_PLANNER` explain asynchronously only when Filter/Sort is active.
- **Driver & Cache Optimization**: Enforced single-roundtrip `.limit(N).batchSize(N)`, removed full-scan `EXECUTION_STATS` fallbacks, capped `explain`/`countDocuments` at `1,500ms` `maxTimeMS`, added 60s TTL cache for `estimatedDocumentCount()` & `listIndexes()`, and safely isolated View / permission-restricted errors.
- **UI-First Instant Tab Open & Lazy View Rendering**: Immediate tab creation/focus with loading spinner, off-EDT lazy rendering of only the active view (`Tree`/`Table`/`JSON`), display truncation (`... (truncated)`) for huge strings/arrays/binaries while preserving full raw data, and Single-Flight cancellation (`AtomicLong` + `Future.cancel(true)`).
- **IntelliJ New UI Native Context Menus**: Upgraded all Result Tree/Table and Connections Tree right-click menus to `ActionPopupMenu` (`setTargetComponent`) with full 16×16 `AllIcons` coverage and crisp native styling.

---

## Build from Source

```bash
./gradlew buildPlugin --no-daemon
```

Generated archive: `build/distributions/maekom-mongo-lens-0.2.4.zip`

---

## License

Licensed under the **Apache License 2.0**. See [LICENSE](./LICENSE) for details.
