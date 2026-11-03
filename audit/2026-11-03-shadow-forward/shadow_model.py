"""Freezes the shadow-Forward model (README.md): study-B settings, trained once on signal days 2018-04-01..2026-10-21.

Reads the cached study-B dataset of ../2026-11-03-ml-ranking-preregistration (.cache/datasets.pkl, built by
ml_ranking.py) and writes model.txt (LightGBM text model) and features.json (input order). Run once; never retrain
during the Forward. Run: python shadow_model.py
"""
import datetime as dt
import json
import pickle
from pathlib import Path

import lightgbm as lgb

HERE = Path(__file__).resolve().parent
CACHE = HERE.parent / '2026-11-03-ml-ranking-preregistration' / '.cache' / 'datasets.pkl'
LAST_SIGNAL = dt.date(2026, 10, 21)
PARAMS = dict(n_estimators=400, learning_rate=0.03, num_leaves=15, min_child_samples=300, subsample=0.8,
              subsample_freq=1, colsample_bytree=0.8, reg_lambda=1.0, random_state=20261103, n_jobs=8, verbose=-1)


def main():
    with open(CACHE, 'rb') as fh:
        b, cols = pickle.load(fh)['B']
    train = b[(~b['outlier']) & (b['d'] <= LAST_SIGNAL)]
    target = (train['y'] - train.groupby('d')['y'].transform('mean')).clip(-10, 10)
    model = lgb.LGBMRegressor(**PARAMS)
    model.fit(train[cols].astype('float32'), target)
    model.booster_.save_model(str(HERE / 'model.txt'))
    (HERE / 'features.json').write_text(json.dumps(cols), encoding='utf-8')
    print(f'trained on {len(train)} rows ({train["d"].min()}..{train["d"].max()}), {len(cols)} inputs -> model.txt')


if __name__ == '__main__':
    main()
