// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler;

import com.platform.cesigning.operator.crd.CloudEventSigningProducerPolicy;
import com.platform.cesigning.operator.crd.KeyRotationPolicy;
import com.platform.cesigning.operator.crypto.KeyPairGenerator;
import com.platform.cesigning.operator.reconciler.dependent.producer.SecretDependentResource;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.javaoperatorsdk.operator.processing.event.source.timer.TimerEventSource;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.jboss.logging.Logger;

@ControllerConfiguration(name = "key-rotation-controller")
public class KeyRotationReconciler implements Reconciler<CloudEventSigningProducerPolicy> {

    private static final Logger LOG = Logger.getLogger(KeyRotationReconciler.class);
    private static final long DEFAULT_CHECK_INTERVAL_MS = Duration.ofHours(1).toMillis();

    @Inject KubernetesClient client;

    private TimerEventSource<CloudEventSigningProducerPolicy> timerEventSource;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public List<EventSource<?, CloudEventSigningProducerPolicy>> prepareEventSources(
            EventSourceContext<CloudEventSigningProducerPolicy> context) {
        timerEventSource = new TimerEventSource<>();

        var secretInformer =
                new InformerEventSource<>(
                        InformerEventSourceConfiguration.from(
                                        Secret.class, CloudEventSigningProducerPolicy.class)
                                .withLabelSelector(SecretDependentResource.KEY_ID_LABEL)
                                .build(),
                        context);

        // TimerEventSource extends AbstractEventSource<Void, HasMetadata> — raw cast needed
        List<EventSource<?, CloudEventSigningProducerPolicy>> sources = new ArrayList<>();
        sources.add((EventSource) timerEventSource);
        sources.add(secretInformer);
        return sources;
    }

    @Override
    public UpdateControl<CloudEventSigningProducerPolicy> reconcile(
            CloudEventSigningProducerPolicy resource,
            Context<CloudEventSigningProducerPolicy> context) {

        String namespace = resource.getMetadata().getNamespace();
        KeyRotationPolicy rotation = resource.getSpec().getKeyRotation();

        // Schedule next check
        timerEventSource.scheduleOnce(resource, DEFAULT_CHECK_INTERVAL_MS);

        if (!rotation.isEnabled()) {
            return UpdateControl.noUpdate();
        }

        try {
            var secretOpt = context.getSecondaryResource(Secret.class);
            if (secretOpt.isEmpty()) {
                return UpdateControl.noUpdate();
            }
            Secret secret = secretOpt.get();

            java.util.Map<String, String> secretLabels = secret.getMetadata().getLabels();
            if (secretLabels == null) {
                return UpdateControl.noUpdate();
            }
            String currentKeyId = secretLabels.get(SecretDependentResource.KEY_ID_LABEL);
            String createdAtStr = secretLabels.get(SecretDependentResource.CREATED_AT_LABEL);

            if (createdAtStr == null || currentKeyId == null) {
                return UpdateControl.noUpdate();
            }

            OffsetDateTime createdAt = LabelSafeTimestamp.decode(createdAtStr);
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            long daysOld = Duration.between(createdAt, now).toDays();

            if (daysOld < rotation.getIntervalDays()) {
                return UpdateControl.noUpdate();
            }

            // Rotation needed
            LOG.infof(
                    "Key %s in namespace %s is %d days old (interval=%d), rotating",
                    currentKeyId, namespace, daysOld, rotation.getIntervalDays());

            String newKeyId = performRotation(namespace, currentKeyId, secret);

            context.getClient()
                    .resource(
                            EventHelper.normalEvent(
                                    resource,
                                    "KeyRotated",
                                    "Rotated key from " + currentKeyId + " to " + newKeyId))
                    .inNamespace(namespace)
                    .create();

        } catch (Exception e) {
            LOG.errorf(e, "Failed key rotation check for namespace %s", namespace);
        }

        return UpdateControl.noUpdate();
    }

    String performRotation(String namespace, String oldKeyId, Secret existingSecret)
            throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        KeyPairGenerator.GeneratedKeyPair newKeyPair = KeyPairGenerator.generate();
        String newKeyId = incrementKeyId(oldKeyId);

        // Preserve existing owner references
        List<OwnerReference> ownerRefs = existingSecret.getMetadata().getOwnerReferences();

        Secret secret =
                new SecretBuilder()
                        .withNewMetadata()
                        .withName(SecretDependentResource.SECRET_NAME)
                        .withNamespace(namespace)
                        .addToLabels(SecretDependentResource.KEY_ID_LABEL, newKeyId)
                        .addToLabels(
                                SecretDependentResource.CREATED_AT_LABEL,
                                LabelSafeTimestamp.encode(now))
                        .addToLabels(SecretDependentResource.PREVIOUS_KEY_ID_LABEL, oldKeyId)
                        .endMetadata()
                        .withType("Opaque")
                        .addToData(
                                SecretDependentResource.PRIVATE_KEY_FIELD,
                                Base64.getEncoder()
                                        .encodeToString(newKeyPair.privatePem().getBytes()))
                        .addToData(
                                SecretDependentResource.PUBLIC_KEY_FIELD,
                                Base64.getEncoder()
                                        .encodeToString(newKeyPair.publicPem().getBytes()))
                        .build();

        if (ownerRefs != null && !ownerRefs.isEmpty()) {
            secret.getMetadata().setOwnerReferences(new ArrayList<>(ownerRefs));
        }

        client.secrets().inNamespace(namespace).resource(secret).serverSideApply();
        LOG.infof(
                "Updated Secret with new key %s in namespace %s (previous: %s)",
                newKeyId, namespace, oldKeyId);

        return newKeyId;
    }

    static String incrementKeyId(String keyId) {
        int dashV = keyId.lastIndexOf("-v");
        if (dashV >= 0) {
            String prefix = keyId.substring(0, dashV);
            String versionStr = keyId.substring(dashV + 2);
            try {
                int version = Integer.parseInt(versionStr);
                return prefix + "-v" + (version + 1);
            } catch (NumberFormatException e) {
                // Fall through
            }
        }
        return keyId + "-v2";
    }
}
