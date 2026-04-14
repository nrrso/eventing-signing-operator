# Local dev flow: build → kind cluster w/ Knative → deploy → test scenario
#
# Usage:
#   make dev       # full flow from scratch
#   make rebuild   # inner loop: rebuild images + upgrade helm + restart pods
#   make clean     # tear down cluster + registry

REGISTRY     ?= localhost:5001
GROUP        ?= public
TAG          ?= dev
CLUSTER      ?= knative
KIND_IMAGE   ?= kindest/node:v1.33.7
HELM_RELEASE ?= ce-signing
NAMESPACE    ?= ce-signing-system

PROXY_IMG    := $(REGISTRY)/$(GROUP)/ce-signing-proxy:$(TAG)
OPERATOR_IMG := $(REGISTRY)/$(GROUP)/ce-signing-operator:$(TAG)
CHART_PATH   := ce-signing-operator/target/helm/kubernetes/ce-signing-operator

PROM_OP_VERSION ?= v0.82.2

.PHONY: setup dev registry build cluster connect prometheus-crds install upgrade apply rebuild logs clean status

# ── Setup ─────────────────────────────────────────────────────────────────────

setup:
	git config core.hooksPath .githooks
	@echo "Git hooks configured (path: .githooks)"

# ── Full flow ────────────────────────────────────────────────────────────────

dev: registry build cluster connect prometheus-crds install apply
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
	@docker network connect kind kind-registry 2>/dev/null || true
	$(eval REG_IP := $(shell docker inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' kind-registry))
	@for node in $$(kind get nodes --name $(CLUSTER)); do \
		docker exec "$$node" bash -c \
			"grep -q kind-registry /etc/hosts || echo '$(REG_IP) kind-registry' >> /etc/hosts && \
			 mkdir -p /etc/containerd/certs.d/localhost:5001 && \
			 printf '[host.\"http://kind-registry:5000\"]\n  capabilities = [\"pull\", \"resolve\"]\n' \
			 > /etc/containerd/certs.d/localhost:5001/hosts.toml" ; \
	done
	@echo "Registry connected to kind network (IP: $(REG_IP))"

# Install the ServiceMonitor CRD so the operator's Fabric8 informer can watch
# ServiceMonitor resources and the Helm chart's servicemonitor.yaml is accepted.
prometheus-crds:
	kubectl apply --server-side -f https://raw.githubusercontent.com/prometheus-operator/prometheus-operator/$(PROM_OP_VERSION)/example/prometheus-operator-crd/monitoring.coreos.com_servicemonitors.yaml

install:
	helm install $(HELM_RELEASE) $(CHART_PATH) \
		-f test/values-local.yaml \
		-n $(NAMESPACE) --create-namespace \
		--wait --timeout 2m

upgrade:
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

# ── Cleanup ──────────────────────────────────────────────────────────────────

clean:
	kind delete cluster --name $(CLUSTER) 2>/dev/null || true
	docker rm -f kind-registry 2>/dev/null || true
	@echo "Cluster and registry removed"
