## [1.0.2](https://github.com/nrrso/eventing-signing-operator/compare/v1.0.1...v1.0.2) (2026-05-01)

### Bug Fixes

* **deps:** update quarkus to v3.35.1 ([#35](https://github.com/nrrso/eventing-signing-operator/issues/35)) ([f6dcd76](https://github.com/nrrso/eventing-signing-operator/commit/f6dcd760cae79ccd448bc03c3dd5123d75393604))

## [1.0.1](https://github.com/nrrso/eventing-signing-operator/compare/v1.0.0...v1.0.1) (2026-04-22)

### Bug Fixes

* **deps:** update quarkus to v3.34.6 ([#32](https://github.com/nrrso/eventing-signing-operator/issues/32)) ([bd96522](https://github.com/nrrso/eventing-signing-operator/commit/bd96522f3c96ec40ce8cfb613e88977560fcb5b9))

## 1.0.0 (2026-04-20)

### Features

* add cluster identity and cluster-aware trust model ([a927abf](https://github.com/nrrso/eventing-signing-operator/commit/a927abf4d005273e3de00b13644507e612cabf0b))
* add multi-cluster federation controller ([3dc1c11](https://github.com/nrrso/eventing-signing-operator/commit/3dc1c11253d6436b84ce372c3a743b84f45aabea))
* **ci:** add semantic-release for automated versioning and changelogs ([9ffcd69](https://github.com/nrrso/eventing-signing-operator/commit/9ffcd69f31516f4765f1680bb658de8df5bb0df6))
* **operator:** add operator mode configuration (LOCAL/FEDERATION) ([975c55d](https://github.com/nrrso/eventing-signing-operator/commit/975c55d216c6259522b61b17bdb15120c0d89378))
* **operator:** add PEM validation and status conditions to FederatedKeyRegistry ([f8e9765](https://github.com/nrrso/eventing-signing-operator/commit/f8e9765f6c8e2c1856e38061937c1451d5d158f9))

### Bug Fixes

* **ci:** replace fragile commit-payload path filters with dorny/paths-filter ([07d8145](https://github.com/nrrso/eventing-signing-operator/commit/07d81451bc74c42fbd231acb2ab2ab69f53a11d9))
* **deps:** update bouncycastle.version to v1.84 ([164ed3d](https://github.com/nrrso/eventing-signing-operator/commit/164ed3df10884e60bcce69936bb4b7ea17bc7155))
* **deps:** update cloudevents to v4.0.2 ([#5](https://github.com/nrrso/eventing-signing-operator/issues/5)) ([09d370d](https://github.com/nrrso/eventing-signing-operator/commit/09d370df85553e9ee8e438c670784f7c87e9dd60))
* **deps:** update dependency io.quarkiverse.operatorsdk:quarkus-operator-sdk to v7.7.4 ([#11](https://github.com/nrrso/eventing-signing-operator/issues/11)) ([491f393](https://github.com/nrrso/eventing-signing-operator/commit/491f3938e31f2053ac5a2b0035d1222b55137c1a))
* **deps:** update quarkus to v3.34.5 ([5930b03](https://github.com/nrrso/eventing-signing-operator/commit/5930b03e1571efc8d5420eb19e0445f4a98871d0))
* **operator:** improve reconciler resilience and federation deployment isolation ([8dc5247](https://github.com/nrrso/eventing-signing-operator/commit/8dc5247c1e3fb1e6a7f9725e7000f04f6eaac8fa))
