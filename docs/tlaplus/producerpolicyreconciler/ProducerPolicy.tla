---------------------------- MODULE ProducerPolicy ----------------------------
\* TLA+ specification of the ProducerPolicyReconciler's reconcile workflow,
\* focusing on the Secret snapshot semantics and the ordering of operations.
\*
\* Key insight: PPR reads the Secret ONCE at reconcile start and uses those
\* values (keyId, previousKeyId) throughout, INCLUDING across retry attempts
\* in updateRegistryWithRetry. If KRR rotates mid-reconcile, PPR publishes
\* with stale values. The stale-active fix in the modifier ensures safety.
\*
\* Differentiates from CeSigningReconciler.tla:
\*   - Models Secret snapshot semantics (pprSecretSnap) vs. current-state reads
\*   - Allows KRR rotation concurrent with PPR mid-reconcile (no pprPC guard)
\*   - Verifies PublishBeforeClear workflow ordering invariant
\*   - Tests stale-read scenarios the system-level spec cannot reach
\*
\* Corresponds to Java: ProducerPolicyReconciler.java
\* Complements: CeSigningReconciler.tla (system-level registry protocol)
\*              KeyRotation.tla (time-based rotation lifecycle)

EXTENDS Integers, FiniteSets

\* ---------------------------------------------------------------------------
\* Constants
\* ---------------------------------------------------------------------------

CONSTANTS
    Namespaces,           \* Set of namespace identifiers, e.g. {"alice", "bob"}
    MaxKeyVersion,        \* Upper bound on key versions (e.g. 3)
    MaxRegistryRetries    \* Matches MAX_REGISTRY_RETRIES = 3 in Java code

ASSUME MaxKeyVersion >= 2
ASSUME MaxRegistryRetries >= 1

\* ---------------------------------------------------------------------------
\* Derived constants
\* ---------------------------------------------------------------------------

\* Key IDs as <<namespace, version>> tuples
KeyIds == {<<ns, v>> : ns \in Namespaces, v \in 1..MaxKeyVersion}

Statuses == {"active", "rotating"}
PolicyStates == {"exists", "deleting", "deleted"}

\* Sentinel for previousKeyId label not set
NULL == <<>>

\* ---------------------------------------------------------------------------
\* Variables
\* ---------------------------------------------------------------------------

VARIABLES
    \* --- Kubernetes resources ---
    secrets,            \* [Namespaces -> [keyId: KeyIds, previousKeyId: KeyIds \cup {NULL}]]
                        \* Modified by KRR (rotation) and PPR (clear label)
    registry,           \* [version: Nat, entries: SUBSET RegistryEntry]
                        \* Shared singleton PublicKeyRegistry with optimistic concurrency
    policies,           \* [Namespaces -> PolicyStates]

    \* --- PPR controller state ---
    pprPC,              \* [Namespaces -> PC states] reconcile workflow program counter
    pprSnap,            \* [Namespaces -> Nat] registry version snapshot for OCC
    pprRetries,         \* [Namespaces -> 0..MaxRegistryRetries]
    pprSecretSnap       \* [Namespaces -> [keyId: KeyIds, prevKeyId: KeyIds \cup {NULL}]]
                        \* Secret values captured at reconcile start, used throughout

vars == <<secrets, registry, policies, pprPC, pprSnap, pprRetries, pprSecretSnap>>

\* ---------------------------------------------------------------------------
\* Helper operators
\* ---------------------------------------------------------------------------

KI(ns, v) == <<ns, v>>
NS(ki) == ki[1]
VER(ki) == ki[2]

EntriesForNs(entries, ns) == {e \in entries : e.ns = ns}
ActiveForNs(entries, ns) == {e \in entries : e.ns = ns /\ e.status = "active"}

\* ---------------------------------------------------------------------------
\* Type invariant
\* ---------------------------------------------------------------------------

