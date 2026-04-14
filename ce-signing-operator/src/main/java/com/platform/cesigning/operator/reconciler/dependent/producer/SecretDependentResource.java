// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler.dependent.producer;

import com.platform.cesigning.operator.crd.CloudEventSigningProducerPolicy;
import com.platform.cesigning.operator.crypto.KeyPairGenerator;
import com.platform.cesigning.operator.reconciler.LabelSafeTimestamp;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.Creator;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependentResource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

@KubernetesDependent
public class SecretDependentResource
        extends KubernetesDependentResource<Secret, CloudEventSigningProducerPolicy>
        implements Creator<Secret, CloudEventSigningProducerPolicy> {

    public static final String SECRET_NAME = "ce-signing-key";
    public static final String PRIVATE_KEY_FIELD = "private.pem";
    public static final String PUBLIC_KEY_FIELD = "public.pem";
    public static final String KEY_ID_LABEL = "ce-signing.platform.io/key-id";
    public static final String CREATED_AT_LABEL = "ce-signing.platform.io/created-at";
    public static final String PREVIOUS_KEY_ID_LABEL = "ce-signing.platform.io/previous-key-id";

    public SecretDependentResource() {
        super(Secret.class);
    }

    @Override
    protected Secret desired(
            CloudEventSigningProducerPolicy primary,
            Context<CloudEventSigningProducerPolicy> context) {
        String namespace = primary.getMetadata().getNamespace();
        String keyId = namespace + "-v1";
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        try {
            KeyPairGenerator.GeneratedKeyPair keyPair = KeyPairGenerator.generate();
            return new SecretBuilder()
                    .withNewMetadata()
                    .withName(SECRET_NAME)
                    .withNamespace(namespace)
                    .addToLabels(KEY_ID_LABEL, keyId)
                    .addToLabels(CREATED_AT_LABEL, LabelSafeTimestamp.encode(now))
                    .endMetadata()
                    .withType("Opaque")
                    .addToData(
                            PRIVATE_KEY_FIELD,
                            Base64.getEncoder().encodeToString(keyPair.privatePem().getBytes()))
                    .addToData(
                            PUBLIC_KEY_FIELD,
                            Base64.getEncoder().encodeToString(keyPair.publicPem().getBytes()))
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to generate Ed25519 keypair", e);
        }
    }
}
