// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

public class ProducerEntry {

    private String name;
    private DestinationRef reply;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public DestinationRef getReply() {
        return reply;
    }

    public void setReply(DestinationRef reply) {
        this.reply = reply;
    }
}
