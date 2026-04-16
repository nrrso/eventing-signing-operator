# CloudEvent Signing Platform — Onboarding Guide

## Prerequisites

- Kubernetes cluster (1.26+)
- Knative Eventing installed (Sequences, Triggers, Brokers)
- Helm 3.x
- Container registry access (e.g., `ghcr.io`)

## Installation

```bash
# Build and push container images
mvn verify -Dquarkus.container-image.build=true \
           -Dquarkus.container-image.push=true \
           -Dquarkus.container-image.registry=ghcr.io

# Install operator via Helm (clusterName is mandatory)
helm install ce-signing \
  ce-signing-operator/charts/ce-signing-operator/ \
  -n ce-signing-system --create-namespace \
  --set clusterName=cluster-east
```

## Enabling Signing (Producer)

Create a `CloudEventSigningProducerPolicy` in the producer namespace:

```yaml
apiVersion: ce-signing.platform.io/v1alpha1
kind: CloudEventSigningProducerPolicy
metadata:
  name: signing-policy
  namespace: bu-alice
spec:
  canonicalAttributes:
    - type
    - source
    - subject
    - datacontenttype
  producers:
    - name: order-service
      reply:
        ref:
          apiVersion: eventing.knative.dev/v1
          kind: Broker
          name: org-wide-broker
          namespace: knative-eventing
```

The operator generates keypairs, deploys signing proxies, and publishes public keys automatically.

## Enabling Verification (Consumer)

Create a `CloudEventSigningConsumerPolicy` in the consumer namespace:

```yaml
apiVersion: ce-signing.platform.io/v1alpha1
kind: CloudEventSigningConsumerPolicy
metadata:
  name: verification-policy
  namespace: bu-bob
spec:
  trustedSources:
    - cluster: cluster-east
      namespace: bu-alice
  rejectUnsigned: true
  consumers:
    - name: order-consumer
      broker: org-wide-broker
      brokerNamespace: knative-eventing
      filter:
        type: order.created
      subscriber:
        ref:
          apiVersion: v1
          kind: Service
          name: order-processor
```

The operator deploys verifying proxies and creates Knative Triggers automatically.

## Multi-Cluster Federation Setup

### Cluster Name Configuration

Every cluster requires a `clusterName` Helm value. This is mandatory and has no default. The value is propagated as `CE_CLUSTER_NAME` to both the operator and signing/verifying proxies. It becomes the `cesignercluster` extension attribute on every signed event.

```bash
helm install ce-signing \
  ce-signing-operator/charts/ce-signing-operator/ \
  -n ce-signing-system --create-namespace \
  --set clusterName=cluster-east
```

### Enabling the Federation Controller

Set `federation.enabled: true` in Helm values. This renders a second Deployment (`ce-signing-operator-federation`) running the same image with `OPERATOR_MODE=FEDERATION`, along with its dedicated RBAC resources.

```bash
helm install ce-signing \
  ce-signing-operator/charts/ce-signing-operator/ \
  -n ce-signing-system --create-namespace \
  --set clusterName=cluster-east \
  --set federation.enabled=true
```

Or via a values file:

```yaml
# values.yaml
clusterName: "cluster-east"
federation:
  enabled: true
```

### Creating the ClusterFederationConfig

Create a `ClusterFederationConfig` singleton to declare remote clusters. Each remote cluster requires a kubeconfig Secret with read-only access to that cluster's `PublicKeyRegistry`.

1. Create a kubeconfig Secret for each remote cluster:

```bash
kubectl create secret generic cluster-west-kubeconfig \
  -n ce-signing-system \
  --from-file=kubeconfig=/path/to/cluster-west-kubeconfig.yaml
```

2. Create the `ClusterFederationConfig`:

```yaml
apiVersion: ce-signing.platform.io/v1alpha1
kind: ClusterFederationConfig
metadata:
  name: federation
spec:
  remoteClusters:
    - name: cluster-west
      kubeconfigSecretRef:
        name: cluster-west-kubeconfig
        namespace: ce-signing-system
```

The federation controller establishes Watch connections to each remote cluster's `PublicKeyRegistry` and syncs keys into local `FederatedKeyRegistry` resources. Remote clusters can be added or removed at runtime without restarting the pod.

### Migrating from trustedNamespaces to trustedSources

The `trustedNamespaces` field on `CloudEventSigningConsumerPolicy` has been replaced by `trustedSources`, which uses explicit `(cluster, namespace)` pairs.

Before (single-cluster, pre-federation):

```yaml
spec:
  trustedNamespaces:
    - bu-alice
```

After:

```yaml
spec:
  trustedSources:
    - cluster: cluster-east
      namespace: bu-alice
```

To trust a namespace on a remote cluster, add an entry with the remote cluster's name:

```yaml
spec:
  trustedSources:
    - cluster: cluster-east
      namespace: bu-alice
    - cluster: cluster-west
      namespace: bu-payments
```

No wildcards are supported. Each trusted source must be an explicit `(cluster, namespace)` pair.

## Key Rotation

Key rotation is automated by default (`intervalDays: 90`, `gracePeriodDays: 7`). No operator intervention is required. Each cluster rotates keys independently; federated keys are synced automatically via the federation controller.
