// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class OperatorApp {
    public static void main(String... args) {
        Quarkus.run(args);
    }
}