TypeOK ==
    /\ \A ns \in Namespaces:
        /\ secrets[ns].keyId \in KeyIds
        /\ secrets[ns].previousKeyId \in KeyIds \cup {NULL}
        /\ policies[ns] \in PolicyStates
        /\ pprPC[ns] \in {"idle", "read_secret", "publish_read", "publish_write",
                            "clear_label", "cleanup_read", "cleanup_write"}
        /\ pprRetries[ns] \in 0..MaxRegistryRetries
        /\ pprSecretSnap[ns].keyId \in KeyIds
        /\ pprSecretSnap[ns].prevKeyId \in KeyIds \cup {NULL}
    /\ registry.version \in Nat
    /\ \A e \in registry.entries:
        /\ e.ns \in Namespaces
        /\ e.keyId \in KeyIds
        /\ e.status \in Statuses

\* ---------------------------------------------------------------------------
\* Initial state
\* ---------------------------------------------------------------------------

\* Start from steady state: each namespace has v1 published and active.
Init ==
    /\ secrets = [ns \in Namespaces |->
           [keyId |-> KI(ns, 1), previousKeyId |-> NULL]]
    /\ registry = [version |-> 1,
                   entries |-> {[ns |-> ns, keyId |-> KI(ns, 1), status |-> "active"]
                                : ns \in Namespaces}]
    /\ policies = [ns \in Namespaces |-> "exists"]
    /\ pprPC = [ns \in Namespaces |-> "idle"]
    /\ pprSnap = [ns \in Namespaces |-> 0]
    /\ pprRetries = [ns \in Namespaces |-> 0]
    /\ pprSecretSnap = [ns \in Namespaces |->
           [keyId |-> KI(ns, 1), prevKeyId |-> NULL]]

\* ---------------------------------------------------------------------------
\* Environment actions
\* ---------------------------------------------------------------------------

\* KRR rotates key for namespace ns.
\* Source: KeyRotationReconciler.performRotation() at lines 120-154
\*
\* CRITICAL: No pprPC guard. KRR is a separate JOSDK controller (confirmed by
\* @ControllerConfiguration at KeyRotationReconciler.java:32) and can rotate
\* at any time, even while PPR is mid-reconcile. This tests the stale-read
\* scenario that CeSigningReconciler.tla cannot reach (it guards on idle).
RotateKey(ns) ==
    /\ policies[ns] = "exists"
    /\ VER(secrets[ns].keyId) < MaxKeyVersion
    /\ LET oldKeyId == secrets[ns].keyId
           newVer   == VER(oldKeyId) + 1
           newKeyId == KI(ns, newVer)
       IN secrets' = [secrets EXCEPT ![ns] =
              [keyId |-> newKeyId, previousKeyId |-> oldKeyId]]
    /\ UNCHANGED <<registry, policies, pprPC, pprSnap, pprRetries, pprSecretSnap>>

\* User deletes the ProducerPolicy, starting cleanup.
\* Source: Kubernetes DELETE + JOSDK finalizer invocation
DeletePolicy(ns) ==
    /\ policies[ns] = "exists"
    /\ pprPC[ns] = "idle"
    /\ policies' = [policies EXCEPT ![ns] = "deleting"]
    /\ pprPC' = [pprPC EXCEPT ![ns] = "cleanup_read"]
    /\ pprRetries' = [pprRetries EXCEPT ![ns] = 0]
    /\ UNCHANGED <<secrets, registry, pprSnap, pprSecretSnap>>

\* ---------------------------------------------------------------------------
\* PPR reconcile workflow
\* ---------------------------------------------------------------------------

\* Trigger reconcile (periodic JOSDK re-queue or InformerEventSource event).
\* Source: JOSDK event loop
TriggerReconcile(ns) ==
    /\ policies[ns] = "exists"
    /\ pprPC[ns] = "idle"
    /\ pprPC' = [pprPC EXCEPT ![ns] = "read_secret"]
    /\ pprRetries' = [pprRetries EXCEPT ![ns] = 0]
    /\ UNCHANGED <<secrets, registry, policies, pprSnap, pprSecretSnap>>

