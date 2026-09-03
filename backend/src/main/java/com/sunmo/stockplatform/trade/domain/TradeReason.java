package com.sunmo.stockplatform.trade.domain;

import jakarta.persistence.*;

@Embeddable
public class TradeReason {
    @Column(name = "reason_code", length = 40)
    private String code;
    @Column(name = "custom_reason", length = 200)
    private String customReason;

    protected TradeReason() {
    }

    public TradeReason(String code, String customReason) {
        this.code = code;
        this.customReason = customReason;
    }

    public String getCode() {
        return code;
    }

    public String getCustomReason() {
        return customReason;
    }
}
