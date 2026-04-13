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

## Tech Stack

- Java 21, Quarkus 3.x, Maven multi-module
- JOSDK (operator), Fabric8 (K8s client), BouncyCastle (Ed25519)
- CloudEvents SDK, java-json-canonicalization (RFC 8785)
- Micrometer + Prometheus, OpenTelemetry, SmallRye Health