\* Read Secret and capture snapshot for the rest of this reconcile.
\* Source: reconcile() lines 82-106
\*
\* The snapshot (keyId, previousKeyId) is passed as method parameters to
\* publishToRegistry (line 110) and used for the clear_label decision
\* (line 118). These values are NOT re-read on retry within
\* updateRegistryWithRetry — confirmed at lines 234-235 (method signature)
\* and the lambda at lines 238-315 (closure over parameters).
ReadSecret(ns) ==
    /\ pprPC[ns] = "read_secret"
    /\ policies[ns] = "exists"
    /\ pprSecretSnap' = [pprSecretSnap EXCEPT ![ns] =
           [keyId |-> secrets[ns].keyId,
            prevKeyId |-> secrets[ns].previousKeyId]]
    /\ pprPC' = [pprPC EXCEPT ![ns] = "publish_read"]
    /\ UNCHANGED <<secrets, registry, policies, pprSnap, pprRetries>>

\* Read registry for optimistic concurrency check.
\* Source: updateRegistryWithRetry loop at lines 339-342
\*
\* Captures the registry version. A concurrent write by another namespace's
\* PPR may increment the version before we write, causing a 409 conflict.
PublishRead(ns) ==
    /\ pprPC[ns] = "publish_read"
    /\ policies[ns] = "exists"
    /\ pprSnap' = [pprSnap EXCEPT ![ns] = registry.version]
    /\ pprPC' = [pprPC EXCEPT ![ns] = "publish_write"]
    /\ UNCHANGED <<secrets, registry, policies, pprRetries, pprSecretSnap>>

\* Write registry with modifier logic using SNAPSHOT values.
\* Source: publishToRegistry modifier at lines 238-315,
\*         updateRegistryWithRetry write at lines 357-367
\*
\* Uses pprSecretSnap (NOT current secrets) for all modifier decisions.
\* This is the key semantic difference from CeSigningReconciler.tla.
\*
\* Modifier steps:
\*   Step 1: Mark snapshot previousKeyId as rotating (lines 248-257)
\*   Step 2: Remove expired rotating entries (lines 260-276, simplified)
\*   Step 2.5: Mark stale active entries as rotating (lines 278-293)
\*   Step 3: Upsert snapshot keyId as active (lines 295-311)
PublishWrite(ns) ==
    /\ pprPC[ns] = "publish_write"
    /\ policies[ns] = "exists"
    /\ LET snapKeyId  == pprSecretSnap[ns].keyId
           snapPrevId == pprSecretSnap[ns].prevKeyId
           curEntries == registry.entries

           \* Step 1: Mark previous key as rotating
           afterMark ==
               IF snapPrevId /= NULL
               THEN {IF e.ns = ns /\ e.keyId = snapPrevId /\ e.status = "active"
                     THEN [e EXCEPT !.status = "rotating"]
                     ELSE e : e \in curEntries}
               ELSE curEntries

           \* Step 2: Remove expired rotating entries (simplified:
           \* remove rotating entries more than 1 version behind current)
           afterClean == {e \in afterMark :
                           ~(e.ns = ns /\ e.status = "rotating"
                             /\ VER(e.keyId) < VER(snapKeyId) - 1)}

           \* Step 2.5: Mark stale active entries as rotating.
           \* Double-rotation fix: catches entries orphaned when KRR rotated
           \* twice before PPR consumed the previousKeyId label.
           afterMarkStale ==
               {IF e.ns = ns /\ e.status = "active" /\ e.keyId /= snapKeyId
                THEN [e EXCEPT !.status = "rotating"]
                ELSE e : e \in afterClean}

           \* Step 3: Upsert active entry for snapshot keyId
           withoutOld == {e \in afterMarkStale : ~(e.ns = ns /\ e.keyId = snapKeyId)}
           newEntry   == [ns |-> ns, keyId |-> snapKeyId, status |-> "active"]
           newEntries == withoutOld \cup {newEntry}
       IN
       \* Idempotency: if entries unchanged, skip the write.
       \* Matches Java: modifier returns false => updateRegistryWithRetry returns false.
       IF newEntries = curEntries
       THEN
           /\ pprPC' = [pprPC EXCEPT ![ns] =
                  IF snapPrevId /= NULL THEN "clear_label" ELSE "idle"]
           /\ pprRetries' = [pprRetries EXCEPT ![ns] = 0]
           /\ UNCHANGED registry
       ELSE
           \* Optimistic concurrency check
           IF registry.version = pprSnap[ns]
           THEN
               \* Write succeeds
               /\ registry' = [version |-> registry.version + 1,
                                entries |-> newEntries]
               /\ pprPC' = [pprPC EXCEPT ![ns] =
                      IF snapPrevId /= NULL THEN "clear_label" ELSE "idle"]
               /\ pprRetries' = [pprRetries EXCEPT ![ns] = 0]
           ELSE
               \* 409 Conflict (lines 368-375)
               IF pprRetries[ns] < MaxRegistryRetries - 1
               THEN
                   \* Retry: re-read registry, reuse same Secret snapshot
                   /\ pprPC' = [pprPC EXCEPT ![ns] = "publish_read"]
                   /\ pprRetries' = [pprRetries EXCEPT ![ns] =
                          pprRetries[ns] + 1]
                   /\ UNCHANGED registry
               ELSE
                   \* Max retries exhausted — exception propagates to JOSDK,
                   \* which calls updateErrorStatus and re-queues with backoff.
                   \* Model as idle; WF on TriggerReconcile ensures re-queue.
                   /\ pprPC' = [pprPC EXCEPT ![ns] = "idle"]
                   /\ pprRetries' = [pprRetries EXCEPT ![ns] = 0]
                   /\ UNCHANGED registry
    /\ UNCHANGED <<secrets, policies, pprSnap, pprSecretSnap>>

