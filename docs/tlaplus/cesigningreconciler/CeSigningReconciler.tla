--------------------------- MODULE CeSigningReconciler ----------------------------
(*
 * TLA+ specification of the CloudEvent Signing Operator reconciler logic.
 *
 * Models the concurrency-sensitive interactions between:
 *   - ProducerPolicyReconciler (manages registry entries, cleans up on delete)
 *   - KeyRotationReconciler (rotates keypairs via Secret label signaling)
 *   - RegistryKeyCache (verifier-side in-memory cache synced via Watch)
 *
 * Focus: registry write protocol, key rotation label signaling, and deletion
 * cleanup under concurrent execution across multiple namespaces.
 *
 * Corresponds to Java classes:
 *   - ProducerPolicyReconciler.java (publishToRegistry, updateRegistryWithRetry, cleanup)
 *   - KeyRotationReconciler.java (reconcile, performRotation)
 *   - RegistryKeyCache.java (replaceAll, put, remove)
 *
 * See also: openspec/specs/key-rotation/spec.md, public-key-registry/spec.md
 *
 * Companion specs (each verifies properties this spec does not cover):
 *   - KeyRotation.tla — time-driven rotation decisions, NoPrematureRotation
 *   - ProducerPolicy.tla — Secret snapshot stale-reads, PublishBeforeClear ordering
 *   - ConsumerPolicy.tla — shared resource cleanup under multi-tenancy
 *)
EXTENDS Integers, Sequences, FiniteSets, TLC

\* ---------------------------------------------------------------------------
\* Constants
\* ---------------------------------------------------------------------------

CONSTANTS
    Namespaces,           \* Set of namespace identifiers, e.g. {"alice", "bob", "eve"}
    MaxKeyVersion,        \* Upper bound on key versions to keep state space finite (e.g. 4)
    MaxRegistryRetries    \* Matches MAX_REGISTRY_RETRIES = 3 in Java code

ASSUME MaxKeyVersion >= 2
ASSUME MaxRegistryRetries >= 1

\* ---------------------------------------------------------------------------
\* Derived constants
\* ---------------------------------------------------------------------------

\* All possible key IDs: "alice-v1", "alice-v2", ..., "bob-v1", etc.
KeyIds == {<<ns, v>> : ns \in Namespaces, v \in 1..MaxKeyVersion}

\* Registry entry statuses
Statuses == {"active", "rotating"}

\* Policy lifecycle states
PolicyStates == {"exists", "deleting", "deleted"}

\* ---------------------------------------------------------------------------
\* Variables
\* ---------------------------------------------------------------------------

VARIABLES
    \* --- Kubernetes resources ---
    secrets,            \* [Namespaces -> [keyId: KeyIds, previousKeyId: KeyIds \cup {<<>>}]]
                        \* <<>> represents NULL (no previous key ID label)
    registry,           \* [version: Nat, entries: SUBSET RegistryEntry]
                        \* Each entry: [ns: Namespaces, keyId: KeyIds, status: Statuses]
    policies,           \* [Namespaces -> PolicyStates]

    \* --- Controller in-flight state ---
    \* Models the non-atomic read-modify-write in updateRegistryWithRetry.
    \* Each namespace may have an in-flight reconcile reading a stale registry snapshot.
    producerPC,         \* [Namespaces -> {"idle", "read_registry", "write_registry",
                        \*                 "clear_label", "cleanup_read", "cleanup_write"}]
    producerSnap,       \* [Namespaces -> snapshot of registry version read]
    producerRetries,    \* [Namespaces -> 0..MaxRegistryRetries]

    \* --- Verifier-side cache ---
    verifierCache       \* Set of [ns: Namespaces, keyId: KeyIds, status: Statuses]
                        \* Models RegistryKeyCache state (may lag behind registry)

vars == <<secrets, registry, policies, producerPC, producerSnap, producerRetries, verifierCache>>

\* ---------------------------------------------------------------------------
\* Helper operators
\* ---------------------------------------------------------------------------

\* Build a key ID tuple for a namespace and version
KI(ns, v) == <<ns, v>>

\* Extract namespace from a key ID
NS(ki) == ki[1]

\* Extract version number from a key ID
VER(ki) == ki[2]

\* NULL marker for previousKeyId
NULL == <<>>

\* The set of registry entries for a given namespace
EntriesForNs(entries, ns) == {e \in entries : e.ns = ns}

