// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class OperatorMode {

    public static final String LOCAL = "LOCAL";
    public static final String FEDERATION = "FEDERATION";

    @Inject
    @ConfigProperty(name = "cesigning.operator.mode", defaultValue = LOCAL)
    String mode;

    public boolean isLocal() {
        return LOCAL.equalsIgnoreCase(mode);
    }

    public boolean isFederation() {
        return FEDERATION.equalsIgnoreCase(mode);
    }

    public String getMode() {
        return mode;
    }
}
