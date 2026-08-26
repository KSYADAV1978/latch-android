# Parser corpus

NFR-502 requires a test corpus of no fewer than **300** real-world input strings with
expected outputs. This directory holds it. Every `.tsv` file here is picked up automatically
by `CorpusTest`; adding a case means adding a line, never editing a test.

**Current size: a seed of ~90 cases, not the required 300.** The remainder should come from
real captures — school circulars, payment reminders, courier SMS, appointment confirmations —
rather than from invented strings, which is why the file is not padded out here.

## Format

Tab-separated, four columns. `#` starts a comment line, blank lines are ignored.

| Column | Meaning |
|---|---|
| `input` | The captured text, exactly as it would arrive |
| `classification` | `EVENT`, `TASK_WITH_DUE_DATE`, `EVENT_INCOMPLETE`, `TASK_UNDATED` (FR-506) |
| `date` | Expected ISO date of the primary candidate, or `-` |
| `time` | Expected 24-hour `HH:MM` of the primary candidate, or `-` |

All cases are evaluated against a fixed clock: **2026-08-25T09:00, a Tuesday**, zone
`Asia/Kolkata`, date order `DD/MM`. Relative expectations are written out against that date,
so the corpus never drifts with the real calendar.
