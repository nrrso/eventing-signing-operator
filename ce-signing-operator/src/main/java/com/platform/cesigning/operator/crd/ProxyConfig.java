// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

import java.util.Collections;
import java.util.Map;

public class ProxyConfig {

    private String image;
    private int replicas = 2;
    private ResourceConfig resources = new ResourceConfig();
    private HpaConfig hpa;
    private TopologyConfig topologySpreadConstraints;
    private Map<String, String> env = Collections.emptyMap();

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public int getReplicas() { return replicas; }
    public void setReplicas(int replicas) { this.replicas = replicas; }

    public ResourceConfig getResources() { return resources; }
    public void setResources(ResourceConfig resources) { this.resources = resources; }

    public HpaConfig getHpa() { return hpa; }
    public void setHpa(HpaConfig hpa) { this.hpa = hpa; }

    public TopologyConfig getTopologySpreadConstraints() { return topologySpreadConstraints; }
    public void setTopologySpreadConstraints(TopologyConfig topologySpreadConstraints) { this.topologySpreadConstraints = topologySpreadConstraints; }

    public Map<String, String> getEnv() { return env; }
    public void setEnv(Map<String, String> env) { this.env = env != null ? env : Collections.emptyMap(); }

    public static class ResourceConfig {
        private ResourceQuantity requests = new ResourceQuantity("50m", "64Mi");
        private ResourceQuantity limits = new ResourceQuantity("200m", "128Mi");

        public ResourceQuantity getRequests() { return requests; }
        public void setRequests(ResourceQuantity requests) { this.requests = requests; }
        public ResourceQuantity getLimits() { return limits; }
        public void setLimits(ResourceQuantity limits) { this.limits = limits; }
    }

    public static class ResourceQuantity {
        private String cpu;
        private String memory;

        public ResourceQuantity() {}
        public ResourceQuantity(String cpu, String memory) { this.cpu = cpu; this.memory = memory; }

        public String getCpu() { return cpu; }
        public void setCpu(String cpu) { this.cpu = cpu; }
        public String getMemory() { return memory; }
        public void setMemory(String memory) { this.memory = memory; }
    }

    public static class HpaConfig {
        private boolean enabled = true;
        private int minReplicas = 2;
        private int maxReplicas = 10;
        private int targetCPUUtilization = 70;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMinReplicas() { return minReplicas; }
        public void setMinReplicas(int minReplicas) { this.minReplicas = minReplicas; }
        public int getMaxReplicas() { return maxReplicas; }
        public void setMaxReplicas(int maxReplicas) { this.maxReplicas = maxReplicas; }
        public int getTargetCPUUtilization() { return targetCPUUtilization; }
        public void setTargetCPUUtilization(int targetCPUUtilization) { this.targetCPUUtilization = targetCPUUtilization; }
    }

    public static class TopologyConfig {
        private boolean enabled = true;
        private int maxSkew = 1;
        private String topologyKey = "topology.kubernetes.io/zone";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxSkew() { return maxSkew; }
        public void setMaxSkew(int maxSkew) { this.maxSkew = maxSkew; }
        public String getTopologyKey() { return topologyKey; }
        public void setTopologyKey(String topologyKey) { this.topologyKey = topologyKey; }
    }
}
