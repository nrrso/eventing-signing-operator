// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

import java.util.Objects;

public class TrustedSource {

    private String cluster;
    private String namespace;

    public TrustedSource() {}

    public TrustedSource(String cluster, String namespace) {
        this.cluster = cluster;
        this.namespace = namespace;
    }

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        this.cluster = cluster;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrustedSource that = (TrustedSource) o;
        return Objects.equals(cluster, that.cluster) && Objects.equals(namespace, that.namespace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cluster, namespace);
    }
}
