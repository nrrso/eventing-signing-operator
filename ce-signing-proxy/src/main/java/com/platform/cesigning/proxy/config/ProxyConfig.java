// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "ce-signing")
public interface ProxyConfig {

    @WithDefault("sign")
    String mode();

    @WithName("private-key-path")
    @WithDefault("/var/run/ce-keys/private.pem")
    String privateKeyPath();

    @WithName("key-id")
    @WithDefault("default")
    String keyId();

    @WithName("canonical-attributes")
    @WithDefault("type,source,subject,datacontenttype")
    List<String> canonicalAttributes();

    VerifyConfig verify();

    interface VerifyConfig {
        @WithName("trusted-namespaces")
        Optional<List<String>> trustedNamespaces();

        @WithName("reject-unsigned")
        @WithDefault("true")
        boolean rejectUnsigned();
    }
}