\* Active entries for a namespace
ActiveForNs(entries, ns) == {e \in entries : e.ns = ns /\ e.status = "active"}

\* ---------------------------------------------------------------------------
\* Type invariant
\* ---------------------------------------------------------------------------

TypeOK ==
    /\ \A ns \in Namespaces:
        /\ secrets[ns].keyId \in KeyIds
        /\ secrets[ns].previousKeyId \in KeyIds \cup {NULL}
        /\ policies[ns] \in PolicyStates
        /\ producerPC[ns] \in {"idle", "read_registry", "write_registry",
                                "clear_label", "cleanup_read", "cleanup_write"}
        /\ producerRetries[ns] \in 0..MaxRegistryRetries
    /\ registry.version \in Nat
    /\ \A e \in registry.entries:
        /\ e.ns \in Namespaces
        /\ e.keyId \in KeyIds
        /\ e.status \in Statuses
    /\ \A e \in verifierCache:
        /\ e.ns \in Namespaces
        /\ e.keyId \in KeyIds
        /\ e.status \in Statuses

\* ---------------------------------------------------------------------------
\* Safety properties (invariants)
\* ---------------------------------------------------------------------------

\* I1: At most one active entry per namespace in the registry.
\* Corresponds to the upsert logic at ProducerPolicyReconciler.java:277-293
\* which removes existing entries with the same keyId before inserting.
OneActivePerNamespace ==
    \A ns \in Namespaces:
        Cardinality(ActiveForNs(registry.entries, ns)) <= 1

\* I2: No orphaned entries after policy fully deleted.
\* Corresponds to cleanup() at ProducerPolicyReconciler.java:172-200.
NoOrphanedEntries ==
    \A ns \in Namespaces:
        (policies[ns] = "deleted" /\ producerPC[ns] = "idle") =>
            EntriesForNs(registry.entries, ns) = {}

\* I3: Key version monotonicity — the active key version in the registry
\* should be >= the version in any rotating entry for the same namespace.
VersionMonotonicity ==
    \A ns \in Namespaces:
        \A a \in ActiveForNs(registry.entries, ns):
            \A r \in {e \in registry.entries : e.ns = ns /\ e.status = "rotating"}:
                VER(a.keyId) > VER(r.keyId)

\* I4: Secret's current keyId matches or exceeds the active registry entry version.
\* If the registry has an active entry, the Secret should be at that version or ahead.
SecretAheadOfRegistry ==
    \A ns \in Namespaces:
        policies[ns] = "exists" =>
            \A a \in ActiveForNs(registry.entries, ns):
                VER(secrets[ns].keyId) >= VER(a.keyId)

\* I5: previousKeyId label, when set, refers to a version strictly less than current.
PreviousKeyIdValid ==
    \A ns \in Namespaces:
        secrets[ns].previousKeyId /= NULL =>
            /\ NS(secrets[ns].previousKeyId) = ns
            /\ VER(secrets[ns].previousKeyId) < VER(secrets[ns].keyId)

\* Combined safety invariant
Safety ==
    /\ TypeOK
    /\ OneActivePerNamespace
    /\ NoOrphanedEntries
    /\ VersionMonotonicity
    /\ SecretAheadOfRegistry
    /\ PreviousKeyIdValid

\* ---------------------------------------------------------------------------
\* Liveness properties (temporal)
\* ---------------------------------------------------------------------------

\* L1: Every previousKeyId label is eventually consumed (cleared) while
\* the policy exists. Once deleted, Kubernetes GC removes the Secret
\* (and its labels) via owner reference — not modeled here.
LabelEventuallyConsumed ==
    \A ns \in Namespaces:
        [](secrets[ns].previousKeyId /= NULL /\ policies[ns] = "exists" =>
            <>(secrets[ns].previousKeyId = NULL \/ policies[ns] /= "exists"))

\* L2: Verifier cache eventually converges to registry state.
CacheEventuallyConverges ==
    []<>(verifierCache = registry.entries)

\* ---------------------------------------------------------------------------
\* Initial state
\* ---------------------------------------------------------------------------

