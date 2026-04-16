// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

public class RemoteClusterEntry {

    private String name;
    private String kubeconfigSecretRef;

    public RemoteClusterEntry() {}

    public RemoteClusterEntry(String name, String kubeconfigSecretRef) {
        this.name = name;
        this.kubeconfigSecretRef = kubeconfigSecretRef;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getKubeconfigSecretRef() {
        return kubeconfigSecretRef;
    }

    public void setKubeconfigSecretRef(String kubeconfigSecretRef) {
        this.kubeconfigSecretRef = kubeconfigSecretRef;
    }
}
