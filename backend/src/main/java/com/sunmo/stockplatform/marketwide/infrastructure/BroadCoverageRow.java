package com.sunmo.stockplatform.marketwide.infrastructure;

import com.sunmo.stockplatform.marketwide.domain.*;

/** Coverage never needs quote, ranking JSON or source observations. */
public interface BroadCoverageRow {
    Long getStockId();
    BroadSnapshotQuality getDataQuality();
    BroadSnapshotStatus getCollectionStatus();
    boolean getActive();
    boolean getManaged();
    boolean getTradingHalted();
    boolean getEtf();
    boolean getEtn();
}
