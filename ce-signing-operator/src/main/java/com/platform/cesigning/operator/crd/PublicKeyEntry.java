// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

public class PublicKeyEntry {

    private String cluster;
    private String namespace;
    private String keyId;
    private String publicKeyPEM;
    private String algorithm = "ed25519";
    private String createdAt;
    private String expiresAt;
    private String status = "active";

    public PublicKeyEntry() {}

    public PublicKeyEntry(String cluster, String namespace, String keyId, String publicKeyPEM) {
        this.cluster = cluster;
        this.namespace = namespace;
        this.keyId = keyId;
        this.publicKeyPEM = publicKeyPEM;
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

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getPublicKeyPEM() {
        return publicKeyPEM;
    }

    public void setPublicKeyPEM(String publicKeyPEM) {
        this.publicKeyPEM = publicKeyPEM;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
