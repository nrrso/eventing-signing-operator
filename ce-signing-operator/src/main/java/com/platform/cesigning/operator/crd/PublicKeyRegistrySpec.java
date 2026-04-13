// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

import java.util.ArrayList;
import java.util.List;

public class PublicKeyRegistrySpec {

    private List<PublicKeyEntry> entries = new ArrayList<>();

    public List<PublicKeyEntry> getEntries() { return entries; }
    public void setEntries(List<PublicKeyEntry> entries) { this.entries = entries; }
}
