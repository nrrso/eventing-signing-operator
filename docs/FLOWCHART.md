# CloudEvent Signing Platform — Control Plane & Data Plane Flows

## Overview

The platform has three distinct planes:

- **Control Plane**: The operator watches CRDs and reconciles Kubernetes resources (keys, deployments, services, Knative resources, registry entries).
- **Data Plane**: The signing and verifying proxies process CloudEvents inline via Knative Sequences. Pure request-response — no outbound HTTP calls.
- **Federation Plane** (opt-in): A separate federation controller syncs public keys from remote clusters into local `FederatedKeyRegistry` resources via Fabric8 Watches.

---

## Control Plane Flow

### Producer Policy Reconciliation

When a `CloudEventSigningProducerPolicy` CR is created or updated in a namespace:

```mermaid
flowchart TD
    A[ProducerPolicy CR created/updated<br/>in namespace bu-alice] --> B{Secret ce-signing-key<br/>exists in namespace?}

    B -- No --> C[Generate Ed25519 keypair]
    C --> D[Create Secret ce-signing-key<br/>private.pem + key-id]
    D --> E[Upsert PublicKeyRegistry entry]

    B -- Yes --> E

    E --> L[Create/Update Deployment ce-signer<br/>MODE=sign, mount Secret]
    L --> M[Create/Update Service ce-signer]
    M --> N[For each producer in spec.producers]
    N --> O[Create Knative Sequence<br/>step: ce-signer service<br/>reply: target broker]
    O --> P[Create HPA + PDB + ServiceMonitor]
    P --> Q[Update ProducerPolicy status<br/>ready, keyId, signingEndpoint]
```

### Key Rotation Reconciliation

A separate `KeyRotationReconciler` watches `ProducerPolicy` CRs on a `TimerEventSource` (default: hourly). When a key exceeds `rotationIntervalDays`:

```mermaid
flowchart TD
    A[TimerEventSource fires<br/>hourly check] --> B[Read Secret ce-signing-key<br/>via InformerEventSource]
    B --> C{Key age ><br/>rotationIntervalDays?}
    C -- No --> D[No-op]
    C -- Yes --> E[Generate new Ed25519 keypair]
    E --> F[Update Secret with new key<br/>set PREVIOUS_KEY_ID_LABEL]
    F --> G[ProducerPolicyReconciler<br/>triggered by Secret change]
    G --> H[Add new registry entry<br/>status=active]
    H --> I[Mark old entry<br/>status=rotating]
    I --> J[After grace period:<br/>expired → removed]
```

### Consumer Policy Reconciliation

When a `CloudEventSigningConsumerPolicy` CR is created or updated in a namespace:

```mermaid
flowchart TD
    A[ConsumerPolicy CR created/updated<br/>in namespace bu-bob] --> B[Create ServiceAccount<br/>ce-signing-verifier]
    B --> C[Create/Update Deployment ce-verifier<br/>MODE=verify]
    C --> D[Set env: TRUSTED_NAMESPACES, REJECT_UNSIGNED]
    D --> E[Create/Update Service ce-verifier]
    E --> F[Create ClusterRoleBinding<br/>ce-signing-verifier-ns]
    F --> G[For each consumer in spec.consumers]
    G --> H[For each trigger in consumer.triggers]
    H --> I[Create Knative Sequence<br/>steps: ce-verifier → subscriber]
    I --> J[Create Knative Trigger<br/>broker → Sequence<br/>with filter rules]
    J --> H
    H -- done --> G
    G -- done --> K[Update ConsumerPolicy status<br/>ready, verifyingProxyReady, triggersReady]
```

### Key Distribution (Registry Watch)

No reconciliation chain needed — verifiers watch both registries directly:

