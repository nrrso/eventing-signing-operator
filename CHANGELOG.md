## [1.0.11](https://github.com/nrrso/eventing-signing-operator/compare/v1.0.10...v1.0.11) (2026-06-12)

### Bug Fixes

* **deps:** update quarkus to v3.36.2 ([#58](https://github.com/nrrso/eventing-signing-operator/issues/58)) ([efc12de](https://github.com/nrrso/eventing-signing-operator/commit/efc12de5937463ddaa10bd8b325a10d9d0101ca7))

## [1.0.10](https://github.com/nrrso/eventing-signing-operator/compare/v1.0.9...v1.0.10) (2026-06-09)

### Bug Fixes

* **deps:** update cloudevents to v4.1.1 ([#56](https://github.com/nrrso/eventing-signing-operator/issues/56)) ([03592f3](https://github.com/nrrso/eventing-signing-operator/commit/03592f32c914025ada3b721f4298cb99deb21706))

## [1.0.9](https://github.com/nrrso/eventing-signing-operator/compare/v1.0.8...v1.0.9) (2026-06-03)

### Bug Fixes

* **deps:** update quarkus to v3.36.1 ([#55](https://github.com/nrrso/eventing-signing-operator/issues/55)) ([c727725](https://github.com/nrrso/eventing-signing-operator/commit/c72772546ba3f82177d73d77cd47467ec60fdf6e))

## [1.0.8](https://github.com/nrrso/eventing-signing-operator/compare/v1.0.7...v1.0.8) (2026-05-28)

### Bug Fixes

* **deps:** update cloudevents to v4.1.0 ([#51](https://github.com/nrrso/eventing-signing-operator/issues/51)) ([3b84fa1](https://github.com/nrrso/eventing-signing-operator/commit/3b84fa11fe284f0949dd063d951ae44cca1be3cc))

## [1.0.7](https://github.com/nrrso/eventing-signing-operator/compare/v1.0.6...v1.0.7) (2026-05-27)

### Bug Fixes

* **deps:** update quarkus to v3.36.0 ([#48](https://github.com/nrrso/eventing-signing-operator/issues/48)) ([73ec30a](https://github.com/nrrso/eventing-signing-operator/commit/73ec30adfd9f2023999f8b97e6c8d2a1edb50e21))

## [1.0.6](https://github.com/nrrso/eventing-signing-operator/compare/v1.0.5...v1.0.6) (2026-05-20)

### Bug Fixes

* **deps:** update quarkus to v3.35.4 ([#46](https://github.com/nrrso/eventing-signing-operator/issues/46)) ([eff6e07](https://github.com/nrrso/eventing-signing-operator/commit/eff6e079fcf2bc5f9d55bc5fe54060d07275aec4))

## [1.0.5](https://github.com/nrrso/eventing-signing-operator/compare/v1.0.4...v1.0.5) (2026-05-20)

### Bug Fixes

* **deps:** update dependency io.quarkiverse.operatorsdk:quarkus-operator-sdk to v7.7.5 ([#45](https://github.com/nrrso/eventing-signing-operator/issues/45)) ([2960c6c](https://github.com/nrrso/eventing-signing-operator/commit/2960c6c452258292ef41ce5a43411d8d5c437fe9))

## [1.0.4](https://github.com/nrrso/eventing-signing-operator/compare/v1.0.3...v1.0.4) (2026-05-13)

### Bug Fixes

* **deps:** update quarkus to v3.35.3 ([#39](https://github.com/nrrso/eventing-signing-operator/issues/39)) ([1367a5b](https://github.com/nrrso/eventing-signing-operator/commit/1367a5b69839471f991ccc55642e520082252934))

## [1.0.3](https://github.com/nrrso/eventing-signing-operator/compare/v1.0.2...v1.0.3) (2026-05-04)

### Bug Fixes

* **deps:** update quarkus to v3.35.2 ([#36](https://github.com/nrrso/eventing-signing-operator/issues/36)) ([d79f058](https://github.com/nrrso/eventing-signing-operator/commit/d79f0589667a9929288e2d2b8f731555f858a62e))

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
