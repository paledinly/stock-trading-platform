# 재무 요인 연구 원자료 (2026-11-09)

`audit/2026-11-09-fundamental-study/data/`를 압축한 파일만 담은 브랜치예요. 코드와 사전 등록·탐색 결과는 `feature/fundamental-study` 브랜치에 있어요.

- `fundamental-data.tar.xz` (약 65MB, 풀면 약 1.3GB, 파일 23,302개)
  - `data/corp.csv`: DART 회사 코드 목록
  - `data/cache/acnt/`: 2016~2026 재무제표 원본 응답(약 2,900개 회사, DART 1,091회 호출)
  - `data/cache/shares/`: 2017~2025 발행주식 수(DART 22,022회 호출)
  - `data/collect*.log`: 수집 기록
- SHA-256: `9b56dcbdc41f814c3afa62f94ef9b7a4e9a0878bb085b1be5c0cb2e57665ef0d`
- DART 인증키는 들어 있지 않아요.

## 되살리기

```bash
git fetch origin data/fundamental-study
git show origin/data/fundamental-study:audit/2026-11-09-fundamental-study/fundamental-data.tar.xz > /tmp/fundamental-data.tar.xz
cd audit/2026-11-09-fundamental-study
tar -xJf /tmp/fundamental-data.tar.xz   # data/ 폴더가 생겨요
```

그다음 `feature/fundamental-study`의 `fundamental_study.py`를 그대로 실행하면 DART를 다시 부르지 않아요. 시험 단계(`--stage test`, 2023-01~2026-10)는 아직 실행하지 않았어요.
