# 2026-10-08 Intraday Diagnosis

This audit describes the read-only snapshot at 14:01 KST, not the final session result.
Start with [DIAGNOSIS.md](DIAGNOSIS.md). CSV tables and summary.json are derived evidence;
they do not establish profitability or approve a strategy change.

Git contains the diagnosis, derived CSV summaries, collection status snapshots, and
ExportEvidence.java, analyze.py, report.py. Raw database/tick TSV exports, the local ID
bounds, console output, and Python caches remain local and are ignored. Do not commit
.env or credentials. A fresh checkout needs the corresponding raw exports to rerun
the Python analysis; its original snapshot cannot be recreated from a changed database.

ExportEvidence.java reads project-local .env without printing credentials and queries
PostgreSQL in a read-only repeatable-read transaction. It requires Java 21 and the
project PostgreSQL JDBC driver. analyze.py requires Python, pandas, and NumPy;
report.py builds the Markdown report from its derived outputs. Re-exporting later
replaces the snapshot artifacts, so use a separate dated directory for a new audit.

Checks cover timestamp units, duplicate input IDs, recommendation counts, excursion
formula examples, and reconstruction of recorded holding MFE/MAE for valid completed
positions. These are data consistency checks, not OOS strategy validation.
