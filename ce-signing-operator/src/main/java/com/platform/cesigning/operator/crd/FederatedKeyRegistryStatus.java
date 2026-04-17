// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

import io.fabric8.kubernetes.api.model.Condition;
import java.util.ArrayList;
import java.util.List;

public class FederatedKeyRegistryStatus {

    private String lastSyncTime;
    private int entryCount;
    private List<Condition> conditions = new ArrayList<>();
    private List<InvalidEntry> invalidEntries = new ArrayList<>();

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

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }

    public List<InvalidEntry> getInvalidEntries() {
        return invalidEntries;
    }

    public void setInvalidEntries(List<InvalidEntry> invalidEntries) {
        this.invalidEntries = invalidEntries;
    }

    public boolean setCondition(String type, String status, String reason, String message) {
        return StatusConditionHelper.setCondition(conditions, type, status, reason, message);
    }

    public static class InvalidEntry {
        private String keyId;
        private String reason;

        public InvalidEntry() {}

        public InvalidEntry(String keyId, String reason) {
            this.keyId = keyId;
            this.reason = reason;
        }

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String keyId) {
            this.keyId = keyId;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}
