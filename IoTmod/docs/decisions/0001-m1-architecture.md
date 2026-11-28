# M1 architecture decisions

- Use manual dependency injection through `AppContainer`; defer Hilt to the M2 refactor and do not add kapt/ksp in M1.
- Store broker profiles and parse the dynamic MQTT protocol with `org.json`; no kotlinx.serialization plugin or dependency is required.
- Switch the three screens directly instead of adding Navigation Compose.
- Keep the MQTT connection manager and message journal in the application-scoped container so they survive activity recreation and ViewModel replacement.

- M2 implementation: use an application-scoped in-memory device/widget registry populated from real ESP32 MQTT traffic; Room persistence and history/charts are deferred to M4.
