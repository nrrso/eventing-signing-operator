// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.health;

import com.platform.cesigning.proxy.config.ProxyConfig;
import com.platform.cesigning.proxy.mode.SigningHandler;
import com.platform.cesigning.proxy.registry.RegistryKeyCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class ReadinessCheck implements HealthCheck {

    @Inject ProxyConfig config;

    @Inject SigningHandler signingHandler;

    @Inject RegistryKeyCache keyCache;

    @Override
    public HealthCheckResponse call() {
        if ("sign".equals(config.mode())) {
            return HealthCheckResponse.named("signing-key")
                    .status(signingHandler.isKeyLoaded())
                    .withData("mode", "sign")
                    .build();
        } else {
            boolean synced = keyCache.isSynced();
            return HealthCheckResponse.named("registry-sync")
                    .status(synced)
                    .withData("mode", "verify")
                    .withData("cacheSize", keyCache.size())
                    .build();
        }
    }
}
