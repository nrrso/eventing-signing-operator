------------------------------ MODULE KeyRotation ------------------------------
(*
 * TLA+ specification of the KeyRotationReconciler's time-based key rotation
 * lifecycle and its signaling protocol with ProducerPolicyReconciler (PPR).
 *
 * Focuses on aspects NOT covered by CeSigningReconciler.tla:
 *   - Time-driven rotation decisions (key age vs. intervalDays)
 *   - The reconcile() decision tree (disabled, no secret, too young, rotate)
 *   - No-premature-rotation safety (keys only rotate when old enough)
 *   - Liveness: enabled rotation + old key => eventual rotation
 *   - The double-rotation scenario's interaction with time
 *
 * Complements CeSigningReconciler.tla which models registry write protocol,
 * optimistic concurrency, and multi-namespace contention.
 *
 * Corresponds to Java: KeyRotationReconciler.java
 *)
EXTENDS Naturals, FiniteSets

\* ---------------------------------------------------------------------------
\* Constants
\* ---------------------------------------------------------------------------

CONSTANTS
    IntervalDays,      \* Rotation interval from KeyRotationPolicy.intervalDays (e.g., 2)
    MaxTime,           \* Upper bound on time in abstract days (for model checking)
    MaxKeyVersion      \* Upper bound on key versions (for model checking)

ASSUME IntervalDays >= 1
ASSUME MaxTime >= 2 * IntervalDays + 1   \* Enough time for at least 2 full rotation cycles
ASSUME MaxKeyVersion >= 3                 \* Enough versions to expose double-rotation

\* Sentinel: previousKeyId label not set
NULL == 0

\* ---------------------------------------------------------------------------
\* Variables
\* ---------------------------------------------------------------------------

VARIABLES
    time,              \* Current time in abstract days (0..MaxTime)
    rotationEnabled,   \* BOOLEAN: policy.spec.keyRotation.enabled (line 72)
    secretReady,       \* BOOLEAN: SecretDependentResource has created the initial Secret

    \* --- Secret state (shared mutable resource between KRR and PPR) ---
    keyId,             \* Current key version in Secret (1..MaxKeyVersion)
    keyCreatedAt,      \* Day the current key was created (0..MaxTime)
    previousKeyId,     \* Rotation signal label (NULL = not set, else old version)

    \* --- Registry state (written by PPR) ---
    registryEntries,   \* Set of [keyId: 1..MaxKeyVersion, status: {"active","rotating"}]

    \* --- Controller program counters ---
    krrPC,             \* KeyRotationReconciler: "idle" | "reconciling"
    pprPC              \* ProducerPolicyReconciler (simplified): "idle" | "publishing" | "clearing"

vars == <<time, rotationEnabled, secretReady, keyId, keyCreatedAt,
          previousKeyId, registryEntries, krrPC, pprPC>>

\* ---------------------------------------------------------------------------
\* Helper operators
\* ---------------------------------------------------------------------------

ActiveEntries  == {e \in registryEntries : e.status = "active"}
RotatingEntries == {e \in registryEntries : e.status = "rotating"}

\* ---------------------------------------------------------------------------
\* Type invariant
\* ---------------------------------------------------------------------------

TypeOK ==
    /\ time \in 0..MaxTime
    /\ rotationEnabled \in BOOLEAN
    /\ secretReady \in BOOLEAN
    /\ keyId \in 1..MaxKeyVersion
    /\ keyCreatedAt \in 0..MaxTime
    /\ keyCreatedAt <= time
    /\ previousKeyId \in 0..MaxKeyVersion
    /\ \A e \in registryEntries:
        /\ e.keyId \in 1..MaxKeyVersion
        /\ e.status \in {"active", "rotating"}
    /\ krrPC \in {"idle", "reconciling"}
    /\ pprPC \in {"idle", "publishing", "clearing"}

\* ---------------------------------------------------------------------------
\* Initial state
\* ---------------------------------------------------------------------------

Init ==
    /\ time = 0
    /\ rotationEnabled = TRUE
    /\ secretReady = FALSE             \* Secret not yet created by SecretDependentResource
    /\ keyId = 1
    /\ keyCreatedAt = 0
    /\ previousKeyId = NULL
    /\ registryEntries = {}            \* Empty until PPR first publishes
    /\ krrPC = "idle"
    /\ pprPC = "idle"

\* ---------------------------------------------------------------------------
\* Actions
\* ---------------------------------------------------------------------------

(*
 * Time advances by one day.
 * Abstracting the real 1-hour timer interval to days, since the rotation
 * interval (intervalDays) is measured in days.
 *)
Tick ==
    /\ time < MaxTime
    /\ time' = time + 1
    /\ UNCHANGED <<rotationEnabled, secretReady, keyId, keyCreatedAt,
                   previousKeyId, registryEntries, krrPC, pprPC>>

