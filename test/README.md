# Knative Test Deployment with CloudEvent Signing

Cross-namespace signing test using [ContainerSources](https://knative.dev/docs/eventing/custom-event-source/containersource/)
as producers (bu-alice, bu-eve) and [event_display](https://knative.dev/docs/eventing/sources/ping-source/#verify-the-pingsource)
as consumer (bu-bob, bu-eve). Each producer emits a dedicated event type via `K_CE_OVERRIDES`.

## Prerequisites

- Kubernetes cluster with Knative Eventing installed
- CloudEvent Signing operator installed (see [onboarding guide](../../docs/onboarding.md))

## Architecture

Three namespaces form a cross-namespace event mesh:

- **bu-alice** — producer only (signs events, publishes every minute)
- **bu-eve** — producer and consumer (signs own events, verifies alice's events)
- **bu-bob** — consumer only (verifies events from both alice and eve)

```
bu-alice (producer)              bu-eve (producer + consumer)         bu-bob (consumer)
─────────────────────           ─────────────────────────────        ──────────────────────
ContainerSource (every 60s)     ContainerSource (every 60s)          event-display
type: ...alice.produced         type: ...eve.produced
  │                               │                                    ▲
  ▼                               ▼                                    │
signing-seq → ce-signer         signing-seq → ce-signer             verify-seq → ce-verifier
  │                               │                                    ▲
  ▼                               ▼                                    │
alice-broker                    eve-broker                           bob-broker
  │                               │  ▲                                 ▲
  │  forward-to-eve               │  │ verify-seq → ce-verifier        │
  ├──────────────────────────────►│  │    │                            │
  │                               │  │    ▼                            │
  │                               │  │  event-display                  │
  │                               │                                    │
  │  forward-to-bob               │  forward-to-bob                    │
  ├───────────────────────────────┼───────────────────────────────────►│
  │                               ├───────────────────────────────────►│
```

**Trust model:**
- bu-eve trusts: bu-alice
- bu-bob trusts: bu-alice, bu-eve

## Deploy

```bash
kubectl apply -k test/manifests/
```

## Verify

```bash
# Check policies
kubectl get cespp -n bu-alice
kubectl get cespp -n bu-eve
kubectl get cescp -n bu-bob
kubectl get cescp -n bu-eve

# Check all Knative resources
kubectl get broker,trigger,sequence,containersource -n bu-alice
kubectl get broker,trigger,sequence,containersource -n bu-eve
kubectl get broker,trigger,sequence -n bu-bob

# Check pods
kubectl get pods -n bu-alice
kubectl get pods -n bu-eve
kubectl get pods -n bu-bob

# Check public key registry
kubectl get cepkr ce-signing-registry -o yaml
```

## Test

Events are produced automatically every minute by the ContainerSources. To verify:

1. Check that the event producers are running:

   ```bash
   kubectl get containersource -n bu-alice
   kubectl get containersource -n bu-eve
   ```

2. View received events in Bob's event-display (receives from both alice and eve):

   ```bash
   kubectl logs -n bu-bob -l app=event-display -f
   ```

3. View received events in Eve's event-display (receives from alice):

   ```bash
   kubectl logs -n bu-eve -l app=event-display -f
   ```

   Signed and verified events should appear in the logs.

## Teardown

Delete all namespaces (removes all resources):

```bash
kubectl delete namespace bu-alice bu-bob bu-eve
```

Or selectively by label:

```bash
kubectl delete all,broker,sequence,trigger,containersource,cespp,cescp \
  -l app.kubernetes.io/part-of=ce-signing-test --all-namespaces
```

## Notes

- `rejectUnsigned: true` on Bob's and Eve's side — unsigned events are rejected with HTTP 403.
- Key rotation is disabled for test simplicity.
- Each [ContainerSource](https://knative.dev/docs/eventing/custom-event-source/containersource/)
  emits a dedicated event type (`dev.ce-signing.alice.produced`, `dev.ce-signing.eve.produced`)
  — broker triggers and consumer policy filters match on these.
- All resources are labeled `app.kubernetes.io/part-of: ce-signing-test` for easy cleanup.
