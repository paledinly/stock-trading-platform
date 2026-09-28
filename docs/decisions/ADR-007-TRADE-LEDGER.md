# ADR-007: Trade ledger and partial-sale accounting

Date: 2026-08-18

## Decision

BUY/SELL 거래 기록은 종목별 이동평균 원가로 실현손익을 계산한다. 매도 수량은 이전 기록으로 재구성한 보유 수량을 초과할 수 없다. 가격과 금액은 BigDecimal을 사용하고 서버가 가격과 수량으로 금액을 계산한다.

보유 기간은 현재 포지션을 연 최초 매수부터 시작하며 잔고가 0이 되면 초기화한다. 시각은 UTC instant로 저장하고 클라이언트에서 Asia/Seoul로 표시한다.

## Partial sales and future extension

부분 매도는 남은 이동평균 원가를 바꾸지 않고 수량만 줄인다. 현재 MVP는 증권사 주문·체결, 수수료, 세금, 계좌이체나 tax-lot 선택을 모델링하지 않는다. 이를 추가할 때는 기존 거래원장을 재작성하지 않고 order/execution/position-lot 기록을 별도로 도입한다.