```mermaid
flowchart LR
    A[Operator] -- writes --> B[PublicKeyRegistry<br/>cluster-scoped singleton]
    C[Federation Controller] -- writes --> D[FederatedKeyRegistry<br/>one per remote cluster]

    B -- Fabric8 Informer --> E[Verifier Pod<br/>RegistryKeyCache]
    D -- Fabric8 Informer --> E

    E -- "merged cache<br/>keyed by (cluster, keyId)" --> F[Key lookup at<br/>verification time]

    style B fill:#f9f,stroke:#333
    style D fill:#f9f,stroke:#333
```

Key lifecycle in the registry:

```
active ──► rotating ──► expired ──► removed
          (grace period)
```

### Federation Reconciliation

When a `ClusterFederationConfig` CR is created or updated (opt-in, requires `federation.enabled=true` in Helm):

```mermaid
flowchart TD
    A[ClusterFederationConfig CR<br/>ce-signing-federation] --> B[For each remote cluster<br/>in spec.remoteClusters]
    B --> C[Read kubeconfig Secret]
    C --> D[Create remote KubernetesClient]
    D --> E[Establish Fabric8 Watch on<br/>remote PublicKeyRegistry]

    E --> F{Watch event<br/>add/update/delete}
    F --> G[Extract entries from<br/>remote registry]
    G --> H[Override cluster field<br/>to remote cluster name]
    H --> I[Validate PEM keys<br/>via PemValidator]
    I --> J[Create/Update local<br/>FederatedKeyRegistry<br/>named cluster-keys]
    J --> K[Update status:<br/>lastSyncTime, entryCount,<br/>conditions, invalidEntries]

    B --> L{Cluster removed<br/>from spec?}
    L --> M[Close Watch connection]
    M --> N[Delete FederatedKeyRegistry<br/>for removed cluster]
```

The federation controller runs as a separate Deployment (`OPERATOR_MODE=FEDERATION`). Metrics exposed:

| Metric | Type | Description |
|--------|------|-------------|
| `ce_signing_federation_watch_connected` | Gauge | 0/1 per remote cluster |
| `ce_signing_federation_remote_entries` | Gauge | Entry count per remote cluster |
| `ce_signing_federation_last_sync_seconds` | Gauge | Last sync timestamp |

---

## Data Plane Flow

### Signing Path (Producer Side)

```mermaid
sequenceDiagram
    participant App as Producer Pod
    participant Seq as Knative Sequence
    participant Signer as ce-signer proxy
    participant Broker as Target Broker

    App->>Seq: POST CloudEvent
    Seq->>Signer: Forward CE (step 1)

    Note over Signer: 1. Parse CloudEvent (binary or structured)
    Note over Signer: 2. Add cesignercluster extension (cluster identity)
    Note over Signer: 3. Filter canonical attributes (skip absent)
    Note over Signer: 4. Sort attributes lexicographically
    Note over Signer: 5. Build canonical form:<br/>attr=value\n... + data=(JCS if JSON)
    Note over Signer: 6. Sign canonical bytes with Ed25519 private key
    Note over Signer: 7. Add extensions:<br/>cesignature, cesignaturealg,<br/>cekeyid, cecanonattrs

    Signer-->>Seq: Return signed CE (HTTP response)
    Seq->>Broker: Route to reply destination
```

### Verification Path (Consumer Side)

```mermaid
sequenceDiagram
    participant Broker as Source Broker
    participant Trigger as Knative Trigger
    participant Seq as Knative Sequence
    participant Verifier as ce-verifier proxy
    participant Consumer as Consumer Pod

    Broker->>Trigger: Deliver event (filter match)
    Trigger->>Seq: Route to Sequence
    Seq->>Verifier: Forward CE (step 1)

    Note over Verifier: 1. Parse CloudEvent
    Note over Verifier: 2. Check all 5 signature extensions present?

    alt Unsigned event
        alt rejectUnsigned=true
            Verifier-->>Seq: 403 Forbidden
        else rejectUnsigned=false
            Verifier-->>Seq: Return CE as-is (warn)
            Seq->>Consumer: Forward to step 2
        end
    else Signed event
        Note over Verifier: 3. Lookup (cesignercluster, cekeyid)<br/>in RegistryKeyCache
        Note over Verifier: 4. Check namespace trust + key status
        Note over Verifier: 5. Read cecanonattrs from event
        Note over Verifier: 6. Rebuild canonical form from event
        Note over Verifier: 7. Verify Ed25519 signature

        alt Valid signature
            Verifier-->>Seq: 200 OK — return CE (signatures intact)
            Seq->>Consumer: Forward to step 2
        else Invalid signature
            Verifier-->>Seq: 403 Forbidden
        end
    end
```

