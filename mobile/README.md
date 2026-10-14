# Mobile Client

Flutter 기반 보조 클라이언트다. 핵심 운영 화면과 전략 검증 기능(마감 추천, 시장 전체 스캔, 백테스트 등)은 Web에만 있고, Mobile은 아래 화면만 제공한다. 2026-09-28 이후 변경되지 않았다.

| 화면 | 사용하는 API |
| --- | --- |
| 홈(관심종목, Momentum 탐지 요약) | `GET /api/v1/watchlists`, `GET /api/v1/scanner-detections` |
| 관심종목(그룹 생성, 종목 추가·삭제) | `/api/v1/watchlists`, `/api/v1/watchlist-groups`, `/api/v1/stocks/search` |
| 실시간 탐지·시장 Scanner(`VOLUME`/`PRICE_RISE`/`MOMENTUM`) | `GET /api/v1/scanner-detections?type=`, SSE `GET /api/v1/stream` |
| 투자 노트(수동 거래 기록) | `GET`/`POST /api/v1/trades` |

주요 의존성: `dio`(HTTP), `flutter_riverpod`(상태 관리), `go_router`(화면 이동). 자세한 버전은 `pubspec.yaml`을 본다.

## 실행

```powershell
flutter pub get
flutter run
```

Backend 주소는 빌드 시 `API_BASE_URL`로 정한다. 기본값은 `http://10.0.2.2:8080`(Android 에뮬레이터에서 본 PC의 localhost)이다.

```powershell
flutter run --dart-define=API_BASE_URL=http://<backend-host>:8080
```

참고: `INTERNET` 권한은 `android/app/src/debug`·`profile`의 manifest에만 있고 `main`에는 없다. 따라서 Android 릴리스 빌드는 그대로는 Backend에 접속하지 못한다.

## 테스트

```powershell
flutter test
```

공통 도메인 규칙과 Backend 실행 방법은 [루트 README](../README.md)와 [docs](../docs/ARCHITECTURE.md)를 기준으로 한다.
