# CloudEvent Signing Platform — Control Plane & Data Plane Flows

## Overview

The platform has two distinct planes:

- **Control Plane**: The operator watches CRDs and reconciles Kubernetes resources (keys, deployments, services, Knative resources, registry entries).
- **Data Plane**: The signing and verifying proxies process CloudEvents inline via Knative Sequences. Pure request-response — no outbound HTTP calls.

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

    B -- Yes --> F{Key rotation due?}
    F -- No --> E
    F -- Yes --> G[Generate new keypair]
    G --> H[Update Secret with new key]
    H --> I[Add new registry entry status=active]
    I --> J[Mark old entry status=rotating]
    J --> K[After grace period: expired → remove]

    E --> L[Create/Update Deployment ce-signer<br/>MODE=sign, mount Secret]
    L --> M[Create/Update Service ce-signer]
    M --> N[For each producer in spec.producers]
    N --> O[Create Knative Sequence<br/>step: ce-signer service<br/>reply: target broker]
    O --> P[Create HPA + PDB + ServiceMonitor]
    P --> Q[Update ProducerPolicy status<br/>ready, keyId, signingEndpoint]
```

### Consumer Policy Reconciliation

When a `CloudEventSigningConsumerPolicy` CR is created or updated in a namespace:

```mermaid
flowchart TD
    A[ConsumerPolicy CR created/updated<br/>in namespace bu-bob] --> B[Create/Update Deployment ce-verifier<br/>MODE=verify]
    B --> C[Set env: TRUSTED_NAMESPACES, REJECT_UNSIGNED]
    C --> D[Create/Update Service ce-verifier]
    D --> E[Create HPA + PDB + ServiceMonitor]
    E --> F[For each consumer in spec.consumers]
    F --> G[For each trigger in consumer.triggers]
    G --> H[Create Knative Sequence<br/>steps: ce-verifier → subscriber]
    H --> I[Create Knative Trigger<br/>broker → Sequence<br/>with filter rules]
    I --> G
    G -- done --> F
    F -- done --> J[Update ConsumerPolicy status<br/>ready, verifyingProxyReady, triggersReady]
```

### Key Distribution (Registry Watch)

No reconciliation chain needed — verifiers watch the registry directly:

```mermaid
flowchart LR
    A[Operator] -- writes --> B[PublicKeyRegistry<br/>cluster-scoped singleton]
    B -- Fabric8 Watch --> C[Verifier Pod 1<br/>RegistryKeyCache]
    B -- Fabric8 Watch --> D[Verifier Pod 2<br/>RegistryKeyCache]
    B -- Fabric8 Watch --> E[Verifier Pod N<br/>RegistryKeyCache]

    style B fill:#f9f,stroke:#333
```

Key lifecycle in the registry:

```
active ──► rotating ──► expired ──► removed
          (grace period)
```

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
    Note over Signer: 2. Filter canonical attributes (skip absent)
    Note over Signer: 3. Sort attributes lexicographically
    Note over Signer: 4. Build canonical form:<br/>attr=value\n... + data=(JCS if JSON)
    Note over Signer: 5. Sign canonical bytes with Ed25519 private key
    Note over Signer: 6. Add extensions:<br/>cesignature, cesignaturealg,<br/>cekeyid, cecanonattrs

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
    Note over Verifier: 2. Check all 4 signature extensions present?

    alt Unsigned event
        alt rejectUnsigned=true
            Verifier-->>Seq: 403 Forbidden
        else rejectUnsigned=false
            Verifier-->>Seq: Return CE as-is (warn)
            Seq->>Consumer: Forward to step 2
        end
    else Signed event
        Note over Verifier: 3. Lookup cekeyid in RegistryKeyCache
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
    A[Incoming CloudEvent] --> B{All 4 signature extensions<br/>present?}

    B -- No --> C{rejectUnsigned?}
    C -- true --> D[403 Reject]
    C -- false --> E[Pass through as-is]

    B -- Yes --> F{cekeyid found<br/>in RegistryKeyCache?}
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

Alice (producer) to Bob (consumer) through an untrusted central broker:

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

    R -. "Fabric8 Watch" .-> C3

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
| `cecanonattrs` | `datacontenttype,source,subject,type` | Signed attributes (sorted) |

These extensions survive any number of intermediate brokers and are **not stripped** by the verifier.

---

## Resource Topology Summary

```
Control Plane (operator):                Data Plane (proxies):
  Watches CRDs ──► Creates resources       Pure request → response
  Manages keys ──► Publishes to registry   No outbound HTTP calls
  Reconciles   ──► Updates status          Knative Sequences own delivery

Per producer namespace:                  Per consumer namespace:
  1 Secret (keypair)                       1 Deployment (ce-verifier)
  1 Deployment (ce-signer)                 1 Service
  1 Service                                N Sequences (verifier → consumer)
  N Sequences (signer → reply)             N Triggers (broker → Sequence)
  1 HPA, 1 PDB, 1 ServiceMonitor          1 HPA, 1 PDB, 1 ServiceMonitor
```