\* Clear previousKeyId label AFTER registry publish succeeds.
\* Source: removePreviousKeyIdLabel() at lines 318-329
\*
\* IMPORTANT: reads the Secret FRESH (lines 320-321: client.secrets()...get()),
\* NOT from the snapshot. Removes whatever label value is currently there.
\* If KRR rotated between PublishWrite and now, this removes the NEW
\* rotation's previousKeyId — the stale-active fix handles this on the
\* next reconcile.
ClearLabel(ns) ==
    /\ pprPC[ns] = "clear_label"
    /\ policies[ns] = "exists"
    /\ secrets' = [secrets EXCEPT ![ns].previousKeyId = NULL]
    /\ pprPC' = [pprPC EXCEPT ![ns] = "idle"]
    /\ UNCHANGED <<registry, policies, pprSnap, pprRetries, pprSecretSnap>>

\* ---------------------------------------------------------------------------
\* Cleanup actions (policy deletion)
\* ---------------------------------------------------------------------------

\* Cleanup Step 1: Read registry for deletion.
\* Source: cleanup() calling updateRegistryWithRetry at line 178
CleanupRead(ns) ==
    /\ pprPC[ns] = "cleanup_read"
    /\ policies[ns] = "deleting"
    /\ pprSnap' = [pprSnap EXCEPT ![ns] = registry.version]
    /\ pprPC' = [pprPC EXCEPT ![ns] = "cleanup_write"]
    /\ UNCHANGED <<secrets, registry, policies, pprRetries, pprSecretSnap>>