(*
 * SecretDependentResource creates the initial Secret with keypair.
 * Source: SecretDependentResource.desired() — creates Secret with
 *         KEY_ID_LABEL, CREATED_AT_LABEL, and Ed25519 keypair.
 * After this, KRR can read the Secret and PPR can publish to registry.
 *)
CreateSecret ==
    /\ ~secretReady
    /\ secretReady' = TRUE
    /\ pprPC' = "publishing"           \* Secret creation triggers PPR via informer
    /\ UNCHANGED <<time, rotationEnabled, keyId, keyCreatedAt,
                   previousKeyId, registryEntries, krrPC>>

(*
 * User toggles rotation enabled/disabled in the policy spec.
 * Source: kubectl edit CloudEventSigningProducerPolicy — changes
 *         spec.keyRotation.enabled between true and false.
 * No fairness: may never happen (environment action).
 *)
ToggleRotation ==
    /\ rotationEnabled' = ~rotationEnabled
    /\ UNCHANGED <<time, secretReady, keyId, keyCreatedAt, previousKeyId,
                   registryEntries, krrPC, pprPC>>

(*
 * KRR: Timer fires (or informer event), reconcile starts.
 * Source: timerEventSource.scheduleOnce(resource, DEFAULT_CHECK_INTERVAL_MS)
 *         at KeyRotationReconciler.java:70
 *
 * JOSDK serializes reconcile calls per controller per resource, so only
 * one KRR reconcile runs at a time for a given ProducerPolicy resource.
 *)
KRR_Start ==
    /\ krrPC = "idle"
    /\ krrPC' = "reconciling"
    /\ UNCHANGED <<time, rotationEnabled, secretReady, keyId, keyCreatedAt,
                   previousKeyId, registryEntries, pprPC>>

(*
 * KRR: Full reconcile decision + rotation (atomic from JOSDK perspective).
 * Source: KeyRotationReconciler.reconcile() at lines 62-118
 *
 * Models the complete decision tree:
 *   Line 72:  if (!rotation.isEnabled()) return noUpdate
 *   Line 78:  if (secretOpt.isEmpty()) return noUpdate
 *   Line 98:  if (daysOld < rotation.getIntervalDays()) return noUpdate
 *   Line 106: performRotation(namespace, currentKeyId, secret)
 *
 * performRotation() (lines 120-154):
 *   - Generates new Ed25519 keypair via KeyPairGenerator.generate()
 *   - Increments keyId via incrementKeyId() (lines 156-169)
 *   - Builds new Secret with previousKeyId label set to old keyId
 *   - Applies via serverSideApply (atomic K8s API call)
 *
 * The entire reconcile is serialized by JOSDK, so the read-decide-write
 * sequence is atomic with respect to other KRR invocations.
 *)
KRR_Reconcile ==
    /\ krrPC = "reconciling"
    /\ IF /\ rotationEnabled                        \* Line 72: check enabled
          /\ secretReady                             \* Line 78: check secret exists
          /\ keyId < MaxKeyVersion                   \* State space bound
          /\ time - keyCreatedAt >= IntervalDays     \* Line 98: check key age
       THEN  \* --- Rotation needed (lines 103-114) ---
           /\ keyId' = keyId + 1                     \* incrementKeyId()
           /\ keyCreatedAt' = time                   \* LabelSafeTimestamp.encode(now)
           /\ previousKeyId' = keyId                 \* Set rotation signal for PPR
           /\ krrPC' = "idle"
           \* Secret change triggers PPR via InformerEventSource<Secret>
           /\ IF pprPC = "idle"
              THEN pprPC' = "publishing"
              ELSE UNCHANGED pprPC                   \* PPR already in-flight; will re-queue
       ELSE  \* --- No rotation: one of the early returns (noUpdate) ---
           /\ krrPC' = "idle"
           /\ UNCHANGED <<keyId, keyCreatedAt, previousKeyId, pprPC>>
    /\ UNCHANGED <<time, rotationEnabled, secretReady, registryEntries>>

(*
 * PPR: Publish current key to registry.
 * Source: ProducerPolicyReconciler.publishToRegistry()
 *
 * Simplified modifier logic (full version in CeSigningReconciler.tla):
 *   Step 1: Mark previousKeyId entry as "rotating" (lines 246-257)
 *   Step 2: Mark stale active entries as "rotating" (double-rotation fix)
 *   Step 3: Upsert current keyId as "active" (lines 277-293)
 *
 * Simplification: reads current Secret state (not a snapshot from trigger
 * time). This is safe because the modifier logic handles any state.
 *)