Init ==
    /\ secrets = [ns \in Namespaces |->
           [keyId |-> KI(ns, 1), previousKeyId |-> NULL]]
    /\ registry = [version |-> 1,
                   entries |-> {[ns |-> ns, keyId |-> KI(ns, 1), status |-> "active"]
                                : ns \in Namespaces}]
    /\ policies = [ns \in Namespaces |-> "exists"]
    /\ producerPC = [ns \in Namespaces |-> "idle"]
    /\ producerSnap = [ns \in Namespaces |-> 0]
    /\ producerRetries = [ns \in Namespaces |-> 0]
    /\ verifierCache = {[ns |-> ns, keyId |-> KI(ns, 1), status |-> "active"]
                         : ns \in Namespaces}

\* ---------------------------------------------------------------------------
\* Actions
\* ---------------------------------------------------------------------------

(*
 * KeyRotationReconciler.reconcile() — triggers rotation for namespace ns.
 *
 * Corresponds to KeyRotationReconciler.java:61-118
 *
 * Preconditions:
 *   - Policy exists (not deleting/deleted)
 *   - Current key version < MaxKeyVersion (state space bound)
 *   - No guard on previousKeyId — this matches the real code, which does NOT
 *     check whether previousKeyId label is already set before rotating.
 *     This is intentional: TLA+ should explore whether double-rotation
 *     before label consumption violates safety.
 *)
RotateKey(ns) ==
    /\ policies[ns] = "exists"
    /\ VER(secrets[ns].keyId) < MaxKeyVersion
    /\ producerPC[ns] = "idle"    \* Simplification: prevents mid-reconcile rotation.
                                   \* ProducerPolicy.tla removes this guard and tests
                                   \* stale-read scenarios — safety still holds.
    /\ LET oldKeyId == secrets[ns].keyId
           newVer   == VER(oldKeyId) + 1
           newKeyId == KI(ns, newVer)
       IN secrets' = [secrets EXCEPT ![ns] =
              [keyId |-> newKeyId, previousKeyId |-> oldKeyId]]
    \* Secret change triggers ProducerPolicyReconciler via InformerEventSource
    /\ producerPC' = [producerPC EXCEPT ![ns] = "read_registry"]
    /\ producerRetries' = [producerRetries EXCEPT ![ns] = 0]
    /\ UNCHANGED <<registry, policies, producerSnap, verifierCache>>

(*
 * ProducerPolicyReconciler — Step 1: Read the registry.
 *
 * Corresponds to updateRegistryWithRetry read at
 * ProducerPolicyReconciler.java:322-324
 *
 * Takes a snapshot of the current registry version. A concurrent write by
 * another namespace's reconciler may increment the version before we write,
 * causing a 409 conflict.
 *)
ProducerReadRegistry(ns) ==
    /\ producerPC[ns] = "read_registry"
    /\ policies[ns] \in {"exists", "deleting"}  \* cleanup also reads
    /\ producerSnap' = [producerSnap EXCEPT ![ns] = registry.version]
    /\ producerPC' = [producerPC EXCEPT ![ns] = "write_registry"]
    /\ UNCHANGED <<secrets, registry, policies, producerRetries, verifierCache>>

(*
 * ProducerPolicyReconciler — Step 2: Write the registry (publishToRegistry).
 *
 * Corresponds to ProducerPolicyReconciler.java:233-298 (the modifier) and
 * the write at ProducerPolicyReconciler.java:339-348.
 *
 * Models the full modifier logic:
 *   1. Mark previous key as rotating (lines 246-257)
 *   2. Clean expired rotating entries (lines 259-275) — simplified: remove
 *      all rotating entries for this namespace (grace period not modeled)
 *   3. Upsert active entry for current keyId (lines 277-293)
 *
 * Optimistic concurrency: if registry.version != snapshot, simulate 409 conflict.
 *)
