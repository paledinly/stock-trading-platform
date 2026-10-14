/*
 * 종목 이름만 보고 인버스·레버리지·선물·ETN·ETF 같은 구조화 상품인지 판별해요.
 * 전체 시장 스캔에서 일반 주식만 후보로 남기고 싶을 때 이 판별을 써요.
 * BroadCandidateCollector가 후보를 고를 때 사용해요.
 */
package com.sunmo.stockplatform.marketwide.domain;

import java.util.Locale;

/**
 * 이름에 특정 단어가 들어간 구조화 상품을 걸러 내는 도구 클래스예요.
 * 상품 이름표에 "세트"라는 글자가 보이면 따로 빼 두는 점원처럼, 이름표 글자만 보고 골라내요.
 */
public final class StructuredProductFilter {
    /** 객체를 만들 필요가 없는 도구 클래스라서 생성자를 막아 뒀어요. */
    private StructuredProductFilter() { }
    /**
     * 하는 일: 종목 이름에 인버스·레버리지·선물·ETN·ETF·2X 중 하나가 들어 있는지 확인해요.
     * 받는 것: 종목 이름(null 가능).
     * 돌려주는 것: 하나라도 들어 있으면 true, 이름이 없거나 해당 없으면 false.
     * ⚠️ 확인 필요: 단순 "포함" 검사라서 일반 회사 이름에 "선물" 같은 글자가 들어가도 걸러질 수 있어요.
     */
    public static boolean matches(String name) {
        if (name == null) return false;
        String upper = name.toUpperCase(Locale.ROOT);
        return java.util.List.of("인버스", "레버리지", "선물", "ETN", "ETF", "2X").stream()
                .anyMatch(upper::contains);
    }
}
