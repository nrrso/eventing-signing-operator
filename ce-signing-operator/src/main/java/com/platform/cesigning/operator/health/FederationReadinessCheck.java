// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.health;

import com.platform.cesigning.operator.reconciler.FederationReconciler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;

@ApplicationScoped
public class FederationReadinessCheck implements HealthCheck {

    @Inject FederationReconciler federationReconciler;

    @Override
    public HealthCheckResponse call() {
        boolean synced = federationReconciler.isAllSynced();
        return HealthCheckResponse.named("federation-sync").status(synced).build();
    }
}
