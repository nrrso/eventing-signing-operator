// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

import java.util.ArrayList;
import java.util.List;

public class CloudEventSigningConsumerPolicySpec {

    private List<TrustedSource> trustedSources = new ArrayList<>();
    private boolean rejectUnsigned = true;
    private List<ConsumerTriggerEntry> consumers = new ArrayList<>();
    private ProxyConfig proxy = new ProxyConfig();

    public List<TrustedSource> getTrustedSources() {
        return trustedSources;
    }

    public void setTrustedSources(List<TrustedSource> trustedSources) {
        this.trustedSources = trustedSources;
    }

    public boolean isRejectUnsigned() {
        return rejectUnsigned;
    }

    public void setRejectUnsigned(boolean rejectUnsigned) {
        this.rejectUnsigned = rejectUnsigned;
    }

    public List<ConsumerTriggerEntry> getConsumers() {
        return consumers;
    }

    public void setConsumers(List<ConsumerTriggerEntry> consumers) {
        this.consumers = consumers;
    }

    public ProxyConfig getProxy() {
        return proxy;
    }

    public void setProxy(ProxyConfig proxy) {
        this.proxy = proxy;
    }
}
