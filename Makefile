# Local dev flow: build → kind cluster w/ Knative → deploy → test scenario
#
# Usage:
#   make dev            # full flow from scratch (single-cluster)
#   make rebuild        # inner loop: rebuild images + upgrade helm + restart pods
#   make federation     # layer federation on top of existing dev env
#   make dev-federation # full flow + federation from scratch
#   make clean          # tear down all clusters + registry

REGISTRY     ?= localhost:5001
GROUP        ?= public
TAG          ?= dev
CLUSTER      ?= knative
KIND_IMAGE   ?= kindest/node:v1.33.7
HELM_RELEASE ?= ce-signing
NAMESPACE    ?= ce-signing-system

PROXY_IMG    := $(REGISTRY)/$(GROUP)/ce-signing-proxy:$(TAG)
OPERATOR_IMG := $(REGISTRY)/$(GROUP)/ce-signing-operator:$(TAG)
CHART_PATH   := ce-signing-operator/charts/ce-signing-operator

REMOTE_CLUSTER ?= remote

PROM_OP_VERSION ?= v0.82.2

.PHONY: setup dev registry build cluster connect prometheus-crds install upgrade apply rebuild logs clean status \
	dev-federation federation cluster-remote connect-remote install-remote-crds apply-remote-registry \
	federation-secret upgrade-federation apply-federation status-federation clean-federation

# ── Setup ─────────────────────────────────────────────────────────────────────

setup:
	git config core.hooksPath .githooks
	@echo "Git hooks configured (path: .githooks)"

# ── Full flow ────────────────────────────────────────────────────────────────

dev: cluster registry build connect prometheus-crds install apply
	@echo ""
	@echo "✓ Local dev environment ready"
	@echo "  Operator:  kubectl logs -n $(NAMESPACE) -l app.kubernetes.io/name=ce-signing-operator -f"
	@echo "  Bob:       kubectl logs -n bu-bob -l app=event-display -f"
	@echo "  Eve:       kubectl logs -n bu-eve -l app=event-display -f"
	@echo ""

# ── Individual targets ───────────────────────────────────────────────────────

registry:
	@docker run -d --restart=always -p 5001:5000 --name kind-registry registry:2 2>/dev/null \
		&& echo "Started local registry on :5001" \
		|| echo "Registry already running"

build:
	mvn clean verify -DskipTests \
		-Dquarkus.container-image.build=true \
		-Dquarkus.container-image.push=true \
		-Dquarkus.container-image.registry=$(REGISTRY) \
		-Dquarkus.container-image.group=$(GROUP) \
		-Dquarkus.container-image.insecure=true \
		-Dquarkus.container-image.tag=$(TAG)

cluster:
	kn quickstart kind --install-eventing -k $(KIND_IMAGE)