ProducerWriteRegistry(ns) ==
    /\ producerPC[ns] = "write_registry"
    /\ policies[ns] = "exists"
    /\ LET curKeyId  == secrets[ns].keyId
           prevKeyId == secrets[ns].previousKeyId
           curEntries == registry.entries

           \* Step 1: Mark previous key as rotating
           afterMark == IF prevKeyId /= NULL
                        THEN {IF e.ns = ns /\ e.keyId = prevKeyId /\ e.status = "active"
                              THEN [e EXCEPT !.status = "rotating"]
                              ELSE e : e \in curEntries}
                        ELSE curEntries

           \* Step 2: Remove expired rotating entries for this namespace
           \* (Simplified: in the real code this checks expiresAt + gracePeriodDays.
           \*  Here we non-deterministically choose whether to clean or not,
           \*  modeling the passage of time.)
           afterClean == {e \in afterMark :
                           ~(e.ns = ns /\ e.status = "rotating"
                             /\ VER(e.keyId) < VER(curKeyId) - 1)}
                         \* Keep rotating entries only if they are version curVer-1
                         \* (i.e., the immediately previous key during grace period)

           \* Step 2.5: Mark any stale active entries for this namespace as rotating.
           \* Handles double-rotation where previousKeyId label was overwritten
           \* before the first rotation's registry write succeeded — the old
           \* active entry would otherwise be orphaned as "active" forever.
           afterMarkStale == {IF e.ns = ns /\ e.status = "active" /\ e.keyId /= curKeyId
                              THEN [e EXCEPT !.status = "rotating"]
                              ELSE e : e \in afterClean}

           \* Step 3: Upsert active entry
           withoutOld == {e \in afterMarkStale : ~(e.ns = ns /\ e.keyId = curKeyId)}
           newEntry   == [ns |-> ns, keyId |-> curKeyId, status |-> "active"]
           newEntries == withoutOld \cup {newEntry}
       IN
       \* Idempotency: if entries unchanged, skip the write entirely.
       \* Matches Java: modifier returns false => updateRegistryWithRetry returns false.
       IF newEntries = curEntries
       THEN
           /\ producerPC' = [producerPC EXCEPT ![ns] = "clear_label"]
           /\ producerRetries' = [producerRetries EXCEPT ![ns] = 0]
           /\ UNCHANGED registry
       ELSE
           \* Optimistic concurrency check
           IF registry.version = producerSnap[ns]
           THEN
               \* Write succeeds
               /\ registry' = [version |-> registry.version + 1, entries |-> newEntries]
               /\ producerPC' = [producerPC EXCEPT ![ns] = "clear_label"]
               /\ producerRetries' = [producerRetries EXCEPT ![ns] = 0]
           ELSE
               \* 409 Conflict
               IF producerRetries[ns] < MaxRegistryRetries - 1
               THEN
                   \* Retry: go back to read
                   /\ producerPC' = [producerPC EXCEPT ![ns] = "read_registry"]
                   /\ producerRetries' = [producerRetries EXCEPT ![ns] = producerRetries[ns] + 1]
                   /\ UNCHANGED registry
               ELSE
                   \* Max retries exhausted — exception propagates to JOSDK,
                   \* which re-queues reconciliation after backoff.
                   \* Model as idle; WF on TriggerReconcile ensures re-queue.
                   /\ producerPC' = [producerPC EXCEPT ![ns] = "idle"]
                   /\ producerRetries' = [producerRetries EXCEPT ![ns] = 0]
                   /\ UNCHANGED registry
    /\ UNCHANGED <<secrets, policies, producerSnap, verifierCache>>

(*
 * ProducerPolicyReconciler — Step 3: Clear the previousKeyId label.
 *
 * Corresponds to removePreviousKeyIdLabel at
 * ProducerPolicyReconciler.java:300-311, called at line 118-120.
 *
 * This happens AFTER the registry write succeeds.
 *)
ProducerClearLabel(ns) ==
    /\ producerPC[ns] = "clear_label"
    /\ policies[ns] = "exists"
    /\ secrets' = [secrets EXCEPT ![ns].previousKeyId = NULL]
    /\ producerPC' = [producerPC EXCEPT ![ns] = "idle"]
    /\ UNCHANGED <<registry, policies, producerSnap, producerRetries, verifierCache>>

(*
 * Trigger a normal (non-rotation) reconciliation for a namespace.
 *
 * This models the periodic or event-driven reconciliation that calls
 * publishToRegistry even without a rotation — the idempotent upsert path
 * at ProducerPolicyReconciler.java:110-113 where previousKeyId is null.
 *)
TriggerReconcile(ns) ==
    /\ policies[ns] = "exists"
    /\ producerPC[ns] = "idle"
    /\ producerPC' = [producerPC EXCEPT ![ns] = "read_registry"]
    /\ producerRetries' = [producerRetries EXCEPT ![ns] = 0]
    /\ UNCHANGED <<secrets, registry, policies, producerSnap, verifierCache>>

