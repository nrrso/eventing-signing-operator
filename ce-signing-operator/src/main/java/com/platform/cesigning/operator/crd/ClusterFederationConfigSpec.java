// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

import java.util.ArrayList;
import java.util.List;

public class ClusterFederationConfigSpec {

    private List<RemoteClusterEntry> remoteClusters = new ArrayList<>();

    public List<RemoteClusterEntry> getRemoteClusters() {
        return remoteClusters;
    }

    public void setRemoteClusters(List<RemoteClusterEntry> remoteClusters) {
        this.remoteClusters = remoteClusters;
    }
}
