---------------------------- MODULE ConsumerPolicy ----------------------------
\* TLA+ specification of the ConsumerPolicyReconciler's resource lifecycle,
\* focusing on shared resource management when multiple ConsumerPolicy
\* resources exist in the same namespace.
\*
\* Models the fixed cleanup logic where:
\*   Fix 1 (CRB): cleanup() only deletes the shared ClusterRoleBinding
\*     when no other ConsumerPolicy exists in the namespace.
\*   Fix 2 (Sequences): cleanup() deletes only this policy's Sequences
\*     and Triggers by name, not all by label.
\*
\* Previously, the unfixed cleanup had two bugs (see git history):
\*   Bug 1: Unconditional CRB deletion broke surviving policies.
\*   Bug 2: Label-based cleanup deleted other policies' Sequences.
\*
\* Both CRBIntegrity and NoCollateralDamage now hold for |Policies| >= 2.
\*
\* Corresponds to Java: ConsumerPolicyReconciler.java
\* Related: VerifyingDeploymentDependentResource.java (shared Deployment),
\*          KnativeResourceHelper.java (Sequence/Trigger creation)

EXTENDS FiniteSets

\* ---------------------------------------------------------------------------
\* Constants
\* ---------------------------------------------------------------------------

CONSTANTS
    Policies           \* Set of ConsumerPolicy resource names in the namespace
                       \* e.g., {"A"} for singleton, {"A", "B"} for multi-tenant

\* ---------------------------------------------------------------------------
\* Variables
\* ---------------------------------------------------------------------------

VARIABLES
    policyState,       \* [Policies -> {"exists", "deleting", "deleted"}]
    cprPC,             \* [Policies -> {"idle", "reconciling", "cleaning"}]
    crbExists,         \* BOOLEAN: shared ClusterRoleBinding for this namespace
    deployOwners,      \* SUBSET Policies: policies that own the shared Deployment
                       \* (Kubernetes multi-owner GC: last owner removed = Deployment deleted)
    seqsExist,         \* [Policies -> BOOLEAN]: per-policy Sequences/Triggers exist
    reconciled         \* [Policies -> BOOLEAN]: has this policy reconciled at least once?
                       \* Used to distinguish "not yet created" from "deleted by collateral"

vars == <<policyState, cprPC, crbExists, deployOwners, seqsExist, reconciled>>

\* ---------------------------------------------------------------------------
\* Type invariant
\* ---------------------------------------------------------------------------

TypeOK ==
    /\ \A p \in Policies:
        /\ policyState[p] \in {"exists", "deleting", "deleted"}
        /\ cprPC[p] \in {"idle", "reconciling", "cleaning"}
        /\ seqsExist[p] \in BOOLEAN
        /\ reconciled[p] \in BOOLEAN
    /\ crbExists \in BOOLEAN
    /\ deployOwners \subseteq Policies

\* ---------------------------------------------------------------------------
\* Initial state
\* ---------------------------------------------------------------------------

\* All policies start as existing but not yet reconciled.
\* No resources exist until the first reconcile runs.
Init ==
    /\ policyState = [p \in Policies |-> "exists"]
    /\ cprPC = [p \in Policies |-> "idle"]
    /\ crbExists = FALSE
    /\ deployOwners = {}
    /\ seqsExist = [p \in Policies |-> FALSE]
    /\ reconciled = [p \in Policies |-> FALSE]

\* ---------------------------------------------------------------------------
\* Actions
\* ---------------------------------------------------------------------------

\* JOSDK triggers reconcile (periodic re-queue or informer event).
TriggerReconcile(p) ==
    /\ policyState[p] = "exists"
    /\ cprPC[p] = "idle"
    /\ cprPC' = [cprPC EXCEPT ![p] = "reconciling"]
    /\ UNCHANGED <<policyState, crbExists, deployOwners, seqsExist, reconciled>>

