# Penelope development prompt & merge log

Record each development prompt, its intended changes, the implementation branch/PR, and whether the code actually reached its target branch. **Writing a prompt is not the same as merging code.**

## Status definitions
- **Prompt drafted** — instructions exist, but code has not been verified.
- **In progress** — implementation is underway; no PR yet.
- **PR open** — code proposed but not merged.
- **Merged** — PR merge verified against the named target branch.
- **Closed (unmerged)** — proposed code was not merged.

## Entries

| Date (AZ) | Prompt / feature | Changes | Target branch | PR | Status | Tests |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-11-20 | Build identity + clear PR summaries | Show the source revision in the app footer and About build information; add a structured PR template and prompt/merge ledger | `main` | [#44](https://github.com/shelmichael29/Ai-companion-app/pull/44) | PR open — **not merged** | CI pending; device verification pending |

## Checklist for every new development prompt
1. Add a dated entry when the prompt is created. State the requested changes and intended target branch. Use **Prompt drafted** until implementation is verified.
2. Link the feature branch and PR once created. Use **PR open** until GitHub confirms a merge.
3. Record CI/device testing (passed, failed, pending, or not run).
4. After a merge, verify its target branch and update the status to **Merged**. If closed without merge, record **Closed (unmerged)**.
5. In the assistant's user-facing message, repeat a brief summary: changes, PR link, test status, and **merged: yes/no**.
6. Keep each project/branch distinct. Merging a tester branch is **not** the same as merging into `main`.

This is a manually maintained project ledger. A PR template does not automatically update the ledger when a PR merges.
