/*
 * 투자 노트에 붙는 매매 이유 한 개(이유 코드 + 직접 쓴 설명)를 정의하는 파일이에요.
 * 독립 테이블 행이 아니라 InvestmentJournal에 딸린 값으로 trade_reason 테이블에 저장돼요.
 * InvestmentJournal, TradeService, TradeResponse가 사용해요.
 */
package com.sunmo.stockplatform.trade.domain;

import jakarta.persistence.*;

/**
 * 매매 이유 한 개를 담는 값 객체예요(@Embeddable: 다른 엔티티 안에 끼워 넣어 저장되는 조각).
 * 메모지에 붙이는 분류 스티커처럼 "뉴스", "차트" 같은 분류 코드와, 필요하면 짧은 손글씨 설명을 함께 적어요.
 */
@Embeddable
public class TradeReason {
    @Column(name = "reason_code", length = 40)
    private String code;
    @Column(name = "custom_reason", length = 200)
    private String customReason;

    /** JPA가 DB에서 읽은 값을 채울 때 쓰는 빈 생성자예요. */
    protected TradeReason() {
    }

    /**
     * 매매 이유를 만들어요.
     * 받는 것: 이유 코드, 직접 쓴 설명(없으면 null). 돌려주는 것: 새 이유 객체.
     */
    public TradeReason(String code, String customReason) {
        this.code = code;
        this.customReason = customReason;
    }

    // 아래는 저장된 값을 읽어 가는 조회용 메서드(getter)예요.

    public String getCode() {
        return code;
    }

    public String getCustomReason() {
        return customReason;
    }
}
