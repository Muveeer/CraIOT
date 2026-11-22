# M1.0 spike report — CLOSED (GO)

Дата: 22.11.2026 · `tools/spike/m1_spike.py` v2/v3

```text
TLS:        ✅ TLSv1.3, Let's Encrypt YR2, до 14.12.2026 (автопродление)
WSS:        ✅ /ws на 18883, CONNACK=0 за 0.26 с, SUBACK QoS 1
Auth:       ✅ username "95:5lZDsy" целиком + реальный пароль (вводит пользователь);
            username-only → rc=5 Not authorized (пароль обязателен)
Трафик:     ✅ 11 сообщений / 11 топиков мгновенно; 10 устройств обнаружено
Открытие:   /ver407/<dev>/state = {"status":"offline"} у всех — гипотеза retained+LWT,
            подтверждение retain-флагом в v3; виджет v93_5054f (ver4 virtual)
Библиотека: GO — Paho mqttv3 1.2.5 (Android) / paho-mqtt 2.x (PC)
Осталось:   soak 15 мин в приложении; retain-флаги v3
```
