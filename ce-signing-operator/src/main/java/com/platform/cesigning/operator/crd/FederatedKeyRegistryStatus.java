// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

public class FederatedKeyRegistryStatus {

    private String lastSyncTime;
    private int entryCount;

    public String getLastSyncTime() {
        return lastSyncTime;
    }

    public void setLastSyncTime(String lastSyncTime) {
        this.lastSyncTime = lastSyncTime;
    }

    public int getEntryCount() {
        return entryCount;
    }

    public void setEntryCount(int entryCount) {
        this.entryCount = entryCount;
    }
}
