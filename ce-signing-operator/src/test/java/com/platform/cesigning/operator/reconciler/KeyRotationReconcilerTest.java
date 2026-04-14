// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler;

import static org.junit.jupiter.api.Assertions.*;

import com.platform.cesigning.operator.crd.PublicKeyRegistry;
import com.platform.cesigning.operator.reconciler.dependent.producer.SecretDependentResource;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeyRotationReconcilerTest {

    @Test
    void incrementKeyIdFromV1() {
        assertEquals("bu-alice-v2", KeyRotationReconciler.incrementKeyId("bu-alice-v1"));
    }

    @Test
    void incrementKeyIdFromV9() {
        assertEquals("bu-alice-v10", KeyRotationReconciler.incrementKeyId("bu-alice-v9"));
    }

    @Test
    void incrementKeyIdFromV42() {
        assertEquals("bu-alice-v43", KeyRotationReconciler.incrementKeyId("bu-alice-v42"));
    }

    @Test
    void incrementKeyIdWithoutVersion() {
        assertEquals("my-key-v2", KeyRotationReconciler.incrementKeyId("my-key"));
    }

    @Test
    void incrementKeyIdWithNamespaceLikeName() {
        assertEquals("my-namespace-v2", KeyRotationReconciler.incrementKeyId("my-namespace-v1"));
    }

    @Test
    void performRotationPreservesOwnerReferences() {
        OwnerReference ownerRef =
                new OwnerReferenceBuilder()
                        .withApiVersion("ce-signing.platform.io/v1alpha1")
                        .withKind("CloudEventSigningProducerPolicy")
                        .withName("test-policy")
                        .withUid("test-uid-123")
                        .withController(true)
                        .build();

        Secret existingSecret =
                new SecretBuilder()
                        .withNewMetadata()
                        .withName(SecretDependentResource.SECRET_NAME)
                        .withNamespace("bu-alice")
                        .addToLabels(SecretDependentResource.KEY_ID_LABEL, "bu-alice-v1")
                        .addToLabels(SecretDependentResource.CREATED_AT_LABEL, "1735689600")
                        .withOwnerReferences(ownerRef)
                        .endMetadata()
                        .withType("Opaque")
                        .addToData("private.pem", "old-key")
                        .addToData("public.pem", "old-pub")
                        .build();

        List<OwnerReference> refs = existingSecret.getMetadata().getOwnerReferences();
        assertNotNull(refs);
        assertEquals(1, refs.size());
        assertEquals("test-policy", refs.get(0).getName());
        assertEquals("test-uid-123", refs.get(0).getUid());
        assertTrue(refs.get(0).getController());
    }

    @Test
    void performRotationMethodSignatureDoesNotIncludeRegistryParams() throws NoSuchMethodException {
        // Verify performRotation no longer takes KeyRotationPolicy (which was needed for registry
        // writes)
        var method =
                KeyRotationReconciler.class.getDeclaredMethod(
                        "performRotation", String.class, String.class, Secret.class);
        assertNotNull(method, "performRotation should take (namespace, oldKeyId, existingSecret)");
        assertEquals(3, method.getParameterCount());
    }

    @Test
    void keyRotationReconcilerDoesNotReferencePublicKeyRegistry() {
        // Verify no registry-related fields or methods remain
        for (var field : KeyRotationReconciler.class.getDeclaredFields()) {
            assertFalse(
                    field.getType().equals(PublicKeyRegistry.class),
                    "KeyRotationReconciler should not have PublicKeyRegistry fields");
        }
        for (var method : KeyRotationReconciler.class.getDeclaredMethods()) {
            assertFalse(
                    method.getName().contains("Registry"),
                    "KeyRotationReconciler should not have registry-related methods: "
                            + method.getName());
        }
    }

    @Test
    void previousKeyIdLabelConstantExists() {
        assertEquals(
                "ce-signing.platform.io/previous-key-id",
                SecretDependentResource.PREVIOUS_KEY_ID_LABEL);
    }
}
