// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler;

import com.platform.cesigning.operator.crd.DestinationRef;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds Knative Sequence and Trigger resources as GenericKubernetesResource instances.
 * Fabric8 has no typed models for Knative CRDs, so we use unstructured resources.
 */
public final class KnativeResourceHelper {

    static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
    static final String MANAGED_BY_VALUE = "ce-signing-operator";
    static final String COMPONENT_LABEL = "app.kubernetes.io/component";

    public static final ResourceDefinitionContext SEQUENCE_RDC = new ResourceDefinitionContext.Builder()
            .withGroup("flows.knative.dev").withVersion("v1")
            .withPlural("sequences").withNamespaced(true).build();

    public static final ResourceDefinitionContext TRIGGER_RDC = new ResourceDefinitionContext.Builder()
            .withGroup("eventing.knative.dev").withVersion("v1")
            .withPlural("triggers").withNamespaced(true).build();

    private KnativeResourceHelper() {}

    /**
     * Build a signing Sequence with one step (signer service) and a reply destination.
     */
    public static GenericKubernetesResource buildSigningSequence(
            String producerName, String namespace, String signerServiceName,
            DestinationRef reply, OwnerReference ownerRef) {

        Map<String, Object> step = Map.of("ref", Map.of(
                "apiVersion", "v1",
                "kind", "Service",
                "name", signerServiceName));

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("steps", List.of(step));

        if (reply != null && reply.getRef() != null) {
            spec.put("reply", Map.of("ref", destinationRefMap(reply)));
        }

        return buildResource("flows.knative.dev/v1", "Sequence",
                producerName + "-signing-seq", namespace, spec, ownerRef, "signer");
    }

    /**
     * Build a verifying Sequence with one step (verifier service) and reply to subscriber.
     */
    public static GenericKubernetesResource buildVerifyingSequence(
            String consumerName, String namespace, String verifierServiceName,
            DestinationRef subscriber, OwnerReference ownerRef) {

        Map<String, Object> verifierStep = Map.of("ref", Map.of(
                "apiVersion", "v1",
                "kind", "Service",
                "name", verifierServiceName));

        Map<String, Object> spec = new LinkedHashMap<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(verifierStep);
        spec.put("steps", steps);

        if (subscriber != null && subscriber.getRef() != null) {
            spec.put("reply", Map.of("ref", destinationRefMap(subscriber)));
        }

        return buildResource("flows.knative.dev/v1", "Sequence",
                consumerName + "-verify-seq", namespace, spec, ownerRef, "verifier");
    }

    /**
     * Build a Trigger that subscribes from a broker (with filter) to a Sequence.
     */
    public static GenericKubernetesResource buildConsumerTrigger(
            String consumerName, String namespace, String brokerName,
            Map<String, String> filter, String sequenceName, OwnerReference ownerRef) {

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("broker", brokerName);

        if (filter != null && !filter.isEmpty()) {
            spec.put("filter", Map.of("attributes", new LinkedHashMap<>(filter)));
        }

        spec.put("subscriber", Map.of("ref", Map.of(
                "apiVersion", "flows.knative.dev/v1",
                "kind", "Sequence",
                "name", sequenceName)));

        return buildResource("eventing.knative.dev/v1", "Trigger",
                consumerName + "-trigger", namespace, spec, ownerRef, "verifier");
    }

    private static GenericKubernetesResource buildResource(
            String apiVersion, String kind, String name, String namespace,
            Map<String, Object> spec, OwnerReference ownerRef, String component) {

        var resource = new GenericKubernetesResource();
        resource.setApiVersion(apiVersion);
        resource.setKind(kind);

        var metaBuilder = new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                .addToLabels(COMPONENT_LABEL, component);

        if (ownerRef != null) {
            metaBuilder.withOwnerReferences(ownerRef);
        }

        resource.setMetadata(metaBuilder.build());
        resource.setAdditionalProperties(Map.of("spec", spec));
        return resource;
    }

    private static Map<String, Object> destinationRefMap(DestinationRef dest) {
        var ref = dest.getRef();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("apiVersion", ref.getApiVersion());
        map.put("kind", ref.getKind());
        map.put("name", ref.getName());
        if (ref.getNamespace() != null) {
            map.put("namespace", ref.getNamespace());
        }
        return map;
    }
}
