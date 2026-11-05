"""The only way research code reads investor_daily_flow (README.md): the test window 2024-11-01..2026-10-30 stays sealed
unless a committed pre-registration document is named.

    from sealed import load_flows
    flows = load_flows(c)                                   # discovery window only (trade_date <= 2024-10-31)
    flows = load_flows(c, prereg='audit/<folder>/README.md')  # also the test window; the file must be committed
"""
import datetime as dt
import subprocess
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[2]
DISCOVERY_END = dt.date(2024, 10, 31)
TEST_END = dt.date(2026, 10, 30)
COLUMNS = ('stock_id', 'trade_date', 'foreign_qty', 'institution_qty', 'individual_qty', 'foreign_amount',
           'institution_amount', 'individual_amount', 'securities_amount', 'trust_amount', 'private_fund_amount',
           'bank_amount', 'insurance_amount', 'merchant_bank_amount', 'pension_amount', 'other_corp_amount')


def committed(path):
    """True when `path` (relative to the repository root) is tracked and has no uncommitted change."""
    rel = Path(path).as_posix()
    tracked = subprocess.run(['git', 'ls-files', '--error-unmatch', rel], cwd=ROOT, capture_output=True).returncode == 0
    clean = subprocess.run(['git', 'diff', '--quiet', 'HEAD', '--', rel], cwd=ROOT).returncode == 0
    return tracked and clean


def load_flows(c, prereg=None, columns=COLUMNS):
    """DataFrame of investor_daily_flow up to the discovery end, or up to the test end with a committed prereg.
    `columns` picks a subset of COLUMNS to keep memory down."""
    if prereg is None:
        end = DISCOVERY_END
    elif committed(prereg):
        end = TEST_END
    else:
        raise PermissionError(f'test window sealed: {prereg} is not a committed pre-registration (README.md > 규칙 3)')
    columns = tuple(columns)
    if not set(columns) <= set(COLUMNS):
        raise ValueError(f'unknown columns: {sorted(set(columns) - set(COLUMNS))}')
    rows = c.execute(f"select {', '.join(columns)} from investor_daily_flow where trade_date <= %s", (end,)).fetchall()
    return pd.DataFrame(rows, columns=columns)
