# Knative Extension: CloudEvent Signing and Verification


---

## Motivation / Abstract

Knative Eventing provides a powerful event-driven architecture, but it currently lacks native support for verifying that events have not been tampered with in transit. In a multi-tenant cluster where events flow through shared brokers, any namespace can emit events with arbitrary `type` and `source` attributes. A consumer has no cryptographic guarantee that an event claiming to originate from namespace `bu-alice` was actually produced there.

This proposal introduces transparent Ed25519 signing at event origin and verification at the consumer, enabling cross-namespace event authentication through untrusted brokers. The platform uses a Kubernetes operator to manage key lifecycle and a lightweight HTTP proxy (deployed as Knative Sequence steps) to sign and verify CloudEvents without requiring application code changes.

The design is scoped to namespace-level trust boundaries using Ed25519 with RFC 8785 (JCS) canonicalization, providing integrity and authenticity guarantees for CloudEvents traversing the Knative eventing mesh.

---

## Background

In multi-tenant Kubernetes clusters using Knative Eventing, events often cross namespace boundaries through shared brokers. This creates several security gaps:

- **No origin authentication:** A consumer cannot verify that an event was actually produced by the namespace it claims to originate from. Any namespace can emit events with arbitrary `type` and `source` attributes.
- **No tamper detection:** Events flowing through a shared broker can be intercepted and modified by intermediaries. Consumers have no way to detect if event attributes or data have been altered in transit.
- **No trust boundaries:** Knative's current model treats all event producers equally. There is no mechanism for a consumer to selectively trust events from specific namespaces while rejecting others.

Existing approaches to this problem are insufficient:

- **Network policies** can restrict traffic flow but do not provide cryptographic integrity guarantees.
- **mTLS (service mesh)** authenticates transport but not event content — once an event enters a shared broker, its origin identity is lost.
- **Application-level signing** requires each producer and consumer to implement signing logic, creating inconsistency and operational burden.

### Knative Eventing Security Features (Alpha/Beta)

Knative Eventing has introduced three security features that address parts of the multi-tenant trust problem at the transport and access-control layers:

