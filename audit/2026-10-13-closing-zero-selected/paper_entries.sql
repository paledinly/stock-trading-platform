select pe.run_id, pe.stock_code, pe.status, pe.completed_at, pe.entered_at
from closing_paper_entry pe order by pe.run_id, pe.stock_code
