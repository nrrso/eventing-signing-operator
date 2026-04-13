// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("ce-signing.platform.io")
@Version("v1alpha1")
@Kind("PublicKeyRegistry")
@Plural("publickeyregistries")
@Singular("publickeyregistry")
@ShortNames("cepkr")
public class PublicKeyRegistry extends CustomResource<PublicKeyRegistrySpec, Void> {

    public static final String SINGLETON_NAME = "ce-signing-registry";
}
