// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler;

import static org.junit.jupiter.api.Assertions.*;

import com.platform.cesigning.operator.crd.DestinationRef;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class KnativeResourceHelperTest {

    private final OwnerReference ownerRef =
            new OwnerReferenceBuilder()
                    .withApiVersion("ce-signing.platform.io/v1alpha1")
                    .withKind("CloudEventSigningProducerPolicy")
                    .withName("test-policy")
                    .withUid("uid-123")
                    .withController(true)
                    .build();

    @Test
    void buildSigningSequenceStructure() {
        DestinationRef reply =
                createDestinationRef(
                        "eventing.knative.dev/v1", "Broker", "alice-broker", "bu-alice");

        GenericKubernetesResource seq =
                KnativeResourceHelper.buildSigningSequence(
                        "cloudevents-player", "bu-alice", "ce-signer", reply, ownerRef);

        assertEquals("flows.knative.dev/v1", seq.getApiVersion());
        assertEquals("Sequence", seq.getKind());
        assertEquals("cloudevents-player-signing-seq", seq.getMetadata().getName());
        assertEquals("bu-alice", seq.getMetadata().getNamespace());
        assertEquals(
                "ce-signing-operator",
                seq.getMetadata().getLabels().get("app.kubernetes.io/managed-by"));
        assertEquals("signer", seq.getMetadata().getLabels().get("app.kubernetes.io/component"));

        Map<String, Object> spec = (Map<String, Object>) seq.getAdditionalProperties().get("spec");
        assertNotNull(spec);

        List<Map<String, Object>> steps = (List<Map<String, Object>>) spec.get("steps");
        assertEquals(1, steps.size());
        Map<String, Object> stepRef = (Map<String, Object>) steps.get(0).get("ref");
        assertEquals("ce-signer", stepRef.get("name"));
        assertEquals("Service", stepRef.get("kind"));

        Map<String, Object> replyMap = (Map<String, Object>) spec.get("reply");
        Map<String, Object> replyRef = (Map<String, Object>) replyMap.get("ref");
        assertEquals("alice-broker", replyRef.get("name"));
        assertEquals("Broker", replyRef.get("kind"));
    }

    @Test
    void buildVerifyingSequenceStructure() {
        DestinationRef subscriber =
                createDestinationRef("v1", "Service", "cloudevents-player", null);

        GenericKubernetesResource seq =
                KnativeResourceHelper.buildVerifyingSequence(
                        "player-consumer", "bu-bob", "ce-verifier", subscriber, ownerRef);

        assertEquals("flows.knative.dev/v1", seq.getApiVersion());
        assertEquals("Sequence", seq.getKind());
        assertEquals("player-consumer-verify-seq", seq.getMetadata().getName());

        Map<String, Object> spec = (Map<String, Object>) seq.getAdditionalProperties().get("spec");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) spec.get("steps");
        assertEquals(1, steps.size());
        Map<String, Object> verifierRef = (Map<String, Object>) steps.get(0).get("ref");
        assertEquals("ce-verifier", verifierRef.get("name"));

        Map<String, Object> replyMap = (Map<String, Object>) spec.get("reply");
        Map<String, Object> replyRef = (Map<String, Object>) replyMap.get("ref");
        assertEquals("cloudevents-player", replyRef.get("name"));
        assertEquals("Service", replyRef.get("kind"));
    }

    @Test
    void buildConsumerTriggerStructure() {
        Map<String, String> filter = Map.of("type", "com.corp.integration.user");

        GenericKubernetesResource trigger =
                KnativeResourceHelper.buildConsumerTrigger(
                        "player-consumer",
                        "bu-bob",
                        "bob-broker",
                        filter,
                        "player-consumer-verify-seq",
                        ownerRef);

        assertEquals("eventing.knative.dev/v1", trigger.getApiVersion());
        assertEquals("Trigger", trigger.getKind());
        assertEquals("player-consumer-trigger", trigger.getMetadata().getName());

        Map<String, Object> spec =
                (Map<String, Object>) trigger.getAdditionalProperties().get("spec");
        assertEquals("bob-broker", spec.get("broker"));

        Map<String, Object> filterMap = (Map<String, Object>) spec.get("filter");
        Map<String, String> attrs = (Map<String, String>) filterMap.get("attributes");
        assertEquals("com.corp.integration.user", attrs.get("type"));

        Map<String, Object> subscriberMap = (Map<String, Object>) spec.get("subscriber");
        Map<String, Object> subRef = (Map<String, Object>) subscriberMap.get("ref");
        assertEquals("flows.knative.dev/v1", subRef.get("apiVersion"));
        assertEquals("Sequence", subRef.get("kind"));
        assertEquals("player-consumer-verify-seq", subRef.get("name"));
    }

    @Test
    void ownerReferenceIsSetOnAllResources() {
        DestinationRef reply = createDestinationRef("eventing.knative.dev/v1", "Broker", "b", null);

        GenericKubernetesResource seq =
                KnativeResourceHelper.buildSigningSequence("p", "ns", "ce-signer", reply, ownerRef);
        assertEquals(1, seq.getMetadata().getOwnerReferences().size());
        assertEquals("test-policy", seq.getMetadata().getOwnerReferences().get(0).getName());
        assertTrue(seq.getMetadata().getOwnerReferences().get(0).getController());
    }

    @Test
    void sequenceRdcHasCorrectGroup() {
        assertEquals("flows.knative.dev", KnativeResourceHelper.SEQUENCE_RDC.getGroup());
        assertEquals("v1", KnativeResourceHelper.SEQUENCE_RDC.getVersion());
    }

    @Test
    void triggerRdcHasCorrectGroup() {
        assertEquals("eventing.knative.dev", KnativeResourceHelper.TRIGGER_RDC.getGroup());
        assertEquals("v1", KnativeResourceHelper.TRIGGER_RDC.getVersion());
    }

    @Test
    void signingAndVerifyingSequencesHaveDistinctComponentLabels() {
        DestinationRef dest = createDestinationRef("eventing.knative.dev/v1", "Broker", "b", null);

        GenericKubernetesResource signing =
                KnativeResourceHelper.buildSigningSequence("p", "ns", "ce-signer", dest, ownerRef);
        GenericKubernetesResource verifying =
                KnativeResourceHelper.buildVerifyingSequence(
                        "p", "ns", "ce-verifier", dest, ownerRef);

        assertEquals(
                "signer", signing.getMetadata().getLabels().get("app.kubernetes.io/component"));
        assertEquals(
                "verifier", verifying.getMetadata().getLabels().get("app.kubernetes.io/component"));
    }

    @Test
    void triggerHasVerifierComponentLabel() {
        GenericKubernetesResource trigger =
                KnativeResourceHelper.buildConsumerTrigger(
                        "c", "ns", "broker", null, "c-verify-seq", ownerRef);
        assertEquals(
                "verifier", trigger.getMetadata().getLabels().get("app.kubernetes.io/component"));
    }

    private DestinationRef createDestinationRef(
            String apiVersion, String kind, String name, String namespace) {
        var ref = new DestinationRef.ObjectRef();
        ref.setApiVersion(apiVersion);
        ref.setKind(kind);
        ref.setName(name);
        ref.setNamespace(namespace);
        var dest = new DestinationRef();
        dest.setRef(ref);
        return dest;
    }
}
