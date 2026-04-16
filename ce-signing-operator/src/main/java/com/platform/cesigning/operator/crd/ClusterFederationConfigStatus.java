// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

import io.fabric8.kubernetes.api.model.Condition;
import java.util.ArrayList;
import java.util.List;

public class ClusterFederationConfigStatus {

    private List<Condition> conditions = new ArrayList<>();
    private List<RemoteClusterStatus> remoteClusters = new ArrayList<>();
    private Long observedGeneration;

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }

    public List<RemoteClusterStatus> getRemoteClusters() {
        return remoteClusters;
    }

    public void setRemoteClusters(List<RemoteClusterStatus> remoteClusters) {
        this.remoteClusters = remoteClusters;
    }

    public Long getObservedGeneration() {
        return observedGeneration;
    }

    public void setObservedGeneration(Long observedGeneration) {
        this.observedGeneration = observedGeneration;
    }

    public boolean setCondition(String type, String status, String reason, String message) {
        return StatusConditionHelper.setCondition(conditions, type, status, reason, message);
    }

    public static class RemoteClusterStatus {
        private String name;
        private String connectionState;
        private String lastSyncTime;
        private int entryCount;
        private String error;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getConnectionState() {
            return connectionState;
        }

        public void setConnectionState(String connectionState) {
            this.connectionState = connectionState;
        }

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

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }
    }
}
