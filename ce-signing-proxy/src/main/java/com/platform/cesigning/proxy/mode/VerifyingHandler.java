// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.mode;

import com.platform.cesigning.proxy.config.ProxyConfig;
import com.platform.cesigning.proxy.crypto.CanonicalForm;
import com.platform.cesigning.proxy.crypto.EventVerifier;
import com.platform.cesigning.proxy.registry.PublicKeyEntry;
import com.platform.cesigning.proxy.registry.RegistryKeyCache;
import io.cloudevents.CloudEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.util.*;
import org.jboss.logging.Logger;

@ApplicationScoped
public class VerifyingHandler {

    private static final Logger LOG = Logger.getLogger(VerifyingHandler.class);
    private static final List<String> SIGNATURE_EXTENSIONS =
            List.of("cesignature", "cesignaturealg", "cekeyid", "cecanonattrs", "cesignercluster");

    @Inject ProxyConfig config;

    @Inject RegistryKeyCache keyCache;

    @Inject MeterRegistry meterRegistry;

    @Inject Tracer tracer;

    private Set<TrustedSource> trustedSources;
    private boolean rejectUnsigned;
    private Counter validCounter;
    private Counter invalidCounter;
    private Counter unsignedCounter;
    private Counter errorCounter;
    private Timer verifyTimer;

    /** Parsed (cluster, namespace) trust pair. */
    record TrustedSource(String cluster, String namespace) {}

    @PostConstruct
    void init() {
        if (!"verify".equals(config.mode())) {
            return;
        }
        trustedSources = parseTrustedSources(config.verify().trustedSources());
        rejectUnsigned = config.verify().rejectUnsigned();
        LOG.infof(
                "Verifying handler initialized: trustedSources=%s, rejectUnsigned=%s",
                trustedSources, rejectUnsigned);

        validCounter =
                Counter.builder("ce_signing_events_total")
                        .tag("mode", "verify")
                        .tag("status", "valid")
                        .register(meterRegistry);
        invalidCounter =
                Counter.builder("ce_signing_events_total")
                        .tag("mode", "verify")
                        .tag("status", "rejected")
                        .register(meterRegistry);
        unsignedCounter =
                Counter.builder("ce_signing_events_total")
                        .tag("mode", "verify")
                        .tag("status", "unsigned")
                        .register(meterRegistry);
        errorCounter =
                Counter.builder("ce_signing_events_total")
                        .tag("mode", "verify")
                        .tag("status", "error")
                        .register(meterRegistry);
        verifyTimer = Timer.builder("ce_verification_duration_seconds").register(meterRegistry);
    }

    /**
     * Parse trusted sources from config. Format: "cluster1/ns1,cluster2/ns2" where each entry is
     * "cluster/namespace".
     */
    static Set<TrustedSource> parseTrustedSources(Optional<List<String>> raw) {
        Set<TrustedSource> result = new HashSet<>();
        raw.ifPresent(
                list -> {
                    for (String entry : list) {
                        String trimmed = entry.trim();
                        if (trimmed.isEmpty()) continue;
                        int slash = trimmed.indexOf('/');
                        if (slash > 0 && slash < trimmed.length() - 1) {
                            result.add(
                                    new TrustedSource(
                                            trimmed.substring(0, slash),
                                            trimmed.substring(slash + 1)));
                        }
                    }
                });
        return result;
    }

    public Response verify(CloudEvent event) {
        return verifyTimer.record(() -> doVerify(event));
    }

    private Response doVerify(CloudEvent event) {
        try {
            // Check all five signature extensions as complete set
            boolean hasAllExtensions =
                    SIGNATURE_EXTENSIONS.stream()
                            .allMatch(
                                    ext -> event.getExtension(Objects.requireNonNull(ext)) != null);

            if (!hasAllExtensions) {
                return handleUnsigned(event);
            }

            String cesignature = event.getExtension("cesignature").toString();
            String cesignaturealg = event.getExtension("cesignaturealg").toString();
            String cekeyid = event.getExtension("cekeyid").toString();
            String cecanonattrs = event.getExtension("cecanonattrs").toString();
            String cesignercluster = event.getExtension("cesignercluster").toString();

            if (!"ed25519".equals(cesignaturealg)) {
                LOG.warnf("Unsupported signature algorithm: %s", cesignaturealg);
                invalidCounter.increment();
                return Response.status(403)
                        .entity("Unsupported algorithm: " + cesignaturealg)
                        .build();
            }

            Span span =
                    tracer.spanBuilder("ce-verify")
                            .setAttribute("ce.type", Objects.requireNonNull(event.getType()))
                            .setAttribute(
                                    "ce.source",
                                    Objects.requireNonNull(event.getSource().toString()))
                            .setAttribute("ce.keyid", Objects.requireNonNull(cekeyid))
                            .setAttribute("ce.cluster", Objects.requireNonNull(cesignercluster))
                            .startSpan();
            try {
                // Look up key by composite (cluster, keyId)
                Optional<PublicKeyEntry> entryOpt = keyCache.getEntry(cesignercluster, cekeyid);
                if (entryOpt.isEmpty()) {
                    LOG.warnf("Unknown key: cluster=%s, keyId=%s", cesignercluster, cekeyid);
                    invalidCounter.increment();
                    span.setAttribute("ce.verified", false);
                    return Response.status(403).entity("Unknown key").build();
                }

                PublicKeyEntry entry = entryOpt.get();

                // Check (cluster, namespace) trust
                if (!trustedSources.contains(
                        new TrustedSource(entry.cluster(), entry.namespace()))) {
                    LOG.warnf(
                            "Untrusted source: cluster=%s, namespace=%s for key %s",
                            entry.cluster(), entry.namespace(), cekeyid);
                    invalidCounter.increment();
                    span.setAttribute("ce.verified", false);
                    return Response.status(403).entity("Untrusted source").build();
                }

                // Check key status
                if (!entry.isUsableForVerification()) {
                    LOG.warnf(
                            "Key %s has status %s, not usable for verification",
                            cekeyid, entry.status());
                    invalidCounter.increment();
                    span.setAttribute("ce.verified", false);
                    return Response.status(403).entity("Key expired").build();
                }

                // Rebuild canonical form from cecanonattrs
                List<String> attrs = Arrays.asList(cecanonattrs.split(","));
                byte[] canonical = CanonicalForm.build(event, attrs);

                // Decode signature
                byte[] signatureBytes = Base64.getUrlDecoder().decode(cesignature);

                // Verify
                boolean valid = EventVerifier.verify(canonical, signatureBytes, entry.publicKey());

                if (valid) {
                    validCounter.increment();
                    span.setAttribute("ce.verified", true);
                    // Return event as-is with signatures intact
                    return Response.ok(event).build();
                } else {
                    LOG.warnf(
                            "Invalid signature for event type=%s source=%s keyid=%s cluster=%s",
                            event.getType(), event.getSource(), cekeyid, cesignercluster);
                    invalidCounter.increment();
                    span.setAttribute("ce.verified", false);
                    return Response.status(403).entity("Invalid signature").build();
                }
            } finally {
                span.end();
            }
        } catch (Exception e) {
            LOG.error("Verification failed", e);
            errorCounter.increment();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Verification error")
                    .build();
        }
    }

    private Response handleUnsigned(CloudEvent event) {
        if (rejectUnsigned) {
            unsignedCounter.increment();
            return Response.status(403).entity("Unsigned event rejected").build();
        }
        LOG.warnf(
                "Unsigned event passed through: type=%s source=%s",
                event.getType(), event.getSource());
        unsignedCounter.increment();
        return Response.ok(event).build();
    }
}
