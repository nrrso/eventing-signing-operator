// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler;

import static org.junit.jupiter.api.Assertions.*;

import com.platform.cesigning.operator.crd.ClusterFederationConfig;
import com.platform.cesigning.operator.crd.ClusterFederationConfigSpec;
import com.platform.cesigning.operator.crd.ClusterFederationConfigStatus;
import com.platform.cesigning.operator.crd.FederatedKeyRegistryStatus;
import com.platform.cesigning.operator.crd.PublicKeyEntry;
import com.platform.cesigning.operator.crd.RemoteClusterEntry;
import com.platform.cesigning.operator.crypto.KeyPairGenerator;
import com.platform.cesigning.operator.crypto.PemValidator;
import com.platform.cesigning.operator.health.FederationReadinessCheck;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.health.Readiness;
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

    @Test
    void federationReadinessCheckDoesNotGateReadiness() {
        assertFalse(
                FederationReadinessCheck.class.isAnnotationPresent(Readiness.class),
                "FederationReadinessCheck must not have @Readiness — sync status should not gate"
                        + " pod readiness");
    }

    @Test
    void fkrStatusAllEntriesValid() throws IOException {
        var status = new FederatedKeyRegistryStatus();
        KeyPairGenerator.GeneratedKeyPair kp = KeyPairGenerator.generate();

        List<PublicKeyEntry> entries =
                List.of(
                        newEntry("key-1", kp.publicPem()),
                        newEntry("key-2", KeyPairGenerator.generate().publicPem()));

        applyValidation(status, entries);

        assertCondition(status, "Ready", "True", "Synced");
        assertCondition(status, "KeysValid", "True", "AllValid");
        assertTrue(status.getInvalidEntries().isEmpty());
        assertEquals("2 of 2 entries valid", getCondition(status, "Ready").getMessage());
    }

    @Test
    void fkrStatusSomeEntriesInvalid() throws IOException {
        var status = new FederatedKeyRegistryStatus();
        String validPem = KeyPairGenerator.generate().publicPem();

        List<PublicKeyEntry> entries =
                List.of(
                        newEntry("good-key", validPem),
                        newEntry("bad-key", "not-a-pem"),
                        newEntry("also-good", KeyPairGenerator.generate().publicPem()));

        applyValidation(status, entries);

        assertCondition(status, "Ready", "True", "Synced");
        assertCondition(status, "KeysValid", "False", "InvalidEntries");
        assertEquals(1, status.getInvalidEntries().size());
        assertEquals("bad-key", status.getInvalidEntries().get(0).getKeyId());
        assertNotNull(status.getInvalidEntries().get(0).getReason());
        assertEquals("2 of 3 entries valid", getCondition(status, "KeysValid").getMessage());
    }

    @Test
    void fkrStatusAllEntriesInvalid() {
        var status = new FederatedKeyRegistryStatus();

        List<PublicKeyEntry> entries =
                List.of(newEntry("bad-1", "garbage"), newEntry("bad-2", "also-garbage"));

        applyValidation(status, entries);

        assertCondition(status, "Ready", "True", "Synced");
        assertCondition(status, "KeysValid", "False", "InvalidEntries");
        assertEquals(2, status.getInvalidEntries().size());
        assertEquals("0 of 2 entries valid", getCondition(status, "KeysValid").getMessage());
    }

    @Test
    void fkrStatusEmptyEntries() {
        var status = new FederatedKeyRegistryStatus();

        applyValidation(status, List.of());

        assertCondition(status, "Ready", "True", "Synced");
        assertCondition(status, "KeysValid", "True", "AllValid");
        assertTrue(status.getInvalidEntries().isEmpty());
        assertEquals("0 of 0 entries valid", getCondition(status, "Ready").getMessage());
    }

    // --- helpers that mirror the reconciler's validation logic ---

    private static void applyValidation(
            FederatedKeyRegistryStatus status, List<PublicKeyEntry> entries) {
        List<FederatedKeyRegistryStatus.InvalidEntry> invalid = new ArrayList<>();
        for (PublicKeyEntry entry : entries) {
            PemValidator.Result result = PemValidator.validatePublicKeyPem(entry.getPublicKeyPEM());
            if (!result.valid()) {
                invalid.add(
                        new FederatedKeyRegistryStatus.InvalidEntry(
                                entry.getKeyId(), result.error()));
            }
        }
        status.setInvalidEntries(invalid);

        int validCount = entries.size() - invalid.size();
        String countMsg = validCount + " of " + entries.size() + " entries valid";
        status.setCondition("Ready", "True", "Synced", countMsg);
        if (invalid.isEmpty()) {
            status.setCondition("KeysValid", "True", "AllValid", countMsg);
        } else {
            status.setCondition("KeysValid", "False", "InvalidEntries", countMsg);
        }
    }

    private static PublicKeyEntry newEntry(String keyId, String pem) {
        PublicKeyEntry entry = new PublicKeyEntry("test-cluster", "test-ns", keyId, pem);
        entry.setAlgorithm("ed25519");
        entry.setStatus("active");
        return entry;
    }

    private static void assertCondition(
            FederatedKeyRegistryStatus status, String type, String expectedStatus, String reason) {
        Condition c = getCondition(status, type);
        assertNotNull(c, "Condition " + type + " not found");
        assertEquals(expectedStatus, c.getStatus(), type + " status");
        assertEquals(reason, c.getReason(), type + " reason");
    }

    private static Condition getCondition(FederatedKeyRegistryStatus status, String type) {
        return status.getConditions().stream()
                .filter(c -> type.equals(c.getType()))
                .findFirst()
                .orElse(null);
    }
}
