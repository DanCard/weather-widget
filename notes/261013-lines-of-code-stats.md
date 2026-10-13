# Codebase Lines of Code Statistics

**Date:** 2026-10-13  
**Scope:** Git-tracked files in the `weather-widget` repository  

---

## Executive Summary

- **Total Git-Tracked Lines:** 467,499 across 3,159 files
- **Total Executable Code Lines (SLOC):** 355,903
- **Kotlin Source Code (Production + Tests):** 196,740 code lines (252,213 total lines) across 1,433 files
- **Test-to-Production Code Ratio:** **1.29 : 1** (110,661 test code lines vs. 86,079 production code lines)
- **Total Test Cases:** **5,329 `@Test` methods** across 783 test classes

---

## High-Level Category Grouping

| Category Group | Files | Total Lines | Blank Lines | Comment Lines | Code (SLOC) | % of Code |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **1. Tests (Kotlin)** | 790 | 136,482 | 15,503 | 10,318 | **110,661** | 31.1% |
| **2. Production Code (Kotlin)** | 643 | 115,731 | 8,697 | 20,955 | **86,079** | 24.2% |
| **3. Documentation & Planning (Markdown)** | 1,443 | 147,154 | 32,641 | 19,972 | **94,541** | 26.6% |
| **4. Database Schemas & Config (JSON/TOML/Props)** | 75 | 44,243 | 1 | 5 | **44,237** | 12.4% |
| **5. UI Resources (Android XML)** | 104 | 14,993 | 879 | 494 | **13,620** | 3.8% |
| **6. Automation & Maintenance Scripts (Shell/Python/SQL)** | 33 | 5,749 | 731 | 529 | **4,489** | 1.3% |
| **7. Build System (Gradle Kotlin DSL)** | 8 | 1,686 | 163 | 184 | **1,339** | 0.4% |
| **8. Other / Native / Miscellaneous (C, etc.)** | 63 | 1,461 | 210 | 314 | **937** | 0.3% |
| **TOTAL** | **3,159** | **467,499** | **58,825** | **52,771** | **355,903** | **100.0%** |

---

## Detailed Breakdown by Component

### 1. Test Code (110,661 SLOC)

| Test Suite / Category | Classes / Files | Tests (`@Test`) | Total Lines | Comments | Code (SLOC) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **`:app` Unit Tests (JVM / Robolectric)** | 375 | 2,499 | 71,660 | 4,876 | **58,361** |
| **`:shared` Unit Tests (JVM)** | 279 | 2,149 | 43,403 | 3,619 | **35,108** |
| **`:desktop` Unit Tests (Compose / JVM)** | 96 | 577 | 15,713 | 1,267 | **12,688** |
| **`:app` Instrumented Tests (`androidTest`)** | 40 | 104 | 5,706 | 556 | **4,504** |
| **Total Tests** | **790** | **5,329** | **136,482** | **10,318** | **110,661** |

### 2. Production Code (86,079 SLOC)

| Module | Files | Total Lines | Blank Lines | Comments | Code (SLOC) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **`:app` (Android Application & Widgets)** | 292 | 59,201 | 4,507 | 7,707 | **46,987** |
| **`:shared` (Core Models, Repositories & APIs)** | 246 | 35,248 | 2,816 | 9,777 | **22,655** |
| **`:desktop` (Compose for Desktop & IPC)** | 105 | 21,282 | 1,374 | 3,471 | **16,437** |
| **Total Production Kotlin** | **643** | **115,731** | **8,697** | **20,955** | **86,079** |

### 3. Test vs. Production Ratio by Module

| Module | Production SLOC | Test SLOC | Test-to-Prod Ratio |
| :--- | :---: | :---: | :---: |
| **`:app`** | 46,987 | 62,865 | **1.34 : 1** |
| **`:shared`** | 22,655 | 35,108 | **1.55 : 1** |
| **`:desktop`** | 16,437 | 12,688 | **0.77 : 1** |
| **Total Codebase** | **86,079** | **110,661** | **1.29 : 1** |

---

## Non-Source Artifacts & Infrastructure

- **Documentation & Planning (`.md`):** 147,154 total lines across 1,443 files.
  - `plans/`: 859 files (~86.7k lines)
  - `session-logs/`: 230 files (~27.3k lines)
  - `summaries/` & `notes/`: 303 files (~25.0k lines)
  - Architectural documentation (`arch/`, root notes): ~8.1k lines
- **Database Migration Schemas (`.json`):** 44,243 lines in `WeatherDatabase` Room schema exports across 80 schema versions.
- **UI Layouts & Resources (`.xml`):** 13,395 code lines (103 files in `app/src/main/res/`).
- **Automation Scripts:**
  - Shell scripts (`.sh`): 2,836 SLOC (22 files in `scripts/`)
  - Python utilities (`.py`): 1,412 SLOC (10 files)
  - SQL scripts (`.sql`): 241 SLOC (1 file)
- **Build System (`build.gradle.kts`):** 1,339 SLOC (8 files).
- **Native C Helper:** 46 SLOC (genmon panel client).