PPR_Publish ==
    /\ pprPC = "publishing"
    /\ LET curKeyId  == keyId
           prevKeyId == previousKeyId

           \* Step 1: Mark previous key as rotating (if label set and entry exists)
           afterMarkPrev ==
               IF prevKeyId /= NULL
               THEN {IF e.keyId = prevKeyId /\ e.status = "active"
                     THEN [e EXCEPT !.status = "rotating"]
                     ELSE e : e \in registryEntries}
               ELSE registryEntries

           \* Step 2: Mark any stale active entries as rotating.
           \* This is the double-rotation fix: if KRR rotated twice before PPR
           \* consumed the label, the first rotation's entry would be orphaned
           \* as "active" forever. This step catches it.
           afterMarkStale ==
               {IF e.status = "active" /\ e.keyId /= curKeyId
                THEN [e EXCEPT !.status = "rotating"]
                ELSE e : e \in afterMarkPrev}

           \* Step 3: Upsert current keyId as active
           withoutCur == {e \in afterMarkStale : e.keyId /= curKeyId}
           newEntry   == [keyId |-> curKeyId, status |-> "active"]
       IN
       /\ registryEntries' = withoutCur \cup {newEntry}
    /\ pprPC' = IF previousKeyId /= NULL THEN "clearing" ELSE "idle"
    /\ UNCHANGED <<time, rotationEnabled, secretReady, keyId, keyCreatedAt,
                   previousKeyId, krrPC>>

(*
 * PPR: Clear the previousKeyId label on the Secret.
 * Source: removePreviousKeyIdLabel() at ProducerPolicyReconciler.java
 *
 * Completes the rotation signaling cycle:
 *   KRR sets label → PPR reads → PPR publishes to registry → PPR clears label
 *)
PPR_Clear ==
    /\ pprPC = "clearing"
    /\ previousKeyId' = NULL
    /\ pprPC' = "idle"
    /\ UNCHANGED <<time, rotationEnabled, secretReady, keyId, keyCreatedAt,
                   registryEntries, krrPC>>

(*
 * PPR: Periodic (non-rotation) reconcile trigger.
 * Source: JOSDK periodic re-queue or InformerEventSource event
 *
 * PPR may reconcile at any time. The publish is idempotent: if the
 * registry already has the correct active entry, no change occurs.
 *)
PPR_Trigger ==
    /\ pprPC = "idle"
    /\ secretReady
    /\ pprPC' = "publishing"
    /\ UNCHANGED <<time, rotationEnabled, secretReady, keyId, keyCreatedAt,
                   previousKeyId, registryEntries, krrPC>>

\* ---------------------------------------------------------------------------
\* Next-state relation
\* ---------------------------------------------------------------------------

Next ==
    \/ Tick
    \/ CreateSecret
    \/ ToggleRotation
    \/ KRR_Start
    \/ KRR_Reconcile
    \/ PPR_Publish
    \/ PPR_Clear
    \/ PPR_Trigger

\* ---------------------------------------------------------------------------
\* Safety invariants (state predicates, checked with INVARIANT)
\* ---------------------------------------------------------------------------

(*
 * S1: At most one active entry in the registry.
 * The double-rotation fix (Step 2 in PPR_Publish) ensures this.
 *
 * Without the fix, double-rotation produces:
 *   1. KRR rotates v1→v2 (previousKeyId=v1)
 *   2. PPR fails or is slow
 *   3. KRR rotates v2→v3 (previousKeyId=v2, overwriting v1)
 *   4. PPR publishes: marks v2 rotating, v3 active — but v1 stays active!
 *   → Two active entries: v1 and v3. VIOLATION.
 *
 * The stale-active fix catches v1 in step 4 and marks it rotating.
 *)
OneActiveEntry ==
    Cardinality(ActiveEntries) <= 1

(*
 * S2: previousKeyId label is valid when set.
 * Set by performRotation: previousKeyId' = keyId (old), keyId' = keyId + 1 (new).
 * Cleared by PPR_Clear. Never set to a value >= current keyId.
 *)
PreviousKeyIdValid ==
    previousKeyId /= NULL =>
        /\ previousKeyId >= 1
        /\ previousKeyId < keyId

(*
 * S3: Key was created no later than current time.
 * performRotation sets keyCreatedAt = time.
 *)
KeyCreatedInPast ==
    keyCreatedAt <= time

(*
 * S4: Active registry entry version never exceeds the Secret's current key.
 * The Secret is the source of truth; registry may lag behind after rotation.
 *)
RegistryNotAhead ==
    \A e \in ActiveEntries : e.keyId <= keyId

(*
 * S5: Active entry version > all rotating entry versions.
 * Active key is always the newest; older keys transition to rotating.
 *)
VersionMonotonicity ==
    \A a \in ActiveEntries :
        \A r \in RotatingEntries :
            a.keyId > r.keyId

