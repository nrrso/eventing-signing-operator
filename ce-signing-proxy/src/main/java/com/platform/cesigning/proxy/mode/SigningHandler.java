// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.mode;

import com.platform.cesigning.proxy.config.ProxyConfig;
import com.platform.cesigning.proxy.crypto.CanonicalForm;
import com.platform.cesigning.proxy.crypto.EventSigner;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SigningHandler {

    private static final Logger LOG = Logger.getLogger(SigningHandler.class);

    @Inject ProxyConfig config;

    @Inject MeterRegistry meterRegistry;

    @Inject Tracer tracer;

    private EventSigner signer;
    private List<String> canonicalAttributes;
    private String keyId;
    private String clusterName;
    private Counter successCounter;
    private Counter errorCounter;
    private Timer signingTimer;

    @PostConstruct
    void init() {
        if (!"sign".equals(config.mode())) {
            return;
        }
        successCounter =
                Counter.builder("ce_signing_events_total")
                        .tag("mode", "sign")
                        .tag("status", "success")
                        .register(meterRegistry);
        errorCounter =
                Counter.builder("ce_signing_events_total")
                        .tag("mode", "sign")
                        .tag("status", "error")
                        .register(meterRegistry);
        signingTimer = Timer.builder("ce_signing_duration_seconds").register(meterRegistry);
        try {
            signer = new EventSigner(java.nio.file.Path.of(config.privateKeyPath()));
            // Always include cesignercluster in canonical attributes
            List<String> configured = config.canonicalAttributes();
            canonicalAttributes = new ArrayList<>(configured);
            if (!canonicalAttributes.contains("cesignercluster")) {
                canonicalAttributes.add("cesignercluster");
            }
            keyId = config.keyId();
            clusterName = config.clusterName();
            LOG.infof(
                    "Signing handler initialized with keyId=%s, clusterName=%s, canonicalAttributes=%s",
                    keyId, clusterName, canonicalAttributes);
        } catch (IOException e) {
            LOG.error("Failed to load signing key", e);
        }
    }

    public Response sign(CloudEvent event) {
        if (signer == null) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Signing key not loaded")
                    .build();
        }

        return signingTimer.record(
                () -> {
                    try {
                        Span span =
                                tracer.spanBuilder("ce-sign")
                                        .setAttribute(
                                                "ce.type", Objects.requireNonNull(event.getType()))
                                        .setAttribute(
                                                "ce.source",
                                                Objects.requireNonNull(
                                                        event.getSource().toString()))
                                        .setAttribute("ce.keyid", Objects.requireNonNull(keyId))
                                        .startSpan();
                        try {
                            // Add cluster identity before building canonical form
                            CloudEvent eventWithCluster =
                                    CloudEventBuilder.from(event)
                                            .withExtension(
                                                    "cesignercluster",
                                                    Objects.requireNonNull(clusterName))
                                            .build();

                            // Build canonical form and sign
                            byte[] canonical =
                                    CanonicalForm.build(eventWithCluster, canonicalAttributes);
                            String signature = signer.signToBase64Url(canonical);
                            String presentAttrs =
                                    CanonicalForm.presentAttributes(
                                            eventWithCluster, canonicalAttributes);

                            // Add signature extensions to the event
                            CloudEvent signedEvent =
                                    CloudEventBuilder.from(eventWithCluster)
                                            .withExtension(
                                                    "cesignature",
                                                    Objects.requireNonNull(signature))
                                            .withExtension("cesignaturealg", "ed25519")
                                            .withExtension("cekeyid", Objects.requireNonNull(keyId))
                                            .withExtension(
                                                    "cecanonattrs",
                                                    Objects.requireNonNull(presentAttrs))
                                            .build();

                            meterRegistry
                                    .summary("ce_signing_event_size_bytes")
                                    .record(canonical.length);
                            successCounter.increment();

                            return Response.ok(signedEvent).build();
                        } finally {
                            span.end();
                        }
                    } catch (Exception e) {
                        LOG.error("Signing failed", e);
                        errorCounter.increment();
                        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                                .entity("Signing failed")
                                .build();
                    }
                });
    }

    public boolean isKeyLoaded() {
        return signer != null;
    }
}
