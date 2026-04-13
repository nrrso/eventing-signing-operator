// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.mode;

import com.platform.cesigning.proxy.config.ProxyConfig;
import io.cloudevents.CloudEvent;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

@Path("/")
public class ProxyRouter {

    @Inject
    ProxyConfig config;

    @Inject
    SigningHandler signingHandler;

    @Inject
    VerifyingHandler verifyingHandler;

    @PostConstruct
    void init() {
        String mode = config.mode();
        if (!"sign".equals(mode) && !"verify".equals(mode)) {
            throw new IllegalStateException(
                    "Invalid CE_SIGNING_MODE: '" + mode + "'. Must be 'sign' or 'verify'.");
        }
    }

    @POST
    @Consumes("*/*")
    @Produces("*/*")
    public Response handle(CloudEvent event) {
        if ("sign".equals(config.mode())) {
            return signingHandler.sign(event);
        } else {
            return verifyingHandler.verify(event);
        }
    }
}
