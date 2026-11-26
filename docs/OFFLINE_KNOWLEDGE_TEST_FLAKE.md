# OfflineKnowledgeTest intermittent failure: investigation and fix (Priority 0, 2026-11-26)

**Status: root cause established; fix verified by 40 of 40 independent CI runs.** Device checks are listed at the end and are NOT RUN.

## 1. Symptom
`OfflineKnowledgeTest` failed on GitHub Actions now and then (twice in normal PR CI; a rerun passed). The failing assertion was `pack installs`. The old `SqliteOfflineKnowledge.install()` wrapped everything in `runCatching`, so the real error was swallowed and the report showed only `AssertionError: pack installs`.

## 2. Better failure reporting (kept in the product code)
- `install()` now records `lastFailure`: install stage (MANIFEST, DIR, SOURCE_OPEN, COPY, VERIFY, RENAME, CLEANUP, OPEN, INFO), exception class, a short ASCII message, up to 4 causes and 5 stack frames, plus a state snapshot. The snapshot holds bytes copied vs. the manifest size, whether the target and temp files exist and their sizes, the knowledge folder listing (file names and sizes only), free space, heap max/total/free, thread name and interrupt flag, elapsed time and active thread count. It never includes user data, VINs, keys or paths outside the app's own `knowledge` folder.
- `VirtualMachineError` (for example `OutOfMemoryError`) is recorded and then rethrown, not swallowed.
- The test assertion message now prints `lastFailure`. The assertion itself is unchanged.
- The app's connection log records the failed stage and error class (`failStage`) when the pack can't install.

## 3. Reproduction
Throwaway branch `diag/offline-knowledge-flake` (never merged): CI could be started many times in parallel, test-worker GC logs were kept, and runner resources were printed.
- **25 independent runs on unchanged code: 3 failed** (45740226636, 45740197506, 45740179722). All 3 show `java.lang.OutOfMemoryError: Java heap space` inside Robolectric's `Asset$_CompressedAsset.getBuffer`, called from the pack install.
- GC logs: every run had `Heap Max Capacity: 512M` (Gradle's default for test workers; `org.gradle.jvmargs` only sizes the daemon). Heap occupancy after young GCs reached **394 to 431 MB of 512 MB** in the failing runs.
- Locally, the same OutOfMemoryError was forced by giving the test JVM 1 CPU and a small heap. The exact CI setup (512 MB, 4 CPUs) did not fail locally in the attempts made; CI was the reliable reproducer.

## 4. Root cause
Under Robolectric, every read of the bundled pack (`penelope_knowledge.db.gz`, about 41 MB once inflated) loads the **whole inflated asset into one heap array**. That array stays reachable through a finalizable `Asset` until the finalizer thread runs and a later GC frees it. JUnit creates a new test instance per test, so the 21 tests in `OfflineKnowledgeTest` run about 17 installs. On a busy runner the finalizer lags, several 41 MB arrays pile up, and a new 41 MB array (a G1 *humongous* allocation, which needs contiguous free regions) no longer fits in 512 MB. This happened in about 1 run in 8.

It is **test-environment only**. On a phone the asset is streamed from the APK, not loaded whole into the heap, and the install runs once per pack version. There is no evidence of a production race, file-locking problem or corrupt pack. The other suspects were checked and ruled out: temp folders, parallel test execution, database initialization, leftover files from earlier tests, disk space (runner `df`/`free` printed in every run), and the pack content and checksum.

## 5. Corrective changes
1. **Root-cause fix:** `app/build.gradle.kts` sets `maxHeapSize = "1g"` for unit-test JVMs, with the evidence in a comment. A new guard test, `the test JVM keeps the heap that Robolectric's whole-asset reads need`, fails if that setting is removed or lowered below 900 MB. Nothing is skipped, retried or weakened.
2. **Resource cleanup:** `OfflineKnowledgeTest` closes its SQLite connection after each test (`@After`). Before, every test left one open. This alone did not reduce the heap retention (measured), so it is hygiene, not the fix.
3. **Installer hardening** (`KnowledgePackInstaller`, no Android imports, fully unit-tested). These close latent production risks found during the audit; none caused the CI failure:
   - one process-wide lock, so two installs can't race on the same files;
   - a unique temp file per attempt (`File.createTempFile`), and stale `installing*.tmp` files are removed under the lock;
   - an existing installed pack is reused only if its size matches `database_bytes` and it really opens (`SELECT count(*) FROM sqlite_master`). A truncated or corrupt file is deleted and reinstalled once;
   - old pack versions (and their `-journal`, `-wal`, `-shm` files) are deleted only after the new pack is verified and renamed into place. The current target is never deleted;
   - `OutOfMemoryError` is not caught; the lock is released and no temp file is left behind.

The offline knowledge content, voice personalities and voice code were not touched.

## 6. Regression tests
- `KnowledgePackInstallerTest` (12): 8 threads × 25 concurrent installs; truncated and same-size-corrupt target replaced; manifest without `database_bytes`; stale temp removed; **restart reuse** (pack not reopened, file mtime unchanged); **update** replaces the old pack only after verification; VERIFY failure keeps the old file and leaves no temp; COPY and SOURCE_OPEN failures report the stage and copied byte count; an OOM propagates and still leaves no temp file.
- `InstallFailureTest` (2): a missing manifest records its stage and error without throwing; a checksum mismatch records the VERIFY stage and byte counts, never raw bytes.
- `OfflineKnowledgeTest`: the heap guard test above. It fails on the old 512 MB default.

## 7. Verification evidence
Branch `verify/offline-knowledge-fix` = the fix + the same throwaway diagnostics (never merged). **40 independent CI runs (45745389663 to 45745642457): 40 passed, 0 failed.** Each run's artifact was checked, not just its green tick:
- `OfflineKnowledgeTest` ran in every run: 21 tests, 0 failures, 0 errors, 0 skipped. The suite had 1302 tests, 0 failed.
- `Heap Max Capacity: 1G` in every GC log.
- No allocation-failure Full GC and no evacuation failure in any run. The only Full GCs were explicit `System.gc()` calls, and the live set after them was 156 to 184 MB, the same as before the fix (so there is no leak).
- Heap occupancy after young GCs reached up to **755 MB in 10 of 40 runs**, which would not fit in the old 512 MB heap. This is occupancy, not a measured minimum need, because G1 grows into the space it has. Still, it shows the 512 MB default had no real margin.
- If the fix did nothing, 40 clean runs in a row at the measured 12 % failure rate would happen by luck about 0.6 % of the time (0.88^40).

A green rerun was never taken as proof. The conclusion rests on the stack traces, the GC logs and the 40-run sample.

## 8. Not covered / device checks (NOT RUN)
On the Galaxy S26 Ultra (phone only, no car or adapter needed):
- first launch after install: the pack installs, and an offline answer (e.g. "what does P2096 mean?") works in airplane mode;
- force-stop and reopen: the pack is reused, with no new install line in the connection log;
- in-place APK update (same pack): still available; with a changed pack, the old file is removed only after the new one opens;
- Settings > Connection log shows `Offline knowledge available=true` and no `failStage`.
