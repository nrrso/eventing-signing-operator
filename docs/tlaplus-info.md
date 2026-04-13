# Formal Verification with TLA+

This project uses [TLA+](https://lamport.azurewebsites.net/tla/tla.html) to formally verify the correctness of its reconciler logic. TLA+ is a mathematical language for specifying and model-checking concurrent systems — it exhaustively explores every possible interleaving of events to find bugs that are nearly impossible to trigger in tests.

## Why formal verification?

Kubernetes operators are inherently concurrent: multiple reconcilers run in parallel, the API server can reorder or retry requests, and shared resources (like the `PublicKeyRegistry`) are accessed by multiple writers. Race conditions in this space are subtle — they may require specific timing across namespaces, retry exhaustion during contention, or stale reads from snapshot-based reconciliation.

The TLA+ models in this project have **discovered and validated fixes for 4 concurrency bugs** that would be extremely difficult to reproduce in integration tests.

## Models overview

Four specifications live in `docs/tlaplus/`, each targeting a different aspect of the system:

```
docs/tlaplus/
├── cesigningreconciler/       System-level: registry protocol, multi-namespace contention
├── keyrotationreconciler/     Time-driven: rotation eligibility, premature rotation prevention
├── producerpolicyreconciler/  Snapshot semantics: stale reads, publish-before-clear ordering
└── consumerpolicyreconciler/  Multi-tenant: shared resource cleanup, collateral damage
```

### CeSigningReconciler — System-level protocol

**What it models:** The concurrency between ProducerPolicyReconciler (registry writer), KeyRotationReconciler (rotation trigger), and RegistryKeyCache (verifier-side Watch). Multiple namespaces contend for the shared `PublicKeyRegistry` using optimistic concurrency control (409 conflict + retry).

**What it checks:**
- At most one active key entry per namespace in the registry
- No orphaned entries after policy deletion cleanup
- Active key version always exceeds rotating key versions
- Verifier cache eventually converges with registry state

**Java counterparts:** `ProducerPolicyReconciler.java`, `KeyRotationReconciler.java`, `RegistryKeyCache.java`

### KeyRotation — Time-driven rotation safety

**What it models:** The complete `reconcile()` decision tree in KeyRotationReconciler — time advancing in abstract days, rotation eligibility checks (enabled? secret exists? key old enough?), and the interaction between time-driven rotation and the double-rotation fix.

**What it checks:**
- Keys are never rotated before `intervalDays` has elapsed
- Rotation never happens when disabled in the policy
- Enabled rotation with an expired key eventually triggers rotation
- The publish-then-clear workflow always completes

**Java counterpart:** `KeyRotationReconciler.java`

### ProducerPolicy — Secret snapshot stale reads

**What it models:** The ProducerPolicyReconciler reads the Secret once at reconcile start and uses that snapshot throughout — including across retries. This model removes the idle-guard that CeSigningReconciler uses, allowing KeyRotationReconciler to rotate mid-reconcile, exposing stale-read scenarios.

**What it checks:**
- Safety holds even when the snapshot is stale (rotation happened after read)
- `publishToRegistry()` always completes before `removePreviousKeyIdLabel()` (ordering invariant)
- Cleanup eventually completes even after retry exhaustion

**Java counterpart:** `ProducerPolicyReconciler.java` (reconcile with Secret read, modifier logic, label clearing)

### ConsumerPolicy — Multi-tenant shared resources

**What it models:** Multiple `ConsumerPolicy` resources in the same namespace sharing infrastructure (ClusterRoleBinding, Deployment). Models the cleanup lifecycle when one policy is deleted while another is still active.

**What it checks:**
- Shared ClusterRoleBinding is not deleted while other policies need it
- Cleanup of one policy does not delete another policy's Knative Sequences
- Surviving policies self-heal by re-creating deleted shared resources on next reconcile

**Java counterparts:** `ConsumerPolicyReconciler.java`, `VerifyingDeploymentDependentResource.java`

## Bugs found and fixed

Each bug was discovered by TLC (the TLA+ model checker) producing a concrete counterexample trace.

### 1. Orphaned registry entries on cleanup failure

**Bug:** When policy deletion cleanup exhausted its 409 retry limit, it returned `DeleteControl.defaultDelete()`, removing the finalizer while registry entries remained — permanently orphaned.

**Fix:** Cleanup returns `DeleteControl.noFinalizerRemoval()` on retry exhaustion, forcing a re-queue until cleanup succeeds.

**Model:** `CeSigningReconciler.tla` — `NoOrphanedEntries` invariant

### 2. Double-rotation leaves two active entries

**Bug:** If KeyRotationReconciler rotates v1->v2 and then v2->v3 before the ProducerPolicyReconciler publishes, the `previousKeyId` label is overwritten (v1 lost). When PPR finally publishes, it marks v2 as rotating and upserts v3 — but v1 is never touched, leaving two active entries.

**Fix:** Added a "stale active" step in the modifier — any active entry whose keyId differs from the current snapshot is marked rotating, catching entries orphaned by label overwrite.

**Model:** `CeSigningReconciler.tla` — `OneActivePerNamespace` invariant

### 3. Shared ClusterRoleBinding deleted during multi-tenant cleanup

**Bug:** When policy A is deleted, cleanup unconditionally deleted the namespace's shared ClusterRoleBinding — even though policy B still needs it.

**Fix:** Cleanup only deletes the ClusterRoleBinding if no other active policy exists in the namespace.

**Model:** `ConsumerPolicy.tla` — `CRBIntegrity` invariant

### 4. Label-based cleanup causes collateral Sequence deletion

**Bug:** Cleanup used label selectors to delete Knative resources, inadvertently deleting Sequences belonging to other policies with the same labels.

**Fix:** Cleanup deletes only the specific policy's Sequences by name, not by label selector.

**Model:** `ConsumerPolicy.tla` — `NoCollateralDamage` invariant

## How the models relate to code

The models operate at a higher abstraction level than the Java source:

| TLA+ concept | Java equivalent |
|---|---|
| Program counter (`pprPC`, `krrPC`) | JOSDK reconciler request queue — one action at a time per resource |
| `registry.version` / `producerSnap` | Kubernetes `resourceVersion` and 409 Conflict on stale write |
| `pprSecretSnap` | Secret read at reconcile start, cached for the entire reconciliation |
| `WF_vars(Action)` (weak fairness) | JOSDK's guaranteed re-queue on exception/backoff |
| `Namespaces = {"alice", "bob"}` | Concurrent reconcilers in different namespaces |
| Abstract `time` (days) | Real clock used by `KeyRotationReconciler` age check |

Concrete details (IP addresses, certificate bytes, JSON payloads) are abstracted away. Each `.tla` file contains comments referencing the specific Java source lines it models.

## Running the models

### Prerequisites

Install the TLA+ tools:
- **TLA+ Toolbox** — [standalone IDE with GUI](https://lamport.azurewebsites.net/tla/toolbox.html)
- **VS Code** — [TLA+ extension](https://marketplace.visualstudio.com/items?itemName=alygin.vscode-tlaplus)
- **Command line** — download `tla2tools.jar` from the [TLA+ releases](https://github.com/tlaplus/tlaplus/releases)

### Command line (TLC model checker)

```bash
# System-level model (2 namespaces, ~85K states)
java -jar tla2tools.jar -config CeSigningReconciler.cfg CeSigningReconciler.tla -workers auto

# 3-namespace contention test (~416K states)
java -jar tla2tools.jar -config CeSigningReconciler-3ns.cfg CeSigningReconciler.tla -workers auto

# Time-driven rotation (~11K states)
java -jar tla2tools.jar -config KeyRotation.cfg KeyRotation.tla -workers auto

# Secret snapshot stale reads
java -jar tla2tools.jar -config ProducerPolicy.cfg ProducerPolicy.tla -workers auto

# Multi-tenant shared resource cleanup
java -jar tla2tools.jar -config ConsumerPolicy.cfg ConsumerPolicy.tla -workers auto
```

Each command exhaustively checks all reachable states. The model checker reports either "Model checking completed. No error found." or a concrete counterexample trace showing the exact sequence of steps that violates a property.

### Configuration parameters

Each `.cfg` file defines constants that bound the state space:

| Parameter | Meaning | Typical value |
|---|---|---|
| `Namespaces` / `Policies` | Number of concurrent actors | 2-4 |
| `MaxKeyVersion` | Upper bound on key rotation count | 2-4 |
| `MaxRegistryRetries` | 409 conflict retry limit (matches Java `MAX_REGISTRY_RETRIES`) | 3 |
| `IntervalDays` | Rotation interval in abstract days | 2 |
| `MaxTime` | Time horizon for bounded model checking | 7 |

Increasing these values explores more behaviors but increases state space exponentially. The default configurations are tuned to complete in seconds to minutes.

## Reading the specifications

If you're new to TLA+, here's a quick orientation:

- **Variables** are the system state. Each step changes one or more variables.
- **Actions** (like `ProducerPublish`, `RotateKey`) describe possible state transitions. An action is a predicate over current and next state (`variable' = newValue`).
- **`Init`** defines the starting state. **`Next`** is the disjunction of all possible actions.
- **Invariants** (`INVARIANT` in `.cfg`) are predicates that must hold in every reachable state. TLC checks them exhaustively.
- **Temporal properties** (`PROPERTY` in `.cfg`) are formulas over execution traces — for example, "if X happens, Y eventually follows." These check liveness under fairness assumptions.
- **Fairness** (`WF_vars(Action)`) means "if an action is continuously enabled, it eventually happens" — modeling JOSDK's guaranteed re-queue behavior.

Each `.tla` file is self-contained with inline comments explaining the modeled behavior and referencing the corresponding Java source lines.