\* Full reconcile: ensure all managed resources exist.
\* Source: reconcile() at ConsumerPolicyReconciler.java lines 52-130
\*
\* Creates/ensures:
\*   - ClusterRoleBinding (line 68, ensureVerifierClusterRoleBinding)
\*   - Deployment ce-verifier (JOSDK dependent, adds ownerReference)
\*   - ServiceAccount ce-signing-verifier (JOSDK dependent)
\*   - Knative Sequences per consumer entry (lines 83-88, serverSideApply)
\*   - Knative Triggers per consumer entry (lines 91-97, serverSideApply)
\*
\* All operations are idempotent (serverSideApply). Multiple policies
\* reconciling concurrently produce the same infrastructure.
Reconcile(p) ==
    /\ cprPC[p] = "reconciling"
    /\ crbExists' = TRUE                              \* ensureVerifierClusterRoleBinding
    /\ deployOwners' = deployOwners \cup {p}           \* JOSDK adds ownerRef to Deployment
    /\ seqsExist' = [seqsExist EXCEPT ![p] = TRUE]    \* serverSideApply Sequences/Triggers
    /\ reconciled' = [reconciled EXCEPT ![p] = TRUE]
    /\ cprPC' = [cprPC EXCEPT ![p] = "idle"]
    /\ UNCHANGED <<policyState>>

\* User deletes the ConsumerPolicy resource.
DeletePolicy(p) ==
    /\ policyState[p] = "exists"
    /\ cprPC[p] = "idle"
    /\ policyState' = [policyState EXCEPT ![p] = "deleting"]
    /\ cprPC' = [cprPC EXCEPT ![p] = "cleaning"]
    /\ UNCHANGED <<crbExists, deployOwners, seqsExist, reconciled>>

\* Cleanup: delete managed resources and remove finalizer.
\* Source: cleanup() at ConsumerPolicyReconciler.java lines 132-143
\*
\* Returns DeleteControl.defaultDelete() — finalizer removed, K8s deletes
\* the ConsumerPolicy resource and GCs owned namespace-scoped resources.
\*
\* FIX 1: Only delete CRB if no other existing policy in the namespace.
\* FIX 2: Only delete this policy's sequences (by name, not all by label).
Cleanup(p) ==
    /\ cprPC[p] = "cleaning"
    /\ policyState[p] = "deleting"
    \* FIX 1: Guard CRB deletion on no other existing policy
    /\ LET othersExist == \E q \in Policies : q /= p /\ policyState[q] = "exists"
       IN crbExists' = IF othersExist THEN crbExists ELSE FALSE
    \* FIX 2: Only delete this policy's sequences, not others'
    /\ seqsExist' = [seqsExist EXCEPT ![p] = FALSE]
    /\ deployOwners' = deployOwners \ {p}              \* K8s GC removes ownerRef
    /\ policyState' = [policyState EXCEPT ![p] = "deleted"]
    /\ cprPC' = [cprPC EXCEPT ![p] = "idle"]
    /\ UNCHANGED <<reconciled>>

\* Terminal state: all policies deleted, system quiesced.
Done ==
    /\ \A p \in Policies: policyState[p] = "deleted" /\ cprPC[p] = "idle"
    /\ UNCHANGED vars

\* ---------------------------------------------------------------------------
\* Next-state relation
\* ---------------------------------------------------------------------------

Next ==
    \/ \E p \in Policies:
        \/ TriggerReconcile(p)
        \/ Reconcile(p)
        \/ DeletePolicy(p)
        \/ Cleanup(p)
    \/ Done

\* ---------------------------------------------------------------------------
\* Safety invariants (hold for any |Policies|)
\* ---------------------------------------------------------------------------

\* S1: Deleted policies do not own the Deployment.
DeploymentOwnersValid ==
    \A p \in deployOwners: policyState[p] /= "deleted"

\* S2: After cleanup, the deleted policy's resources are gone.
CleanupComplete ==
    \A p \in Policies:
        (policyState[p] = "deleted" /\ cprPC[p] = "idle") =>
            /\ ~seqsExist[p]
            /\ p \notin deployOwners

Safety == /\ TypeOK /\ DeploymentOwnersValid /\ CleanupComplete

\* ---------------------------------------------------------------------------
\* Shared-resource integrity (VIOLATED with |Policies| >= 2)
\* ---------------------------------------------------------------------------

