# CloudEvent Signing Platform

Transparent Ed25519 signing and verification for [CloudEvents](https://cloudevents.io/) on [Knative](https://knative.dev/). Sign events at the producer, verify at the consumer — enabling cross-namespace event authentication through untrusted brokers.

## Features

- **Ed25519 signatures** with [RFC 8785 (JCS)](https://www.rfc-editor.org/rfc/rfc8785) canonicalization
- **Namespace-scoped trust boundaries** — one keypair per namespace, not per service
- **Self-describing signatures** — verifier reads signed attributes from the event itself (`cecanonattrs`)
- **Automatic key rotation** — configurable interval with grace period for zero-downtime rollover
- **Kubernetes-native** — CRD-driven configuration, Helm deployment, Prometheus metrics
- **Transparent to applications** — signing and verification happen in Knative Sequence steps
- **Formally verified** — TLA+ models check reconciler concurrency (4 bugs found and fixed)

## Architecture

Two Quarkus services, each built as a separate container image:

| Component | Description |
|-----------|-------------|
| **ce-signing-proxy** | HTTP service running as a Knative Sequence step in `sign` or `verify` mode. Signs/verifies CloudEvents using Ed25519. Deployed once per producer Sequence and once per consumer Trigger. |
| **ce-signing-operator** | Kubernetes operator (JOSDK + Fabric8) reconciling three CRDs to manage keypairs, proxies, Knative resources, and public key distribution. |

### CRDs

| CRD | Scope | Purpose |
|-----|-------|---------|
| `CloudEventSigningProducerPolicy` | Namespaced | Configures signing for a namespace — manages keypairs, signing proxy, Knative Sequences |
| `CloudEventSigningConsumerPolicy` | Namespaced | Configures verification — manages verifying proxy, Knative Triggers |
| `PublicKeyRegistry` | Cluster | Distributes public keys (singleton, name: `ce-signing-registry`) |

### Signing Flow

```
CloudEvent → canonical form (attribute filter + sort + JCS data) → Ed25519 sign → add extensions → return
```

Four extension attributes are added to signed events: `cesignature`, `cesignaturealg`, `cekeyid`, `cecanonattrs`.

## Prerequisites

- Java 21
- Maven 3.9+ (or use the included Maven wrapper: `./mvnw`)
- Docker (for local development with kind)
- Kubernetes cluster with [Knative Eventing](https://knative.dev/docs/install/) installed (for deployment)
- Helm 3.x (for deployment)

Tool versions can be managed via [mise](https://mise.jdx.dev/): run `mise install` to set up the correct versions.

## Build

```bash
# Build all modules
./mvnw clean package

# Build individual modules
./mvnw clean package -pl ce-signing-proxy
./mvnw clean package -pl ce-signing-operator

# Run tests
./mvnw test

# Build container images (Jib — no Dockerfile needed)
./mvnw verify -Dquarkus.container-image.build=true

# Build and push to registry
./mvnw verify -Dquarkus.container-image.build=true \
              -Dquarkus.container-image.push=true \
              -Dquarkus.container-image.registry=ghcr.io
```

## Development

A `Makefile` drives the local dev flow: build images, spin up a kind cluster with Knative, deploy via Helm, and apply test scenarios.

```bash
# Full flow from scratch — creates kind cluster, builds images, deploys operator + test scenarios
make dev

# Inner loop — rebuild images, upgrade Helm release, restart pods
make rebuild

# Tear down cluster and local registry
make clean
```

### Individual targets

```bash
make registry          # Start local container registry on :5001
make build             # Build + push images to local registry
make cluster           # Create kind cluster with Knative Eventing
make connect           # Connect registry to kind network
make prometheus-crds   # Install ServiceMonitor CRD
make install           # Helm install operator
make upgrade           # Helm upgrade operator
make apply             # Apply test scenarios (kubectl apply -k test/overlays/local)
```

### Observability

```bash
make logs              # Tail operator + event-display logs
make status            # Show pods, nodes, and PublicKeyRegistry state
```

### Quarkus dev mode

```bash
./mvnw quarkus:dev -pl ce-signing-proxy      # Proxy dev mode
./mvnw quarkus:dev -pl ce-signing-operator    # Operator dev mode
```

### Running tests

```bash
./mvnw test                                           # All unit tests
./mvnw test -pl ce-signing-proxy                      # Proxy tests only
./mvnw test -pl ce-signing-operator                   # Operator tests only
./mvnw verify                                         # Includes integration tests
./mvnw test -pl ce-signing-proxy -Dtest=CanonicalFormTest  # Single test class
```

## Quick Start

### 1. Enable signing for a producer namespace

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
  keyRotation:
    enabled: true
    intervalDays: 90
    gracePeriodDays: 7
```

### 2. Enable verification for a consumer namespace

```yaml
apiVersion: ce-signing.platform.io/v1alpha1
kind: CloudEventSigningConsumerPolicy
metadata:
  name: verification-policy
  namespace: bu-bob
spec:
  trustedNamespaces:
    - bu-alice
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

### 3. Verify setup

```bash
kubectl get cespp -A          # Producer policies
kubectl get cescp -A          # Consumer policies
kubectl get cepkr             # Public key registry
```

## Documentation

- [Architecture overview](docs/ARCHITECTURE.md)
- [Design document](docs/DESIGN.md)
- [Control plane & data plane flows](docs/FLOWCHART.md)
- [Formal verification with TLA+](docs/tlaplus-info.md)

## Tech Stack

- **Java 21**, **Quarkus 3.33**, **Maven** (multi-module)
- **Quarkus Operator SDK 7.7** (JOSDK), **Fabric8** (Kubernetes client)
- **BouncyCastle 1.78** (Ed25519), **CloudEvents SDK 4.0.1**
- **java-json-canonicalization 1.1** (RFC 8785 JCS)
- **Micrometer + Prometheus**, **OpenTelemetry**, **SmallRye Health**

## License

This project is licensed under the [Apache License 2.0](LICENSE).