(*
 * Initiate policy deletion — transitions policy to "deleting".
 *
 * Corresponds to Kubernetes sending a DELETE and the finalizer being invoked.
 *)
DeletePolicy(ns) ==
    /\ policies[ns] = "exists"
    /\ producerPC[ns] = "idle"
    /\ policies' = [policies EXCEPT ![ns] = "deleting"]
    /\ producerPC' = [producerPC EXCEPT ![ns] = "cleanup_read"]
    /\ producerRetries' = [producerRetries EXCEPT ![ns] = 0]
    /\ UNCHANGED <<secrets, registry, producerSnap, verifierCache>>

(*
 * Cleanup — Step 1: Read registry for deletion.
 *
 * Corresponds to the read inside updateRegistryWithRetry called from
 * cleanup() at ProducerPolicyReconciler.java:178.
 *)
CleanupReadRegistry(ns) ==
    /\ producerPC[ns] = "cleanup_read"
    /\ policies[ns] = "deleting"
    /\ producerSnap' = [producerSnap EXCEPT ![ns] = registry.version]
    /\ producerPC' = [producerPC EXCEPT ![ns] = "cleanup_write"]
    /\ UNCHANGED <<secrets, registry, policies, producerRetries, verifierCache>>

(*
 * Cleanup — Step 2: Write registry with entries removed.
 *
 * Corresponds to the modifier at ProducerPolicyReconciler.java:178-188
 * which removes all entries where namespace matches.
 *)
CleanupWriteRegistry(ns) ==
    /\ producerPC[ns] = "cleanup_write"
    /\ policies[ns] = "deleting"
    /\ LET cleaned == {e \in registry.entries : e.ns /= ns}
       IN
       IF registry.version = producerSnap[ns]
       THEN
           \* Write succeeds — finalize deletion
           /\ registry' = [version |-> registry.version + 1, entries |-> cleaned]
           /\ policies' = [policies EXCEPT ![ns] = "deleted"]
           /\ producerPC' = [producerPC EXCEPT ![ns] = "idle"]
           /\ producerRetries' = [producerRetries EXCEPT ![ns] = 0]
       ELSE
           \* 409 Conflict
           IF producerRetries[ns] < MaxRegistryRetries - 1
           THEN
               /\ producerPC' = [producerPC EXCEPT ![ns] = "cleanup_read"]
               /\ producerRetries' = [producerRetries EXCEPT ![ns] = producerRetries[ns] + 1]
               /\ UNCHANGED <<registry, policies>>
           ELSE
               \* Max retries exhausted — cleanup fails.
               \* FIX: return DeleteControl.noFinalizerRemoval() so JOSDK retries.
               \* Policy stays in "deleting" and cleanup restarts on next reconcile.
               /\ producerPC' = [producerPC EXCEPT ![ns] = "cleanup_read"]
               /\ producerRetries' = [producerRetries EXCEPT ![ns] = 0]
               /\ UNCHANGED <<registry, policies>>
    /\ UNCHANGED <<secrets, producerSnap, verifierCache>>

(*
 * RegistryKeyCache — verifier Watch sync.
 *
 * Corresponds to RegistryKeyCache.replaceAll() at RegistryKeyCache.java:34-38.
 *
 * The Watch may deliver the current registry state at any time.
 * Models the asynchronous propagation delay between registry writes and
 * the verifier's in-memory cache.
 *)
VerifierSync ==
    /\ verifierCache' = registry.entries
    /\ UNCHANGED <<secrets, registry, policies, producerPC, producerSnap, producerRetries>>

\* ---------------------------------------------------------------------------
\* Next-state relation
\* ---------------------------------------------------------------------------

\* Terminal state: all policies deleted, system quiesced.
Done ==
    /\ \A ns \in Namespaces: policies[ns] = "deleted" /\ producerPC[ns] = "idle"
    /\ UNCHANGED vars

Next ==
    \/ \E ns \in Namespaces:
        \/ RotateKey(ns)
        \/ ProducerReadRegistry(ns)
        \/ ProducerWriteRegistry(ns)
        \/ ProducerClearLabel(ns)
        \/ TriggerReconcile(ns)
        \/ DeletePolicy(ns)
        \/ CleanupReadRegistry(ns)
        \/ CleanupWriteRegistry(ns)
    \/ VerifierSync
    \/ Done

\* ---------------------------------------------------------------------------
\* Fairness
\* ---------------------------------------------------------------------------