\* S3: CRB should exist whenever any reconciled policy is active.
\* VIOLATED: Cleanup of policy A deletes the CRB that policy B needs.
\*
\* Counterexample (TLC trace):
\*   1. A reconciles: CRB created
\*   2. B reconciles: CRB exists (idempotent)
\*   3. A deleted, cleanup runs: CRB deleted
\*   4. B is "exists", reconciled, idle — but CRB is gone
\*
\* Fix: In cleanup(), check if other ConsumerPolicies exist in the
\* namespace before deleting the CRB. Or: move CRB to JOSDK dependent
\* resource with multi-owner semantics.
CRBIntegrity ==
    (\E p \in Policies:
        policyState[p] = "exists" /\ reconciled[p] /\ cprPC[p] = "idle")
    => crbExists

\* S4: A policy's Sequences/Triggers should exist while it's active.
\* VIOLATED: Cleanup of policy A deletes policy B's Sequences by label.
\*
\* Counterexample (TLC trace):
\*   1. A and B reconcile: seqsExist = [A=TRUE, B=TRUE]
\*   2. A deleted, cleanup runs: seqsExist = [A=FALSE, B=FALSE]
\*   3. B is "exists", reconciled, idle — but seqsExist[B] = FALSE
\*
\* Fix: Use ownerReference-based cleanup instead of label-based, or
\* filter by ownerReference during label-based cleanup. K8s GC already
\* handles the deleted policy's owned resources via ownerReference.
NoCollateralDamage ==
    \A p \in Policies:
        (policyState[p] = "exists" /\ reconciled[p] /\ cprPC[p] = "idle")
        => seqsExist[p]

\* ---------------------------------------------------------------------------
\* Liveness properties (hold for any |Policies|)
\* ---------------------------------------------------------------------------

\* L1: CRB is eventually restored after being deleted while a policy exists.
\* The surviving policy's next reconcile re-creates the CRB.
CRBEventuallyRestored ==
    \A p \in Policies:
        (policyState[p] = "exists" /\ ~crbExists)
        ~> (crbExists \/ policyState[p] /= "exists")

\* L2: Sequences are eventually restored after collateral deletion.
\* The surviving policy's next reconcile re-creates its Sequences.
SequencesEventuallyRestored ==
    \A p \in Policies:
        (policyState[p] = "exists" /\ reconciled[p] /\ ~seqsExist[p])
        ~> (seqsExist[p] \/ policyState[p] /= "exists")

\* L3: Cleanup eventually completes.
CleanupEventuallyCompletes ==
    \A p \in Policies:
        [](policyState[p] = "deleting" => <>(policyState[p] = "deleted"))

\* ---------------------------------------------------------------------------
\* Fairness
\* ---------------------------------------------------------------------------

\* WF on reconcile actions: JOSDK re-queues ensure eventual processing.
\* WF on cleanup: JOSDK runs cleanup to completion.
\* NO fairness on DeletePolicy: user action, may never happen.
Fairness ==
    \A p \in Policies:
        /\ WF_vars(TriggerReconcile(p))
        /\ WF_vars(Reconcile(p))
        /\ WF_vars(Cleanup(p))

\* ---------------------------------------------------------------------------
\* Specification
\* ---------------------------------------------------------------------------

Spec == Init /\ [][Next]_vars /\ Fairness

\* ---------------------------------------------------------------------------
\* Model checking configuration
\* ---------------------------------------------------------------------------
\*
\* To find the shared-resource bugs:
\*
\*   CONSTANTS Policies = {"A", "B"}
\*   INVARIANT Safety
\*   INVARIANT CRBIntegrity         \* <-- TLC will find violation
\*   INVARIANT NoCollateralDamage   \* <-- TLC will find violation
\*
\* To verify self-healing (after removing the violated invariants):
\*
\*   CONSTANTS Policies = {"A", "B"}
\*   INVARIANT Safety
\*   PROPERTY CRBEventuallyRestored
\*   PROPERTY SequencesEventuallyRestored
\*   PROPERTY CleanupEventuallyCompletes
\*
\* To confirm singleton correctness (all properties hold):
\*
\*   CONSTANTS Policies = {"A"}
\*   INVARIANT Safety
\*   INVARIANT CRBIntegrity
\*   INVARIANT NoCollateralDamage
\*   PROPERTY CRBEventuallyRestored
\*   PROPERTY SequencesEventuallyRestored
\*   PROPERTY CleanupEventuallyCompletes

=============================================================================