\* Cleanup Step 2: Write registry with entries removed.
\* Source: cleanup modifier at lines 178-188, write at lines 357-367
\*
\* On success: removes finalizer, policy transitions to "deleted".
\* On 409 exhaustion: returns DeleteControl.noFinalizerRemoval() (line 197),
\*   so JOSDK retries cleanup on next reconcile. Model: restart cleanup_read.
CleanupWrite(ns) ==
    /\ pprPC[ns] = "cleanup_write"
    /\ policies[ns] = "deleting"
    /\ LET cleaned == {e \in registry.entries : e.ns /= ns}
       IN
       IF registry.version = pprSnap[ns]
       THEN
           \* Write succeeds — finalize deletion
           /\ registry' = [version |-> registry.version + 1, entries |-> cleaned]
           /\ policies' = [policies EXCEPT ![ns] = "deleted"]
           /\ pprPC' = [pprPC EXCEPT ![ns] = "idle"]
           /\ pprRetries' = [pprRetries EXCEPT ![ns] = 0]
       ELSE
           \* 409 Conflict
           IF pprRetries[ns] < MaxRegistryRetries - 1
           THEN
               /\ pprPC' = [pprPC EXCEPT ![ns] = "cleanup_read"]
               /\ pprRetries' = [pprRetries EXCEPT ![ns] =
                      pprRetries[ns] + 1]
               /\ UNCHANGED <<registry, policies>>
           ELSE
               \* noFinalizerRemoval: stay deleting, restart cleanup.
               \* FIX from CeSigningReconciler.tla finding #1.
               /\ pprPC' = [pprPC EXCEPT ![ns] = "cleanup_read"]
               /\ pprRetries' = [pprRetries EXCEPT ![ns] = 0]
               /\ UNCHANGED <<registry, policies>>
    /\ UNCHANGED <<secrets, pprSnap, pprSecretSnap>>

\* ---------------------------------------------------------------------------
\* Next-state relation
\* ---------------------------------------------------------------------------

\* Terminal state: all policies deleted, no work left. Stutter to avoid deadlock.
Done ==
    /\ \A ns \in Namespaces: policies[ns] = "deleted" /\ pprPC[ns] = "idle"
    /\ UNCHANGED vars

Next ==
    \/ \E ns \in Namespaces:
        \/ RotateKey(ns)
        \/ DeletePolicy(ns)
        \/ TriggerReconcile(ns)
        \/ ReadSecret(ns)
        \/ PublishRead(ns)
        \/ PublishWrite(ns)
        \/ ClearLabel(ns)
        \/ CleanupRead(ns)
        \/ CleanupWrite(ns)
    \/ Done

\* ---------------------------------------------------------------------------
\* Safety invariants
\* ---------------------------------------------------------------------------

\* S1: At most one active entry per namespace in the registry.
\* The stale-active fix (Step 2.5 in PublishWrite) ensures this even when
\* PPR publishes with stale snapshot values after a mid-reconcile rotation.
OneActivePerNamespace ==
    \A ns \in Namespaces:
        Cardinality(ActiveForNs(registry.entries, ns)) <= 1

\* S2: No orphaned entries after policy fully deleted.
\* Cleanup with noFinalizerRemoval retry ensures all entries are removed.
NoOrphanedEntries ==
    \A ns \in Namespaces:
        (policies[ns] = "deleted" /\ pprPC[ns] = "idle") =>
            EntriesForNs(registry.entries, ns) = {}

\* S3: Active key version > all rotating key versions per namespace.
VersionMonotonicity ==
    \A ns \in Namespaces:
        \A a \in ActiveForNs(registry.entries, ns):
            \A r \in {e \in registry.entries : e.ns = ns /\ e.status = "rotating"}:
                VER(a.keyId) > VER(r.keyId)

\* S4: Secret's current keyId >= registry's active keyId.
SecretAheadOfRegistry ==
    \A ns \in Namespaces:
        policies[ns] = "exists" =>
            \A a \in ActiveForNs(registry.entries, ns):
                VER(secrets[ns].keyId) >= VER(a.keyId)

\* S5: previousKeyId label is valid when set.
PreviousKeyIdValid ==
    \A ns \in Namespaces:
        secrets[ns].previousKeyId /= NULL =>
            /\ NS(secrets[ns].previousKeyId) = ns
            /\ VER(secrets[ns].previousKeyId) < VER(secrets[ns].keyId)

