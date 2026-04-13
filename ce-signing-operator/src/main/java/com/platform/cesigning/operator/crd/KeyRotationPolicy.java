// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

public class KeyRotationPolicy {

    private boolean enabled = true;
    private int intervalDays = 90;
    private int gracePeriodDays = 7;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getIntervalDays() { return intervalDays; }
    public void setIntervalDays(int intervalDays) { this.intervalDays = intervalDays; }

    public int getGracePeriodDays() { return gracePeriodDays; }
    public void setGracePeriodDays(int gracePeriodDays) { this.gracePeriodDays = gracePeriodDays; }
}
