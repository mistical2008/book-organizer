# Librarian Project Architecture Refactoring Plan

This refactoring plan aligns the Librarian application with our target structural constraints, dividing the stack into **Core**, **Sandbox**, and **File Organizer** modules across both the **Clojure (Babashka)** daemon and the **TypeScript (Node/Vite)** full-stack server.

---

## 1. Architectural Definition

| Module | Scope & Core Responsibility | High-Level Constraints |
| :--- | :--- | :--- |
| **Core** | Path generation, template string parsing, API query formulations, and ISBN verification. | 🚫 **Forbidden**: Modifying files or database registries. Purely mathematical string transitions, validations, and requests. |
| **Sandbox** | Interactive dry-run simulations, pipeline telemetry previews, and diagnostic reports. | 🚫 **Forbidden**: Writing, deleting, or renaming actual files. Purely stateful virtualization & log rendering. |
| **File Organizer** | Directory synchronization, physical file movement (copy, rename, cleanup), and actual persistence. | ✅ **Required**: Exposes side-effects to organize publications securely based on Core outputs. |

---

## 2. Clojure Namespace Separation

We split the Clojure Babashka daemon (`src/clojure/librarian/`) into three clean files to respect the strict domain separations:

### A. `librarian.core` (`src/clojure/librarian/core.clj`)
Contains pure logic and configuration definitions:
- ISBN validation logic (`isbn-10?`, `isbn-13?`, `valid-isbn?`, `extract-valid-isbn`).
- Target path calculation and template substitution (`compute-destination`).
- Web request assembly and query formulations (`query-google-books`, `extract-via-gemini` string payload construction).

### B. `librarian.sandbox` (`src/clojure/librarian/sandbox.clj`)
Exposes dry-run scanning utilities:
- Traverses input directories without performing writing side-effects.
- Formulates preview logs detailing what actions *would* occur.
- Computes target path strings using `librarian.core`.

### C. `librarian.organizer` (`src/clojure/librarian/organizer.clj`)
Full file-relocation layer:
- Inherits path calculations from `librarian.core`.
- Executes actual write actions: `io/make-parents`, `io/copy`, and `io/delete-file` (cleanup).
- Persists system database states inside `data/state.json`.

---

## 3. TypeScript Full-Stack Restructuring

Similarly, we refactor the Node.js / Express backend (`/server.ts`) to import from cleanly separated modules under `/src/lib/`:

### A. `src/lib/core.ts`
- Functions: `isValidIsbn`, `extractValidIsbn`, `computeDestinationPath`, `buildGeminiRequest`.
- No filesystem writes (`fs.writeFileSync`, `fs.copyFileSync`, etc.) allowed.

### B. `src/lib/sandbox.ts`
- Performs dry-run scans.
- Simulates categorization state tables, triggering virtual OCR reads and API mock logging.
- Explements a virtual simulation run route `/api/sandbox/dry-run`.

### C. `src/lib/organizer.ts`
- Implements `runPersistentSyncPipeline`.
- Does actual folder checks (`fs.mkdirSync`, `fs.copyFileSync`, `fs.unlinkSync`).
- Triggers model requests and records final state into SQLite-like `state.json`.

---

## 4. Dual-Mode User Interface Evolution

We update `/src/components/PipelineSandbox.tsx` to provide visual toggles highlighting this architectural separation:
1. **Sandbox / Simulation Tab**: Runs pure dry-runs using Core path builders and displays step-by-step mock logs.
2. **File Organizer Module Tab**: Triggers actual server file organization syncing, displaying real directory results.
