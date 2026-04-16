# CloudEvent Signing Platform — Architecture

## Overview

The platform provides transparent Ed25519 signing and verification of CloudEvents in a Knative eventing mesh. Two services share a single container image:

- **ce-signing-proxy** — HTTP service (sign or verify mode) as a Knative Sequence step
- **ce-signing-operator** — Kubernetes operator (JOSDK) reconciling three CRDs

## CRDs

| CRD | Scope | Purpose |
|-----|-------|---------|
| `CloudEventSigningProducerPolicy` (cespp) | Namespaced | Configures signing for a namespace |
| `CloudEventSigningConsumerPolicy` (cescp) | Namespaced | Configures verification for a namespace |
| `PublicKeyRegistry` (cepkr) | Cluster | Distributes public keys (singleton) |

## Signing Flow

```
CloudEvent → CanonicalForm (JCS + attribute sort) → Ed25519 sign → add extensions → return
```

Four extension attributes are added:
- `cesignature` — base64url-encoded 64-byte signature
- `cesignaturealg` — `ed25519`
- `cekeyid` — key identifier (e.g., `bu-alice-v1`)
- `cecanonattrs` — sorted comma-separated list of signed attributes

## Verification Flow

```
CloudEvent → check 4 extensions → lookup key → check namespace trust → check key status → rebuild canonical form → verify signature
```

## Canonicalization (RFC 8785)

The canonical form is built from the parsed CloudEvent SDK object:
1. Filter attributes to those present on the event
2. Sort attribute names lexicographically
3. Emit `name=value\n` for each attribute
4. Append `data=` followed by JCS-canonicalized JSON (or raw bytes for non-JSON)

JCS ensures identical bytes regardless of JSON key ordering or whitespace.

## Key Design Decisions

- **Namespace is the trust boundary** — one keypair per namespace
- **Self-describing signatures** — `cecanonattrs` tells the verifier which attributes were signed
- **Signatures never stripped** — audit trail preserved through the mesh
- **Proxy has no HTTP client** — Knative Sequences own all delivery
- **BouncyCastle Ed25519 directly** — not JCA wrapper, for GraalVM compatibility

## Key Rotation Lifecycle

```
active → rotating (grace period) → expired → removed
```

The operator generates a new keypair, marks the old key as `rotating`, and the verifier accepts both during the grace period.

## Resource Topology

Per producer namespace:
- 1 Secret (keypair), 1 Deployment (ce-signer), 1 Service, N Sequences, 1 HPA, 1 PDB

Per consumer namespace:
- 1 Deployment (ce-verifier), 1 Service, N Sequences, N Triggers, 1 HPA, 1 PDB

## Multi-Cluster Federation

Federation extends the signing/verification platform across 1-5 Kubernetes clusters connected via service mesh east-west gateways. It is opt-in; single-cluster deployments require only a `clusterName` Helm value.

### New CRDs

| CRD | Scope | Purpose |
|-----|-------|---------|
| `ClusterFederationConfig` | Cluster (singleton, name: `federation`) | Declares remote clusters and kubeconfig secret references |
| `FederatedKeyRegistry` (cefkr) | Cluster (one per remote cluster, name: `{clusterName}-keys`) | Stores public keys pulled from a remote cluster's `PublicKeyRegistry` |

### Cluster Identity Extension

Every signed event carries a fifth extension attribute `cesignercluster`, set to the signing cluster's `clusterName`. This attribute is always present (even in single-cluster mode), always included in `cecanonattrs`, and therefore always part of the signed canonical form. An attacker cannot swap cluster identity without invalidating the signature.

### Federation Controller

The existing operator image supports an `OPERATOR_MODE` environment variable:

- **LOCAL** (default) — runs ProducerPolicyReconciler, ConsumerPolicyReconciler, KeyRotationReconciler. Federation is disabled.
- **FEDERATION** — runs only the FederationReconciler. Deployed as a separate Deployment from the same Helm release (`federation.enabled: true`).

The FederationReconciler reconciles the `ClusterFederationConfig` singleton and establishes Fabric8 Watch connections to each remote cluster's `PublicKeyRegistry`. On Watch events, it syncs remote entries (with the `cluster` field set) to the local `FederatedKeyRegistry` resource for that cluster. Remote clusters can be added or removed at runtime without restarting the federation pod.

The verifier watches both `PublicKeyRegistry` (local keys) and `FederatedKeyRegistry` (remote keys) via Fabric8 informers, merging them into a single cache keyed by `(cluster, keyId)`.

### AP Consistency Model

Federation follows an AP (availability + partition tolerance) consistency model:

- **Propagation latency:** There is a window between a key change on a remote cluster and the federation controller syncing it locally. Under normal conditions this window is bounded by Fabric8 Watch propagation (typically sub-second). During this window, a revoked key on the remote cluster may still be trusted locally.
- **Network partitions:** When a remote cluster becomes unreachable, the federation controller continues serving from the last successfully synced `FederatedKeyRegistry`. The verifier remains available with cached keys. The `FederatedKeyRegistry` is not deleted during temporary unavailability — only explicit removal from `ClusterFederationConfig` triggers deletion.
- **Revocation propagation window:** A key revoked on cluster-a remains trusted on cluster-b until the Watch event propagates. For connected clusters this is near-instant; for partitioned clusters the window extends until connectivity is restored. This is an accepted trade-off for availability.

## Tech Stack

- Java 21, Quarkus 3.x, Maven multi-module
- JOSDK (operator), Fabric8 (K8s client), BouncyCastle (Ed25519)
- CloudEvents SDK, java-json-canonicalization (RFC 8785)
- Micrometer + Prometheus, OpenTelemetry, SmallRye Health
