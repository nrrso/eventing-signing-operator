// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class ProxyApp {
    public static void main(String... args) {
        Quarkus.run(args);
    }
}