connect:
	@docker run -d --restart=always -p 5001:5000 --name kind-registry registry:2 2>/dev/null || true
	@docker network connect kind kind-registry 2>/dev/null || true
	@REG_IP=$$(docker inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' kind-registry) && \
	for node in $$(kind get nodes --name $(CLUSTER)); do \
		docker exec "$$node" bash -c \
			"grep -v kind-registry /etc/hosts > /tmp/hosts.new; \
			 echo '$$REG_IP kind-registry' >> /tmp/hosts.new; \
			 cp /tmp/hosts.new /etc/hosts; \
			 mkdir -p /etc/containerd/certs.d/localhost:5001 && \
			 printf '[host.\"http://kind-registry:5000\"]\n  capabilities = [\"pull\", \"resolve\"]\n' \
			 > /etc/containerd/certs.d/localhost:5001/hosts.toml" ; \
	done && \
	echo "Registry connected to kind network (IP: $$REG_IP)"

# Install the ServiceMonitor CRD so the operator's Fabric8 informer can watch
# ServiceMonitor resources and the Helm chart's servicemonitor.yaml is accepted.
prometheus-crds:
	kubectl apply --server-side -f https://raw.githubusercontent.com/prometheus-operator/prometheus-operator/$(PROM_OP_VERSION)/example/prometheus-operator-crd/monitoring.coreos.com_servicemonitors.yaml

bootstrap-registry:
	@kubectl apply -f - <<< '{"apiVersion":"ce-signing.platform.io/v1alpha1","kind":"PublicKeyRegistry","metadata":{"name":"ce-signing-registry"},"spec":{"entries":[]}}' 2>/dev/null || true

install: bootstrap-registry
	helm install $(HELM_RELEASE) $(CHART_PATH) \
		-f test/values-local.yaml \
		-n $(NAMESPACE) --create-namespace \
		--wait --timeout 2m

upgrade: bootstrap-registry
	helm upgrade $(HELM_RELEASE) $(CHART_PATH) \
		-f test/values-local.yaml \
		-n $(NAMESPACE) \
		--wait --timeout 2m

apply:
	kubectl apply -k test/overlays/local

# ── Inner dev loop ───────────────────────────────────────────────────────────

rebuild: build upgrade
	kubectl rollout restart deploy -n bu-alice 2>/dev/null || true
	kubectl rollout restart deploy -n bu-bob 2>/dev/null || true
	kubectl rollout restart deploy -n bu-eve 2>/dev/null || true
	@echo "Rebuild complete — pods restarting"

# ── Observability ────────────────────────────────────────────────────────────

logs:
	@echo "=== Operator ===" && \
	kubectl logs -n $(NAMESPACE) -l app.kubernetes.io/name=ce-signing-operator --tail=30 2>/dev/null || echo "(not running yet)" && \
	echo "" && \
	echo "=== Event Display (bob) ===" && \
	kubectl logs -n bu-bob -l app=event-display --tail=20 2>/dev/null || echo "(not running yet)" && \
	echo "" && \
	echo "=== Event Display (eve) ===" && \
	kubectl logs -n bu-eve -l app=event-display --tail=20 2>/dev/null || echo "(not running yet)"

status:
	@echo "=== Nodes ===" && kubectl get nodes && echo ""
	@echo "=== Operator ===" && kubectl get pods -n $(NAMESPACE) && echo ""
	@echo "=== Alice ===" && kubectl get pods -n bu-alice 2>/dev/null && echo ""
	@echo "=== Bob ===" && kubectl get pods -n bu-bob 2>/dev/null && echo ""
	@echo "=== Eve ===" && kubectl get pods -n bu-eve 2>/dev/null && echo ""
	@echo "=== PublicKeyRegistry ===" && kubectl get publickeyregistry ce-signing-registry -o yaml 2>/dev/null || echo "(not created yet)"

# ── Federation ────────────────────────────────────────────────────────────────

dev-federation: dev federation

federation: cluster-remote connect-remote install-remote-crds apply-remote-registry federation-secret upgrade-federation apply-federation
	@echo ""
	@echo "✓ Federation environment ready"
	@echo "  Federation pod: kubectl logs -n $(NAMESPACE) -l app.kubernetes.io/component=federation -f"
	@echo "  Config status:  kubectl get clusterfederationconfig ce-signing-federation -o yaml"
	@echo "  Synced keys:    kubectl get federatedkeyregistry remote-keys -o yaml"
	@echo ""

cluster-remote:
	kind create cluster --name $(REMOTE_CLUSTER) --image $(KIND_IMAGE)

connect-remote:
	@docker run -d --restart=always -p 5001:5000 --name kind-registry registry:2 2>/dev/null || true
	@docker network connect kind kind-registry 2>/dev/null || true
	@REG_IP=$$(docker inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' kind-registry) && \
	for node in $$(kind get nodes --name $(REMOTE_CLUSTER)); do \
		docker exec "$$node" bash -c \
			"grep -v kind-registry /etc/hosts > /tmp/hosts.new; \
			 echo '$$REG_IP kind-registry' >> /tmp/hosts.new; \
			 cp /tmp/hosts.new /etc/hosts; \
			 mkdir -p /etc/containerd/certs.d/localhost:5001 && \
			 printf '[host.\"http://kind-registry:5000\"]\n  capabilities = [\"pull\", \"resolve\"]\n' \
			 > /etc/containerd/certs.d/localhost:5001/hosts.toml" ; \
	done && \
	echo "Registry connected to remote cluster (IP: $$REG_IP)"

install-remote-crds:
	kubectl apply --context kind-$(REMOTE_CLUSTER) \
		-f ce-signing-operator/src/main/helm/crds/publickeyregistry.yaml

apply-remote-registry:
	kubectl apply --context kind-$(REMOTE_CLUSTER) \
		-f test/manifests/federation/remote-registry.yaml

federation-secret:
	@KUBECONFIG_RAW=$$(kind get kubeconfig --name $(REMOTE_CLUSTER)) && \
	REMOTE_IP=$$(docker inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' $(REMOTE_CLUSTER)-control-plane) && \
	KUBECONFIG_PATCHED=$$(echo "$$KUBECONFIG_RAW" | \
		sed "s|https://127.0.0.1:[0-9]*|https://$$REMOTE_IP:6443|g" | \
		sed '/certificate-authority-data:/d' | \
		awk '/server:/{print; print "    insecure-skip-tls-verify: true"; next}1') && \
	kubectl --context kind-$(CLUSTER) -n $(NAMESPACE) create secret generic remote-cluster-kubeconfig \
		--from-literal=kubeconfig="$$KUBECONFIG_PATCHED" \
		--dry-run=client -o yaml | kubectl --context kind-$(CLUSTER) apply -f -

upgrade-federation: bootstrap-registry
	kubectl apply --server-side -f https://raw.githubusercontent.com/prometheus-operator/prometheus-operator/$(PROM_OP_VERSION)/example/prometheus-operator-crd/monitoring.coreos.com_servicemonitors.yaml
	helm upgrade --install $(HELM_RELEASE) $(CHART_PATH) \
		-f test/values-local.yaml \
		-f test/values-federation.yaml \
		-n $(NAMESPACE) --create-namespace \
		--wait --timeout 2m

apply-federation:
	kubectl --context kind-$(CLUSTER) apply -k test/overlays/federation
	kubectl --context kind-$(CLUSTER) apply -f test/manifests/federation/cluster-federation-config.yaml

status-federation:
	@echo "=== Federation Pod ===" && kubectl get pods -n $(NAMESPACE) -l app.kubernetes.io/component=federation 2>/dev/null && echo ""
	@echo "=== ClusterFederationConfig ===" && kubectl get clusterfederationconfig ce-signing-federation -o yaml 2>/dev/null || echo "(not created yet)" && echo ""
	@echo "=== FederatedKeyRegistry ===" && kubectl get federatedkeyregistry 2>/dev/null || echo "(none yet)"

clean-federation:
	kind delete cluster --name $(REMOTE_CLUSTER) 2>/dev/null || true
	@echo "Remote cluster removed"

# ── Cleanup ──────────────────────────────────────────────────────────────────

clean:
	kind delete cluster --name $(CLUSTER) 2>/dev/null || true
	kind delete cluster --name $(REMOTE_CLUSTER) 2>/dev/null || true
	docker rm -f kind-registry 2>/dev/null || true
	@echo "Clusters and registry removed"
