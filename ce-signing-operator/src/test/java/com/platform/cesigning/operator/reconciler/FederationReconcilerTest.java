// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler;

import static org.junit.jupiter.api.Assertions.*;

import com.platform.cesigning.operator.crd.ClusterFederationConfig;
import com.platform.cesigning.operator.crd.ClusterFederationConfigSpec;
import com.platform.cesigning.operator.crd.ClusterFederationConfigStatus;
import com.platform.cesigning.operator.crd.RemoteClusterEntry;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import java.util.List;
import org.junit.jupiter.api.Test;

class FederationReconcilerTest {

    @Test
    void isAllSyncedReturnsTrueWhenNoRemoteClusters() {
        var reconciler = new FederationReconciler();
        assertTrue(reconciler.isAllSynced(), "No remote clusters should mean synced");
    }

    @Test
    void clusterFederationConfigCrdStructure() {
        var config = new ClusterFederationConfig();
        config.setMetadata(new ObjectMeta());
        config.getMetadata().setName(ClusterFederationConfig.SINGLETON_NAME);

        var spec = new ClusterFederationConfigSpec();
        spec.setRemoteClusters(
                List.of(
                        new RemoteClusterEntry("cluster-west", "cluster-west-kubeconfig"),
                        new RemoteClusterEntry("cluster-south", "cluster-south-kubeconfig")));
        config.setSpec(spec);

        assertEquals(2, config.getSpec().getRemoteClusters().size());
        assertEquals("cluster-west", config.getSpec().getRemoteClusters().get(0).getName());
        assertEquals(
                "cluster-west-kubeconfig",
                config.getSpec().getRemoteClusters().get(0).getKubeconfigSecretRef());
    }

    @Test
    void clusterFederationConfigStatusTracksPerCluster() {
        var status = new ClusterFederationConfigStatus();
        var cs1 = new ClusterFederationConfigStatus.RemoteClusterStatus();
        cs1.setName("cluster-west");
        cs1.setConnectionState("Connected");
        cs1.setEntryCount(3);

        var cs2 = new ClusterFederationConfigStatus.RemoteClusterStatus();
        cs2.setName("cluster-south");
        cs2.setConnectionState("Error");
        cs2.setError("Connection refused");

        status.setRemoteClusters(List.of(cs1, cs2));

        assertEquals(2, status.getRemoteClusters().size());
        assertEquals("Connected", status.getRemoteClusters().get(0).getConnectionState());
        assertEquals("Error", status.getRemoteClusters().get(1).getConnectionState());
        assertEquals("Connection refused", status.getRemoteClusters().get(1).getError());
    }

    @Test
    void statusConditionHelper() {
        var status = new ClusterFederationConfigStatus();
        assertTrue(status.setCondition("Ready", "True", "AllConnected", "All connected"));
        assertFalse(status.setCondition("Ready", "True", "AllConnected", "All connected"));
        assertTrue(status.setCondition("Ready", "False", "NotReady", "Not all connected"));
    }
}