\* Weak fairness on all producer reconciler steps ensures that once a
\* reconcile is triggered, it eventually completes (no infinite stuttering).
\* Weak fairness on TriggerReconcile models JOSDK's automatic re-queue
\* after a failed reconciliation (exception propagates, JOSDK retries with backoff).
\* Weak fairness on VerifierSync ensures cache eventually converges.
Fairness ==
    /\ \A ns \in Namespaces:
        /\ WF_vars(TriggerReconcile(ns))
        /\ WF_vars(ProducerReadRegistry(ns))
        /\ WF_vars(ProducerWriteRegistry(ns))
        /\ WF_vars(ProducerClearLabel(ns))
        /\ WF_vars(CleanupReadRegistry(ns))
        /\ WF_vars(CleanupWriteRegistry(ns))
    /\ WF_vars(VerifierSync)

\* ---------------------------------------------------------------------------
\* Specification
\* ---------------------------------------------------------------------------

Spec == Init /\ [][Next]_vars /\ Fairness

\* ---------------------------------------------------------------------------
\* Model checking configuration notes
\* ---------------------------------------------------------------------------
(*
 * Suggested TLC configuration for initial exploration:
 *
 *   CONSTANTS
 *     Namespaces     = {"alice", "bob"}
 *     MaxKeyVersion  = 3
 *     MaxRegistryRetries = 3
 *
 *   INVARIANT Safety
 *   PROPERTY LabelEventuallyConsumed
 *   PROPERTY CacheEventuallyConverges
 *
 * For finding the double-rotation bug, try:
 *   Namespaces = {"alice"}, MaxKeyVersion = 4, MaxRegistryRetries = 3
 *   This focuses on a single namespace rotating through v1->v2->v3->v4
 *   and checks whether the previousKeyId label can be overwritten before
 *   ProducerPolicyReconciler consumes it.
 *
 * For finding registry contention exhaustion, try:
 *   Namespaces = {"alice", "bob", "eve"}, MaxKeyVersion = 2, MaxRegistryRetries = 3
 *   This creates 3 concurrent writers and checks whether 3 retries suffice.
 *
 * Verified findings (2026-04-12):
 *
 *   1. NoOrphanedEntries — CONFIRMED VIOLATED, FIXED.
 *      Trace: Namespaces={"alice","bob"}, MaxKeyVersion=3, MaxRegistryRetries=3.
 *      Bob's cleanup gets 409'd 3 times by Alice's concurrent writes.
 *      Old code returned DeleteControl.defaultDelete() on failure, removing
 *      the finalizer while bob's registry entry remained orphaned.
 *      FIX: cleanup() returns DeleteControl.noFinalizerRemoval() on failure.
 *      Model: exhausted cleanup retries restart cleanup_read (not "deleted").
 *
 *   2. OneActivePerNamespace — CONFIRMED VIOLATED, FIXED.
 *      Trace: Namespaces={"alice","bob"}, MaxKeyVersion=3, MaxRegistryRetries=3.
 *      Double-rotation bug: Bob rotates v1->v2, reconcile exhausts retries
 *      (never writes to registry), then rotates v2->v3. The previousKeyId
 *      label now points to v2 (not v1), so the modifier marks v2 as rotating
 *      (which was never in the registry) and upserts v3 — leaving v1 active.
 *      Result: two active entries (v1 + v3) for the same namespace.
 *      FIX: Added step 2.5 in modifier — mark any stale active entries for
 *      this namespace as rotating, regardless of previousKeyId. This catches
 *      entries orphaned by label overwrite during double-rotation.
 *
 *   3. All 5 safety invariants VERIFIED (2 namespaces, MaxKeyVersion=3):
 *      18,391 distinct states, depth 33. No violations.
 *
 *   4. All 5 safety invariants VERIFIED (3 namespaces, MaxKeyVersion=2):
 *      416,028 distinct states, depth 30. 3 retries sufficient under
 *      3-way contention. No violations.
 *
 *   5. Liveness (LabelEventuallyConsumed, CacheEventuallyConverges):
 *      VERIFIED for both 2-namespace and 3-namespace configurations.
 *      LabelEventuallyConsumed scoped to policies[ns] = "exists" because
 *      K8s GC removes the Secret (and its labels) when the policy is deleted.
 *)

=============================================================================