\* S6: Workflow ordering — when PPR is about to clear the label,
\* the snapshot key has already been published as active in the registry.
\* This verifies the critical ordering: publishToRegistry BEFORE
\* removePreviousKeyIdLabel (lines 110-113 before lines 118-120).
PublishBeforeClear ==
    \A ns \in Namespaces:
        pprPC[ns] = "clear_label" =>
            \E e \in registry.entries:
                /\ e.ns = ns
                /\ e.keyId = pprSecretSnap[ns].keyId
                /\ e.status = "active"

\* Combined safety invariant
Safety ==
    /\ TypeOK
    /\ OneActivePerNamespace
    /\ NoOrphanedEntries
    /\ VersionMonotonicity
    /\ SecretAheadOfRegistry
    /\ PreviousKeyIdValid
    /\ PublishBeforeClear

\* ---------------------------------------------------------------------------
\* Liveness properties
\* ---------------------------------------------------------------------------

\* L1: Every previousKeyId label is eventually consumed while
\* the policy exists. Once deleted, K8s GC removes the Secret.
LabelEventuallyConsumed ==
    \A ns \in Namespaces:
        [](secrets[ns].previousKeyId /= NULL /\ policies[ns] = "exists" =>
            <>(secrets[ns].previousKeyId = NULL \/ policies[ns] /= "exists"))

\* L2: Policy deletion eventually completes (cleanup succeeds).
\* The noFinalizerRemoval retry ensures eventual completion.
CleanupEventuallyCompletes ==
    \A ns \in Namespaces:
        [](policies[ns] = "deleting" => <>(policies[ns] = "deleted"))

\* ---------------------------------------------------------------------------
\* Fairness
\* ---------------------------------------------------------------------------

\* Weak fairness on all PPR workflow steps ensures reconcile completes.
\* WF on TriggerReconcile models JOSDK's automatic re-queue after failure.
\* NO fairness on RotateKey or DeletePolicy (environment actions).
Fairness ==
    \A ns \in Namespaces:
        /\ WF_vars(TriggerReconcile(ns))
        /\ WF_vars(ReadSecret(ns))
        /\ WF_vars(PublishRead(ns))
        /\ WF_vars(PublishWrite(ns))
        /\ WF_vars(ClearLabel(ns))
        /\ WF_vars(CleanupRead(ns))
        /\ WF_vars(CleanupWrite(ns))

\* ---------------------------------------------------------------------------
\* Specification
\* ---------------------------------------------------------------------------

Spec == Init /\ [][Next]_vars /\ Fairness

\* ---------------------------------------------------------------------------
\* Model checking configuration
\* ---------------------------------------------------------------------------
\* Suggested TLC configuration:
\*
\*   CONSTANTS
\*     Namespaces        = {"alice", "bob"}
\*     MaxKeyVersion     = 3
\*     MaxRegistryRetries = 3
\*
\*   INVARIANT Safety
\*   PROPERTY LabelEventuallyConsumed
\*   PROPERTY CleanupEventuallyCompletes
\*
\* Stale-read scenarios to explore:
\*   1. KRR rotates while PPR is in "publish_read" or "publish_write"
\*      → PPR publishes old key, next reconcile corrects
\*   2. KRR rotates twice while PPR is in any step
\*      → PPR snapshot misses one rotation, stale-active fix compensates
\*   3. KRR rotates between PublishWrite and ClearLabel
\*      → ClearLabel removes new rotation's signal, next reconcile re-reads
\*
\* To verify the stale-active fix is necessary: remove afterMarkStale from
\* PublishWrite and check OneActivePerNamespace — TLC should find a
\* counterexample with two active entries in the same namespace.
\*
\* To see the stale-read scenario in a trace: add ALIAS or use TLC trace
\* explorer, look for states where pprSecretSnap[ns].keyId /= secrets[ns].keyId
\* while pprPC[ns] \in {"publish_read", "publish_write", "clear_label"}.

=============================================================================