- **[Transport Encryption](https://knative.dev/docs/eventing/features/transport-encryption/)** (Beta) — Enables HTTPS endpoints for Brokers and Channels using cluster-internal CAs via cert-manager. Protects events from eavesdropping on the wire. Three modes: `disabled`, `permissive`, `strict`.

- **[Sender Identity / OIDC Authentication](https://knative.dev/docs/eventing/features/sender-identity/)** (Alpha) — Addressables expose an OIDC audience in `.status.address.audience`. Sources automatically obtain and attach OIDC Bearer tokens. The broker can authenticate *who* sent an event by validating the token against the sender's ServiceAccount.

- **[Authorization / EventPolicy](https://knative.dev/docs/eventing/features/authorization/)** (Alpha) — The `EventPolicy` CRD enables fine-grained access control over which ServiceAccounts (`.spec.from`) may send events to which Brokers, Channels, or Sequences (`.spec.to`). Supports advanced CloudEvent filtering via CESQL. Unauthorized senders receive HTTP 403.

**These features are complementary to this proposal, not a replacement.** All three operate at the **broker boundary** — they protect the gate but not the content that passes through it:

1. **OIDC tokens are not forwarded to consumers.** Once the broker accepts an event, it re-delivers to subscribers using the broker's own identity. The consumer cannot answer "was this event actually produced by namespace `bu-alice`?"

2. **No content integrity beyond the broker.** A compromised or buggy broker (or any intermediary) can modify event attributes or data before delivery. Neither TLS, OIDC, nor EventPolicy provide tamper detection at the consumer.

3. **No cryptographic proof travels with the event.** Authentication and authorization happen at the gate, but the event itself carries no evidence of its origin once inside the mesh.

This proposal fills the remaining layer: **end-to-end, content-level integrity** where the cryptographic signature is embedded in the event as extension attributes, survives broker re-delivery and re-serialization, and can be independently verified by any consumer with access to the public key registry.

The recommended deployment model is defense in depth — all four layers working together:

```
Producer
  → [TLS]         wire encryption                    ← transport-encryption
  → [OIDC]        broker authenticates sender         ← sender-identity
  → [EventPolicy] broker authorizes sender            ← authorization
  → [Ed25519]     event signed with content integrity ← this proposal
  → Broker (shared, untrusted)
  → [Consumer verifies Ed25519 signature]             ← this proposal
```

**Related work:**
- [Knative Eventing Transport Encryption](https://knative.dev/docs/eventing/features/transport-encryption/) — TLS for event delivery (Beta)
- [Knative Eventing Sender Identity](https://knative.dev/docs/eventing/features/sender-identity/) — OIDC authentication (Alpha)
- [Knative Eventing Authorization](https://knative.dev/docs/eventing/features/authorization/) — EventPolicy access control (Alpha)
- [CloudEvents Subscriptions Spec](https://github.com/cloudevents/spec/blob/main/subscriptions/spec.md)
- [RFC 8785 — JSON Canonicalization Scheme (JCS)](https://www.rfc-editor.org/rfc/rfc8785)
- [Ed25519 — RFC 8032](https://www.rfc-editor.org/rfc/rfc8032)

---

## Proposal Design / Approach

The platform introduces two components deployed via a Kubernetes operator:

1. **Signing Proxy** — an HTTP service injected as a Knative Sequence step in front of the producer's target broker. It intercepts outbound CloudEvents, signs them with Ed25519, and adds four extension attributes carrying the signature, algorithm, key ID, and list of signed attributes.

2. **Verifying Proxy** — an HTTP service injected as a Knative Sequence step after a Trigger. It intercepts inbound CloudEvents, verifies the Ed25519 signature against a cluster-wide public key registry, and rejects events that fail verification (HTTP 403) or are unsigned (configurable).

The operator manages three Custom Resource Definitions:

- **CloudEventSigningProducerPolicy** (namespace-scoped) — declares which producers should sign events, which attributes to include in the signature, and key rotation policy.
- **CloudEventSigningConsumerPolicy** (namespace-scoped) — declares which namespaces a consumer trusts, whether to reject unsigned events, and how to route verified events to subscribers.
- **PublicKeyRegistry** (cluster-scoped singleton) — distributes public keys from all producer namespaces to all verifiers. Only the ProducerPolicyReconciler writes to this resource.

**Key design principles:**
- **Namespace is the trust boundary** — one keypair per namespace, not per service, keeping key management simple.
- **Self-describing signatures** — the `cecanonattrs` extension tells the verifier which attributes were signed, so verifiers do not need out-of-band configuration.
- **Signatures are never stripped** — the audit trail is preserved through the entire mesh.
- **Proxy has no HTTP client** — Knative Sequences own all delivery and retry, keeping the proxy stateless and simple.

---

## Design

### Resource Model

#### CloudEventSigningProducerPolicy

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
  proxy:
    image: ghcr.io/nrrso/ce-signing-proxy:latest
    replicas: 2
    resources:
      requests:
        cpu: 100m
        memory: 128Mi
      limits:
        cpu: 500m
        memory: 256Mi
```

**Status conditions:** `Ready`, `KeyPairReady`, `SigningProxyReady`, `RegistryPublished`

#### CloudEventSigningConsumerPolicy

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

**Status conditions:** `Ready`, `VerifyingProxyReady`

#### PublicKeyRegistry (cluster-scoped singleton)

```yaml
apiVersion: ce-signing.platform.io/v1alpha1
kind: PublicKeyRegistry
metadata:
  name: ce-signing-registry
spec:
  entries:
    - namespace: bu-alice
      keyId: bu-alice-v1
      publicKeyPEM: |
        -----BEGIN PUBLIC KEY-----
        MCowBQYDK2VwAyEA...
        -----END PUBLIC KEY-----
      algorithm: ed25519
      status: active
      createdAt: "2025-01-15T10:00:00Z"
      expiresAt: "2025-04-15T10:00:00Z"
```

Each entry is uniquely identified by `(namespace, keyId)`. Only one `active` entry per namespace.

### Signing Flow

```
Producer Application
  |
  v
Knative Sequence (step 1: ce-signer)
  |
  |  1. Receive CloudEvent via HTTP POST
  |  2. Build canonical form:
  |     a. Filter attributes to those present on the event
  |     b. Sort attribute names lexicographically (UTF-8)
  |     c. Emit "name=value\n" for each attribute
  |     d. Append "data=" + JCS-canonicalized JSON (RFC 8785)
  |  3. Ed25519 sign canonical bytes (BouncyCastle, 64-byte signature)
  |  4. Add extension attributes:
  |     - cesignature:    base64url-encoded 64-byte signature
  |     - cesignaturealg: "ed25519"
  |     - cekeyid:        key identifier (e.g., "bu-alice-v1")
  |     - cecanonattrs:   sorted comma-separated list of signed attributes
  |  5. Return signed CloudEvent (HTTP binary content mode)
  |
  v
Sequence reply -> Broker (shared, untrusted)
```

### Verification Flow

```
Broker
  |
  v
Knative Trigger (filter match)
  |
  v
Knative Sequence (step 1: ce-verifier)
  |
  |  1. Check all 4 signature extensions present
  |     (partial = treat as unsigned -> reject if rejectUnsigned)
  |  2. Lookup cekeyid in RegistryKeyCache (in-memory, Watch-synced)
  |  3. Check key status is "active" or "rotating"
  |  4. Check key's source namespace is in trustedNamespaces
  |  5. Rebuild canonical form using cecanonattrs + event data
  |  6. Verify Ed25519 signature against public key
  |  7. Valid:   return HTTP 200 (signatures preserved)
  |     Invalid: return HTTP 403
  |
  v
Sequence step 2 -> Subscriber (application service)
```

### Key Rotation Lifecycle

```
                    intervalDays
                   (default: 90)
                        |
    active ─────────────┼──> rotating ──────────> expired ──> removed
   (new key)            |   (grace period,       (no longer
                        |    default: 7 days)      accepted)
                        v
                   active (v2)
```

1. `KeyRotationReconciler` detects key age exceeds `intervalDays`
2. Generates new Ed25519 keypair, updates Secret with incremented key ID
3. `ProducerPolicyReconciler` detects the change and updates registry:
   - Old key: `active` -> `rotating`
   - New key: `active`
4. Verifiers pick up both keys via Watch (no restart needed)
5. During grace period, events signed with either key verify successfully
6. After grace period: `rotating` -> `expired` -> entry removed from registry

### Failure Modes

| Failure | Behavior |
|---------|----------|
| Signing proxy unavailable | Knative Sequence retries with backoff; events queue, not lost |
| Verifying proxy unavailable | Knative Trigger retries with backoff; events queue, not lost |
| Registry Watch disconnected | Verifier serves from cached keys; reconnects automatically; readiness probe gates on `isSynced()` |
| Key not found in registry | Verification fails with HTTP 403 |
| Untrusted namespace | Verification fails with HTTP 403 |
| Registry write conflict (409) | ProducerPolicyReconciler retries with optimistic concurrency (max 3 attempts) |
| Partial signature extensions | Event treated as unsigned; rejected if `rejectUnsigned: true` |

### Security Properties

- Producer and consumer never share secrets (asymmetric cryptography)
- The shared broker sees signed events but cannot forge signatures
- Tampering with any attribute listed in `cecanonattrs` invalidates the signature
- Tampering with `cecanonattrs` itself changes the canonical form, failing verification
- An attacker in a different namespace cannot forge another namespace's signatures (separate keypair)
- Private keys are stored only in Kubernetes Secrets; they never appear in logs, status, registry, or non-Secret resources

---

## Implementation

### Repository Structure

```
ce-signing-platform/
  pom.xml                           # Parent POM (Java 21, Quarkus 3.15.1)
  ce-signing-proxy/                 # HTTP signing/verification service
    src/main/java/
      com/platform/cesigning/proxy/
        CanonicalForm.java          # RFC 8785 canonicalization
        Ed25519Signer.java          # Signing logic
        Ed25519Verifier.java        # Verification logic
        RegistryKeyCache.java       # Watch-synced key cache
        SigningResource.java        # HTTP endpoint (sign mode)
        VerifyingResource.java      # HTTP endpoint (verify mode)
  ce-signing-operator/              # Kubernetes operator
    src/main/java/
      com/platform/cesigning/operator/
        ProducerPolicyReconciler.java
        ConsumerPolicyReconciler.java
        KeyRotationReconciler.java
        dependents/                 # JOSDK dependent resources
          SecretDependentResource.java
          SigningDeploymentDependentResource.java
          VerifyingDeploymentDependentResource.java
          SequenceDependentResource.java
          TriggerDependentResource.java
    src/main/helm/
      crds/                         # CRD YAML definitions
      templates/                    # Custom Helm templates (RBAC, namespace)
```

### Key Directories Impacted

- `ce-signing-proxy/src/main/java/` — signing and verification HTTP service
- `ce-signing-operator/src/main/java/` — operator reconcilers and dependent resources
- `ce-signing-operator/src/main/helm/crds/` — CRD definitions
- `ce-signing-operator/src/main/helm/templates/` — custom Helm chart templates
- `deploy/` — Grafana dashboard and Prometheus alerting rules

---

## Prerequisites / Dependencies

- **Kubernetes** cluster (1.26+)
- **Knative Eventing** installed (Sequences, Triggers, Brokers)
- **Helm 3.x** for operator installation
- **Java 21** and **Maven 3.9.9** for building
- **Container registry** access (e.g., `ghcr.io`) for pushing images

### Library Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Quarkus | 3.15.1 | Application framework |
| JOSDK | 6.8.4 | Operator SDK |
| Fabric8 | (managed by Quarkus) | Kubernetes client |
| BouncyCastle | 1.78 | Ed25519 cryptography |
| CloudEvents SDK | 4.0.1 | CloudEvent parsing/serialization |
| java-json-canonicalization | 1.1 | RFC 8785 JCS |

---

## Integration Checklist

### Operations

**Installation:**

```bash
# Build operator and container images
mvn verify -Dquarkus.container-image.build=true \
           -Dquarkus.container-image.push=true \
           -Dquarkus.container-image.registry=ghcr.io

# Install operator via generated Helm chart
helm install ce-signing \
  ce-signing-operator/target/helm/kubernetes/ce-signing-operator/ \
  -n ce-signing-system --create-namespace
```

**Enabling signing:** Create a `CloudEventSigningProducerPolicy` in the producer namespace. The operator generates keypairs, deploys signing proxies, and publishes public keys automatically.

**Enabling verification:** Create a `CloudEventSigningConsumerPolicy` in the consumer namespace. The operator deploys verifying proxies and creates Knative Triggers automatically.

**Key rotation:** Enabled by default (`intervalDays: 90`, `gracePeriodDays: 7`). Fully automated — no operator intervention required.

### Observability

**Metrics (Micrometer + Prometheus):**

| Metric | Type | Description |
|--------|------|-------------|
| `ce_signing_events_signed_total` | Counter | Events signed, by key ID |
| `ce_signing_events_verified_total` | Counter | Events verified, by key ID and result |
| `ce_signing_events_rejected_total` | Counter | Events rejected (invalid signature, untrusted, unsigned) |
| `ce_signing_signing_duration_seconds` | Histogram | Signing latency |
| `ce_signing_verification_duration_seconds` | Histogram | Verification latency |
| `ce_signing_registry_synced` | Gauge | 1 if registry Watch is synced, 0 otherwise |
| `ce_signing_registry_entries` | Gauge | Number of entries in registry cache |
| `ce_signing_key_age_seconds` | Gauge | Age of current active key |

**Tracing (OpenTelemetry):**
- Spans for sign/verify operations with key ID, algorithm, and result attributes

**Health (SmallRye Health):**
- Signing proxy: liveness + readiness (always ready in sign mode)
- Verifying proxy: liveness + readiness (gates on `isSynced()` for registry Watch)
- Operator: liveness + readiness

**Alerting (Prometheus rules in `deploy/prometheus-rules.yaml`):**
- Key approaching expiration without rotation
- Registry Watch desync
- Elevated rejection rate

**Dashboard:**
- Grafana dashboard at `deploy/grafana/ce-signing-dashboard.json`

**Personas:**

| Persona | Observability Needs |
|---------|-------------------|
| Platform Operator | Key rotation status, registry health, proxy availability |
| Application Developer | Events signed/verified counts, rejection reasons |
| Security Team | Key lifecycle, untrusted namespace rejections, audit trail |

---

## Test Plan

**Unit Tests:**
- `CanonicalFormTest` — canonical form construction with unicode, nested JSON, numeric edge cases, attribute ordering
- `Ed25519SignerTest` — signing with known test vectors
- `Ed25519VerifierTest` — verification with valid/invalid/tampered signatures
- `RegistryKeyCacheTest` — cache population, eviction, sync status

**Integration Tests (Quarkus `@QuarkusTest`):**
- ProducerPolicyReconciler: keypair generation, Secret creation, Deployment creation, Sequence creation, registry publication
- ConsumerPolicyReconciler: Deployment creation, Trigger creation, Sequence creation
- KeyRotationReconciler: rotation trigger, Secret update, registry status transitions
- End-to-end sign-verify cycle through HTTP endpoints

**Property-Based Tests:**
- Canonicalization determinism: random CloudEvents always produce the same canonical form
- Sign-verify round-trip: any valid CloudEvent can be signed and verified successfully

**Test execution:**

```bash
mvn test                                  # All unit tests
mvn test -pl ce-signing-proxy             # Proxy tests only
mvn test -pl ce-signing-operator          # Operator tests only
mvn verify                                # Includes integration tests
mvn test -pl ce-signing-proxy -Dtest=CanonicalFormTest  # Single test class
```

---

## Documentation

### Personas and Use Cases

| Persona | Use Case | Documentation |
|---------|----------|---------------|
| Platform Operator | Install operator, configure RBAC, monitor health | `docs/onboarding.md` |
| Application Developer (Producer) | Enable signing for a namespace | `docs/onboarding.md` (producer section) |
| Application Developer (Consumer) | Enable verification with trusted namespaces | `docs/onboarding.md` (consumer section) |
| Security/Compliance | Understand trust model, audit key rotation | `docs/architecture.md` |

### Existing Documentation

- `docs/architecture.md` — Architecture overview, resource topology, design decisions
- `docs/onboarding.md` — Step-by-step setup guide for producers and consumers
- `docs/uc1-cross-bu-scenario.md` — End-to-end cross-namespace scenario (Alice to Bob)

---

## Exit Criteria

### Alpha

- [ ] CRDs defined and applied via Helm chart
- [ ] ProducerPolicyReconciler generates keypairs, deploys signing proxy, publishes to registry
- [ ] ConsumerPolicyReconciler deploys verifying proxy, creates Triggers and Sequences
- [ ] Ed25519 signing and verification via proxy (sign and verify modes)
- [ ] RFC 8785 JCS canonicalization for JSON event data
- [ ] PublicKeyRegistry with Watch-based distribution to verifiers
- [ ] Basic key rotation (manual trigger via Secret deletion)
- [ ] Unit and integration tests pass
- [ ] Documentation: onboarding guide and architecture overview

### Beta

- [ ] Automated key rotation with configurable interval and grace period
- [ ] Optimistic concurrency for registry writes (409 conflict handling)
- [ ] HPA and PDB for proxy deployments
- [ ] Prometheus metrics and Grafana dashboard
- [ ] OpenTelemetry tracing spans
- [ ] Health checks with registry sync gating for verifier readiness
- [ ] Prometheus alerting rules
- [ ] Error status recording and Kubernetes event emission
- [ ] Property-based tests for canonicalization
- [ ] Cross-namespace end-to-end scenario validated

### GA

- [ ] Key rotation lifecycle fully automated (active -> rotating -> expired -> removed)
- [ ] Finalizer-based cleanup on policy deletion
- [ ] Topology spread constraints for proxy pods
- [ ] Performance testing under load (signing/verification latency P99)
- [ ] Security audit of key material handling
- [ ] Conformance test suite
- [ ] Complete documentation for all personas

---

## Alternatives Considered

### 1. Application-Level Signing Libraries

**Approach:** Provide a signing/verification library that each application imports and calls directly.

**Why rejected:** Requires code changes in every producer and consumer. Inconsistent adoption across teams. Language-specific — the platform supports polyglot services. Harder to enforce and audit centrally.

### 2. Knative Native Security Features (Transport Encryption + OIDC + EventPolicy)

**Approach:** Rely on the combination of Knative's alpha/beta security features — transport encryption (TLS via cert-manager), sender identity (OIDC authentication), and authorization (EventPolicy CRD) — to secure event delivery without additional signing infrastructure.

**Why insufficient alone:** These three features collectively secure the **transport and broker boundary**, but they do not provide end-to-end content integrity:

| Property | Knative Native Features | This Proposal |
|---|---|---|
| Wire encryption | TLS (transport-encryption) | N/A (complementary) |
| Sender authenticated to broker | OIDC (sender-identity) | N/A (complementary) |
| Access control at broker | EventPolicy (authorization) | N/A (complementary) |
| **Content integrity through broker** | No | Ed25519 signature in `cesignature` |
| **Consumer verifies origin** | No — OIDC token not forwarded past broker | Public key lookup by `cekeyid` |
| **Tamper detection at consumer** | No | Canonical form + signature verification |
| **Audit trail** | No | Signature extensions preserved end-to-end |
| **Survives re-serialization** | N/A | JCS canonicalization (RFC 8785) |

The critical gap: once the broker accepts an event, it re-delivers to subscribers using the broker's own identity. The OIDC token from the original producer is not forwarded. A compromised broker can modify event content undetected. These features are treated as **complementary layers** in a defense-in-depth model, not as replacements. See the Background section for the recommended layered deployment model.

### 3. Service Mesh mTLS

**Approach:** Rely on Istio/Linkerd mTLS for transport-level authentication.

**Why rejected:** mTLS authenticates the transport channel, not the event content. Once an event enters a shared broker, the broker re-sends it — the original mTLS identity is lost. Does not provide integrity guarantees for event data. Similar to Knative's transport-encryption feature, this protects the wire but not the payload.

### 5. HMAC (Symmetric) Signing

**Approach:** Use HMAC-SHA256 with shared secrets between producer and consumer namespaces.

**Why rejected:** Requires secret distribution between namespaces, which is operationally complex and increases the blast radius of a key compromise. Any party with the shared secret can forge signatures. Ed25519 (asymmetric) allows the producer to sign without the consumer needing the private key.

### 6. Per-Service Keys (Instead of Per-Namespace)

**Approach:** Generate a unique keypair for each service within a namespace.

**Why rejected:** Dramatically increases key management complexity (N keys per namespace vs. 1). The namespace is already the Kubernetes trust/RBAC boundary, so per-namespace keys align with the existing security model. Per-service keys can be considered for a future version if the use case arises.

### 7. Signing at the Broker Level

**Approach:** Modify the Knative Broker to sign events on behalf of producers.

**Why rejected:** Requires changes to the Broker implementation (Knative core). The broker is shared and untrusted by design — having it sign events defeats the purpose of end-to-end authentication. The sidecar/proxy approach works with any broker implementation.

### 8. JWS / JWT Envelope

**Approach:** Wrap the entire CloudEvent in a JWS/JWT envelope for signing.

**Why rejected:** Changes the event structure — intermediaries would need to unwrap/re-wrap. CloudEvent extension attributes preserve the standard event format and flow transparently through the mesh. The `cecanonattrs` approach is self-describing and lighter weight than a full JWS envelope.
