// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

import java.util.Map;

public class ConsumerTriggerEntry {

    private String name;
    private String broker;
    private Map<String, String> filter;
    private DestinationRef subscriber;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBroker() {
        return broker;
    }

    public void setBroker(String broker) {
        this.broker = broker;
    }

    public Map<String, String> getFilter() {
        return filter;
    }

    public void setFilter(Map<String, String> filter) {
        this.filter = filter;
    }

    public DestinationRef getSubscriber() {
        return subscriber;
    }

    public void setSubscriber(DestinationRef subscriber) {
        this.subscriber = subscriber;
    }
}
