# Git 연결 및 작업 인수인계

이 문서는 다른 Codex 세션이나 에이전트가 프로젝트의 Git 저장소에 안전하게 연결하고 현재 작업 상태를 이어가기 위한 안내서다.

## 저장소 정보

- GitHub 저장소: `https://github.com/paledinly/stock-trading-platform`
- Git 원격 주소: `https://github.com/paledinly/stock-trading-platform.git`
- 공개 범위: 비공개(Private)
- 기본 브랜치: `main`
- 원격 이름: `origin`
- 최신 커밋 확인: `git log -1 --oneline`
- 로컬 작업 폴더: `D:\sunmo\codexApp\stock-trading-platform`

비공개 저장소이므로 GitHub 계정 `paledinly` 또는 해당 저장소에 권한을 부여받은 계정으로 인증해야 한다. 토큰, 비밀번호, KIS 키는 이 문서나 Git 파일에 기록하지 않는다.

## 같은 PC와 작업 폴더를 사용하는 경우

별도로 clone하지 말고 기존 폴더를 사용한다.

```powershell
cd D:\sunmo\codexApp\stock-trading-platform
git remote -v
git branch --show-current
git status --short
```

정상 상태에서는 `origin`이 다음 주소를 가리켜야 한다.

```text
https://github.com/paledinly/stock-trading-platform.git
```

원격 주소가 없는 경우에만 다음 명령으로 연결한다.

```powershell
git remote add origin https://github.com/paledinly/stock-trading-platform.git
```

`origin`이 있지만 주소가 잘못된 경우:

```powershell
git remote set-url origin https://github.com/paledinly/stock-trading-platform.git
```

## 다른 PC나 새 작업 폴더에서 연결하는 경우

GitHub CLI 인증 방식을 권장한다.

```powershell
gh auth login
gh auth status
git clone https://github.com/paledinly/stock-trading-platform.git
cd stock-trading-platform
git status
```

브라우저 인증을 선택하고 저장소 접근 권한이 있는 GitHub 계정으로 로그인한다. 인증 정보나 Personal Access Token을 소스 파일에 저장하지 않는다.

## 반영 범위와 현재 상태 확인

Phase 1~8 소스, Phase 7 분석 기능, Flutter 모바일 소스와 생성된 플랫폼 프로젝트, 보고서 및 이 인수인계 문서가 `main`에 반영되는 구성이다.

- 실제 비밀값이 들어 있는 `.env`는 Git에서 무시된다.
- Flutter 정적 분석과 위젯 테스트는 통과했다.
- Debug APK 빌드에는 Android SDK NDK `28.2.13676358` 설치가 필요하다.
- 로컬 변경 상태는 문서의 고정 설명보다 실시간 `git status` 결과를 우선한다.

현재 상태와 원격 차이를 확인하는 명령:

```powershell
git status --short
git status -sb
git log -1 --oneline
git diff --cached --stat
git diff --stat
git check-ignore -v .env
```

다른 세션이나 에이전트는 기존 변경을 먼저 확인하고, 사용자 지시 없이 reset, restore 또는 checkout으로 작업을 덮어쓰지 않는다.
## 비밀값 보호 규칙

- `.env`를 절대 스테이징하거나 커밋하지 않는다.
- `KIS_APP_KEY`, `KIS_APP_SECRET`, 계좌번호, GitHub 토큰 값을 출력하거나 문서에 복사하지 않는다.
- `.env.example`에는 변수 이름과 비어 있는 예시값만 둔다.
- 커밋 전에 staged 파일을 대상으로 비밀값을 검사한다.

예시 검사:

```powershell
git diff --cached --name-only
git diff --cached | Select-String -Pattern 'KIS_APP_KEY|KIS_APP_SECRET|ghp_|github_pat_|BEGIN (RSA|OPENSSH|PRIVATE) KEY'
git check-ignore -v .env
```

검색 결과에 실제 값이 보이면 commit이나 push를 중단하고 먼저 제거한다.

## 권장 작업 절차

1. `git status --short`로 기존 변경을 확인한다.
2. 현재 사용자의 요청 범위에 해당하는 파일만 수정한다.
3. 관련 테스트를 실행한다.
4. `git diff --check`로 공백 및 패치 오류를 검사한다.
5. 사용자가 명시적으로 지시한 파일만 스테이징한다.
6. 비밀값 검사를 수행한다.
7. commit과 push는 사용자가 별도로 지시한 경우에만 수행한다.

원격 상태 확인이 필요한 경우 기존 로컬 변경을 건드리지 않는 다음 명령부터 사용한다.

```powershell
git fetch origin
git status -sb
git log --oneline --decorate --max-count=10 --all
```

로컬 변경이 남아 있는 상태에서는 사용자 승인 없이 pull, rebase, merge를 수행하지 않는다.

## 주요 확인 문서

- `docs/DEVELOPMENT_HANDOFF.md` — 현재 미커밋 Phase 1·2A와 이후 개발 순서
- `docs/PHASE_1_DATA_RELIABILITY_REPORT.md`
- `docs/PHASE_2A_PERFORMANCE_RELIABILITY_REPORT.md`
- `docs/PHASE_7_REPORT.md`
- `docs/PHASE_8_REPORT.md`
- 루트 설계 문서 및 README

작업을 시작할 때 이 문서와 `git status --short`의 실시간 결과가 다르면 실시간 Git 상태를 우선하며, 차이점을 사용자에게 알린다.

## 최근 개발 반영

2026-09-02 기준 Phase 1 데이터 신뢰성과 Phase 2A 성과 신뢰성 구현은 다음 기능 커밋으로 정리됐다.

```text
6737bc7 feat: harden market data and performance recovery
```

세부 변경, 검증 상태, Phase 2B 이후 순서는 `docs/DEVELOPMENT_HANDOFF.md`를 확인한다.