### Verification Decision Tree

```mermaid
flowchart TD
    A[Incoming CloudEvent] --> B{All 5 signature extensions<br/>present?}

    B -- No --> C{rejectUnsigned?}
    C -- true --> D[403 Reject]
    C -- false --> E[Pass through as-is]

    B -- Yes --> F{"(cesignercluster, cekeyid)<br/>found in RegistryKeyCache?"}
    F -- No --> D

    F -- Yes --> G{Namespace in<br/>trustedNamespaces?}
    G -- No --> D

    G -- Yes --> H{Key status<br/>active or rotating?}
    H -- No --> D

    H -- Yes --> I[Rebuild canonical form<br/>from cecanonattrs + data]
    I --> J{Ed25519 signature<br/>valid?}
    J -- No --> D
    J -- Yes --> K[200 OK<br/>Return event with<br/>signatures intact]

    style D fill:#f66,color:#fff
    style K fill:#6f6
```

---

## End-to-End: UC1 Cross-BU Scenario

Alice (producer) to Bob (consumer) through an untrusted central broker, **same cluster**:

```mermaid
flowchart LR
    subgraph bu-alice [Namespace: bu-alice]
        A1[Producer Pod] --> A2[Knative Sequence<br/>order-service-signing-seq]
        A2 --> A3[ce-signer<br/>Deployment]
        A3 --> A2
    end

    subgraph central [Namespace: eventing-central]
        B1[org-wide-broker]
    end

    subgraph bu-bob [Namespace: bu-bob]
        C0[ingestion-broker] --> C1[Knative Trigger<br/>order-events]
        C1 --> C2[Knative Sequence<br/>ingest-verifier-order-events-seq]
        C2 --> C3[ce-verifier<br/>Deployment]
        C3 --> C2
        C2 --> C4[order-consumer<br/>Service]
    end

    A2 -- "signed CE<br/>(reply)" --> B1
    B1 -- "routed to<br/>bu-bob broker" --> C0

    subgraph cluster [Cluster-scoped]
        R[PublicKeyRegistry<br/>ce-signing-registry]
    end

    R -. "Fabric8 Informer" .-> C3

    style A3 fill:#4a9,color:#fff
    style C3 fill:#49a,color:#fff
    style R fill:#f9f,stroke:#333
```

**Signature extensions on the wire:**

| Extension | Value | Description |
|-----------|-------|-------------|
| `cesignature` | `<base64url 64 bytes>` | Ed25519 signature |
| `cesignaturealg` | `ed25519` | Algorithm |
| `cekeyid` | `bu-alice-v1` | Key lookup ID |
| `cecanonattrs` | `cesignercluster,datacontenttype,source,subject,type` | Signed attributes (sorted) |
| `cesignercluster` | `cluster-east` | Signing cluster identity |

These extensions survive any number of intermediate brokers and are **not stripped** by the verifier.

---

## End-to-End: UC2 Cross-Cluster Federation Scenario

Alice (producer in **cluster-east**) to Bob (consumer in **cluster-west**). The federation controller in cluster-west syncs Alice's public key so Bob's verifier can validate the signature:

```mermaid
flowchart TD
    subgraph cluster-east [Cluster: cluster-east]
        subgraph bu-alice-east [Namespace: bu-alice]
            E1[Producer Pod] --> E2[Knative Sequence]
            E2 --> E3[ce-signer]
            E3 --> E2
        end
        E2 -- "signed CE" --> E4[Broker]
        ER[PublicKeyRegistry<br/>ce-signing-registry]
        EOP[Operator<br/>LOCAL mode] -- writes --> ER
    end

    E4 -- "cross-cluster<br/>event routing" --> W4

    subgraph cluster-west [Cluster: cluster-west]
        subgraph bu-bob-west [Namespace: bu-bob]
            W4[Broker] --> W1[Knative Trigger]
            W1 --> W2[Knative Sequence]
            W2 --> W3[ce-verifier]
            W3 --> W2
            W2 --> W5[order-consumer<br/>Service]
        end
        WR[PublicKeyRegistry<br/>ce-signing-registry]
        WFR[FederatedKeyRegistry<br/>cluster-east-keys]
        WFC[ClusterFederationConfig<br/>ce-signing-federation]

        WOP[Operator<br/>LOCAL mode] -- writes --> WR
        WFED[Federation Controller<br/>FEDERATION mode] -- watches --> WFC
    end

    WFED -. "Fabric8 Watch<br/>(remote)" .-> ER
    WFED -- "sync entries" --> WFR
    WR -. "Fabric8 Informer" .-> W3
    WFR -. "Fabric8 Informer" .-> W3

    style E3 fill:#4a9,color:#fff
    style W3 fill:#49a,color:#fff
    style ER fill:#f9f,stroke:#333
    style WR fill:#f9f,stroke:#333
    style WFR fill:#f9f,stroke:#333
    style WFC fill:#fc9,stroke:#333
```

**Federation setup on cluster-west:**

```yaml
# 1. Kubeconfig Secret for remote access
apiVersion: v1
kind: Secret
metadata:
  name: cluster-east-kubeconfig
  namespace: ce-signing-system
type: Opaque
data:
  kubeconfig: <base64-encoded kubeconfig for cluster-east>
---
# 2. ClusterFederationConfig declaring the remote cluster
apiVersion: ce-signing.platform.io/v1alpha1
kind: ClusterFederationConfig
metadata:
  name: ce-signing-federation
spec:
  remoteClusters:
    - name: cluster-east
      kubeconfigSecretRef: cluster-east-kubeconfig
```

**Verification flow for cross-cluster events:**

1. Event arrives at cluster-west with `cesignercluster=cluster-east` and `cekeyid=bu-alice-v1`
2. Verifier looks up composite key `(cluster-east, bu-alice-v1)` in `RegistryKeyCache`
3. Cache contains the key because `FederatedKeyRegistry/cluster-east-keys` was synced by the federation controller
4. Signature is verified using the federated public key — **no direct cross-cluster call at verification time**

**Consistency model:** AP (Availability + Partition tolerance). During network partitions, verifiers serve from the last successfully synced `FederatedKeyRegistry`. Key revocations on the remote cluster propagate once connectivity is restored.

---

## Resource Topology Summary

```
Control Plane (operator — LOCAL mode):    Data Plane (proxies):
  Watches CRDs ──► Creates resources        Pure request → response
  Manages keys ──► Publishes to registry    No outbound HTTP calls
  Reconciles   ──► Updates status           Knative Sequences own delivery

Per producer namespace:                   Per consumer namespace:
  1 Secret (keypair)                        1 ServiceAccount (ce-signing-verifier)
  1 Deployment (ce-signer)                  1 ClusterRoleBinding
  1 Service                                 1 Deployment (ce-verifier)
  N Sequences (signer → reply)              1 Service
  1 HPA, 1 PDB, 1 ServiceMonitor           N Sequences (verifier → consumer)
                                            N Triggers (broker → Sequence)

Federation (opt-in — FEDERATION mode):
  1 Deployment (ce-signing-operator-federation)
  1 ServiceAccount + ClusterRoleBinding
  1 ClusterFederationConfig (singleton)
  N FederatedKeyRegistries (one per remote cluster)
```
