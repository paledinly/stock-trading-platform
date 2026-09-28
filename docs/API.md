# API Guide

Backend의 REST/SSE endpoint는 Controller mapping이 기준이다. 이 문서는 영역과 사용 원칙만 설명하며 정확한 request/response 필드는 DTO와 Web 호출 코드를 함께 확인한다.

## 주요 영역

- 종목 검색과 현재가
- 관심종목 그룹/항목
- 5분봉과 과거 데이터 준비
- 실시간 상태와 SSE 스트림
- 스캐너 설정, 탐지, 성과
- 시장 전체 스캔과 후보
- 마감 추천 생성, 후보 평가, 조회
- 익일 성과와 보유/매도 판단
- 백테스트와 전략 분석
- 수동 거래 및 투자 기록

## 원칙

- 날짜와 시각의 timezone 의미를 보존한다.
- 빈 목록과 undefined payload를 구분한다.
- 추천 생성 API는 실제 주문 API가 아니다.
- 점수는 확률로 표시하지 않는다.
- 데이터 품질, 제외 사유, 실행 가정을 결과와 함께 전달한다.
- KIS 오류 원문이나 인증정보를 응답에 노출하지 않는다.

API 변경 시 Controller, DTO, Web client와 테스트를 함께 수정한다. 사용자에게 보이는 enum/영문 reason은 서버 원본을 보존하되 UI 번역 mapping으로 표시한다.
