// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

public class DestinationRef {

    private ObjectRef ref;

    public ObjectRef getRef() { return ref; }
    public void setRef(ObjectRef ref) { this.ref = ref; }

    public static class ObjectRef {
        private String apiVersion = "eventing.knative.dev/v1";
        private String kind = "Broker";
        private String name;
        private String namespace;

        public String getApiVersion() { return apiVersion; }
        public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }

        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
    }
}