\* Combined safety invariant
Safety ==
    /\ TypeOK
    /\ OneActiveEntry
    /\ PreviousKeyIdValid
    /\ KeyCreatedInPast
    /\ RegistryNotAhead
    /\ VersionMonotonicity

\* ---------------------------------------------------------------------------
\* Safety properties (action constraints, checked with PROPERTY)
\* ---------------------------------------------------------------------------

(*
 * S6: No premature rotation.
 * Whenever keyId increases, the old key was at least IntervalDays old.
 * Verifies the age guard at KeyRotationReconciler.java:98 is correct.
 *)
NoPrematureRotation ==
    [][keyId' > keyId => time - keyCreatedAt >= IntervalDays]_vars

(*
 * S7: Rotation requires enablement.
 * keyId never changes when rotation is disabled. Verifies the
 * enabled check at KeyRotationReconciler.java:72.
 *)
NoRotationWhenDisabled ==
    [][keyId' > keyId => rotationEnabled]_vars

\* ---------------------------------------------------------------------------
\* Liveness properties (temporal, checked with PROPERTY under Fairness)
\* ---------------------------------------------------------------------------

(*
 * L1: previousKeyId label is eventually consumed.
 * Once KRR sets the label, PPR eventually publishes to registry and clears it.
 * Requires: WF on PPR_Publish, PPR_Clear, PPR_Trigger.
 *)
LabelEventuallyConsumed ==
    [](previousKeyId /= NULL => <>(previousKeyId = NULL))

(*
 * L2: Registry eventually reflects the current Secret key.
 * After rotation, PPR eventually publishes the new key as active.
 * Requires: WF on PPR_Publish, PPR_Trigger.
 *)
RegistryEventuallyConverges ==
    []<>(\E e \in registryEntries : e.keyId = keyId /\ e.status = "active")

(*
 * L3: If rotation is enabled and the key is old enough, it eventually rotates.
 * The timer-driven reconcile loop ensures progress. Rotation can be avoided
 * only if the user disables it (ToggleRotation).
 *
 * Uses leads-to (~>): once the antecedent holds, the consequent must
 * eventually hold. The disjunction with ~rotationEnabled accounts for
 * the user disabling rotation between the check and the action.
 *)
EventualRotation ==
    \A v \in 1..(MaxKeyVersion - 1) :
        (keyId = v /\ rotationEnabled /\ secretReady /\ time - keyCreatedAt >= IntervalDays)
            ~> (keyId > v \/ ~rotationEnabled)

\* ---------------------------------------------------------------------------
\* Fairness
\* ---------------------------------------------------------------------------

\* Fairness models scheduling guarantees:
\*   - WF(Tick): time advances (days pass) until MaxTime
\*   - WF(CreateSecret): Secret is eventually created
\*   - WF(KRR_Start, KRR_Reconcile): timer-driven reconcile completes
\*   - WF(PPR_Publish, PPR_Clear, PPR_Trigger): PPR reconcile completes
\*   - NO fairness on ToggleRotation: user action, may never happen
Fairness ==
    /\ WF_vars(Tick)
    /\ WF_vars(CreateSecret)
    /\ WF_vars(KRR_Start)
    /\ WF_vars(KRR_Reconcile)
    /\ WF_vars(PPR_Publish)
    /\ WF_vars(PPR_Clear)
    /\ WF_vars(PPR_Trigger)

\* ---------------------------------------------------------------------------
\* Specification
\* ---------------------------------------------------------------------------

Spec == Init /\ [][Next]_vars /\ Fairness

\* ---------------------------------------------------------------------------
\* Model checking configuration
\* ---------------------------------------------------------------------------
(*
 * Suggested TLC configuration for initial exploration:
 *
 *   CONSTANTS
 *     IntervalDays = 2
 *     MaxTime      = 7
 *     MaxKeyVersion = 4
 *
 *   INVARIANT Safety
 *   PROPERTY NoPrematureRotation
 *   PROPERTY NoRotationWhenDisabled
 *   PROPERTY LabelEventuallyConsumed
 *   PROPERTY RegistryEventuallyConverges
 *   PROPERTY EventualRotation
 *
 * This explores keys aging 0→2→4→6 days, with rotations at v1→v2→v3→v4.
 * Enough time for 3 full rotation cycles and double-rotation scenarios.
 *
 * To verify the double-rotation fix is necessary, remove the "stale active"
 * step (afterMarkStale) from PPR_Publish and check OneActiveEntry — TLC
 * should find a counterexample where two keys are simultaneously active.
 *
 * For larger exploration:
 *
 *   CONSTANTS
 *     IntervalDays = 3
 *     MaxTime      = 10
 *     MaxKeyVersion = 4
 *)

=============================================================================
