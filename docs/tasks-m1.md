# M1 — завершено

- MQTT-подключение через Paho и управление reconnect находятся в application-scoped `AppContainer`.
- Профили брокеров и credentials хранятся через существующий `ProfileRepository` и `SecretBox`.
- Журнал входящих MQTT-сообщений работает в application-scoped контейнере.
- UI использует прямое переключение экранов без Navigation Compose.
