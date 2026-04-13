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
import org.jboss.logging.Logger;

import java.util.*;

@ApplicationScoped
public class VerifyingHandler {

    private static final Logger LOG = Logger.getLogger(VerifyingHandler.class);
    private static final List<String> SIGNATURE_EXTENSIONS = List.of(
            "cesignature", "cesignaturealg", "cekeyid", "cecanonattrs");

    @Inject
    ProxyConfig config;

    @Inject
    RegistryKeyCache keyCache;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    Tracer tracer;

    private Set<String> trustedNamespaces;
    private boolean rejectUnsigned;
    private Counter validCounter;
    private Counter invalidCounter;
    private Counter unsignedCounter;
    private Counter errorCounter;
    private Timer verifyTimer;

    @PostConstruct
    void init() {
        if (!"verify".equals(config.mode())) {
            return;
        }
        trustedNamespaces = config.verify().trustedNamespaces()
                .map(HashSet::new)
                .orElse(new HashSet<>());
        rejectUnsigned = config.verify().rejectUnsigned();
        LOG.infof("Verifying handler initialized: trustedNamespaces=%s, rejectUnsigned=%s",
                trustedNamespaces, rejectUnsigned);

        validCounter = Counter.builder("ce_signing_events_total")
                .tag("mode", "verify").tag("status", "valid")
                .register(meterRegistry);
        invalidCounter = Counter.builder("ce_signing_events_total")
                .tag("mode", "verify").tag("status", "rejected")
                .register(meterRegistry);
        unsignedCounter = Counter.builder("ce_signing_events_total")
                .tag("mode", "verify").tag("status", "unsigned")
                .register(meterRegistry);
        errorCounter = Counter.builder("ce_signing_events_total")
                .tag("mode", "verify").tag("status", "error")
                .register(meterRegistry);
        verifyTimer = Timer.builder("ce_verification_duration_seconds")
                .register(meterRegistry);
    }

    public Response verify(CloudEvent event) {
        return verifyTimer.record(() -> doVerify(event));
    }

    private Response doVerify(CloudEvent event) {
        try {
            // Check all four signature extensions as complete set
            boolean hasAllExtensions = SIGNATURE_EXTENSIONS.stream()
                    .allMatch(ext -> event.getExtension(Objects.requireNonNull(ext)) != null);

            if (!hasAllExtensions) {
                return handleUnsigned(event);
            }

            String cesignature = event.getExtension("cesignature").toString();
            String cesignaturealg = event.getExtension("cesignaturealg").toString();
            String cekeyid = event.getExtension("cekeyid").toString();
            String cecanonattrs = event.getExtension("cecanonattrs").toString();

            if (!"ed25519".equals(cesignaturealg)) {
                LOG.warnf("Unsupported signature algorithm: %s", cesignaturealg);
                invalidCounter.increment();
                return Response.status(403).entity("Unsupported algorithm: " + cesignaturealg).build();
            }

            Span span = tracer.spanBuilder("ce-verify")
                    .setAttribute("ce.type", Objects.requireNonNull(event.getType()))
                    .setAttribute("ce.source", Objects.requireNonNull(event.getSource().toString()))
                    .setAttribute("ce.keyid", Objects.requireNonNull(cekeyid))
                    .startSpan();
            try {
                // Look up key
                Optional<PublicKeyEntry> entryOpt = keyCache.getEntry(cekeyid);
                if (entryOpt.isEmpty()) {
                    LOG.warnf("Unknown key ID: %s", cekeyid);
                    invalidCounter.increment();
                    span.setAttribute("ce.verified", false);
                    return Response.status(403).entity("Unknown key").build();
                }

                PublicKeyEntry entry = entryOpt.get();

                // Check namespace trust
                if (!trustedNamespaces.contains(entry.namespace())) {
                    LOG.warnf("Untrusted namespace: %s for key %s", entry.namespace(), cekeyid);
                    invalidCounter.increment();
                    span.setAttribute("ce.verified", false);
                    return Response.status(403).entity("Untrusted namespace").build();
                }

                // Check key status
                if (!entry.isUsableForVerification()) {
                    LOG.warnf("Key %s has status %s, not usable for verification", cekeyid, entry.status());
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
                    LOG.warnf("Invalid signature for event type=%s source=%s keyid=%s",
                            event.getType(), event.getSource(), cekeyid);
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
                    .entity("Verification error").build();
        }
    }

    private Response handleUnsigned(CloudEvent event) {
        if (rejectUnsigned) {
            unsignedCounter.increment();
            return Response.status(403).entity("Unsigned event rejected").build();
        }
        LOG.warnf("Unsigned event passed through: type=%s source=%s", event.getType(), event.getSource());
        unsignedCounter.increment();
        return Response.ok(event).build();
    }
}
