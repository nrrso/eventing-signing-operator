// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

import java.util.ArrayList;
import java.util.List;

public class CloudEventSigningProducerPolicySpec {

    private List<String> canonicalAttributes =
            List.of("type", "source", "subject", "datacontenttype");
    private List<ProducerEntry> producers = new ArrayList<>();
    private KeyRotationPolicy keyRotation = new KeyRotationPolicy();
    private ProxyConfig proxy = new ProxyConfig();

    public List<String> getCanonicalAttributes() {
        return canonicalAttributes;
    }

    public void setCanonicalAttributes(List<String> canonicalAttributes) {
        this.canonicalAttributes = canonicalAttributes;
    }

    public List<ProducerEntry> getProducers() {
        return producers;
    }

    public void setProducers(List<ProducerEntry> producers) {
        this.producers = producers;
    }

    public KeyRotationPolicy getKeyRotation() {
        return keyRotation;
    }

    public void setKeyRotation(KeyRotationPolicy keyRotation) {
        this.keyRotation = keyRotation;
    }

    public ProxyConfig getProxy() {
        return proxy;
    }

    public void setProxy(ProxyConfig proxy) {
        this.proxy = proxy;
    }
}
