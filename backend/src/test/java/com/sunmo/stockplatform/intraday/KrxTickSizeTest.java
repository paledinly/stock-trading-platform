package com.sunmo.stockplatform.intraday;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KrxTickSizeTest {
    @Test void bandsFollowThePost2023StockTable() {
        assertThat(KrxTickSize.at(1_999)).isEqualTo(1);
        assertThat(KrxTickSize.at(2_000)).isEqualTo(5);
        assertThat(KrxTickSize.at(4_995)).isEqualTo(5);
        assertThat(KrxTickSize.at(5_000)).isEqualTo(10);
        assertThat(KrxTickSize.at(20_000)).isEqualTo(50);
        assertThat(KrxTickSize.at(50_000)).isEqualTo(100);
        assertThat(KrxTickSize.at(199_900)).isEqualTo(100);
        assertThat(KrxTickSize.at(200_000)).isEqualTo(500);
        assertThat(KrxTickSize.at(500_000)).isEqualTo(1_000);
        assertThat(KrxTickSize.at(1_522_000)).isEqualTo(1_000);
        assertThat(KrxTickSize.at(0)).isNaN();
    }

    @Test void roundsToValidQuotesIncludingAcrossBandBounds() {
        assertThat(KrxTickSize.round(238_740, false)).isEqualTo(238_500);
        assertThat(KrxTickSize.round(238_740, true)).isEqualTo(239_000);
        assertThat(KrxTickSize.round(20_030, false)).isEqualTo(20_000);
        assertThat(KrxTickSize.round(19_995, true)).isEqualTo(20_000);
        assertThat(KrxTickSize.round(11_920, false)).isEqualTo(11_920);
        assertThat(KrxTickSize.round(2_147.5, false)).isEqualTo(2_145);
    }
}
