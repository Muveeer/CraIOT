# M1.0: проверка облака. Запуск: pip install paho-mqtt && python m1_spike.py
# Данные авторизации задаются через IOTMOD_USERNAME и IOTMOD_PASS.
# HELLO по умолчанию выключен; для включения: IOTMOD_HELLO=1
import os, socket, ssl, time
import paho.mqtt.client as mqtt

HOST, PORT, PATH, PREFIX = "live-control.com", 18883, "/ws", "/ver407"
USERNAME = os.environ.get("IOTMOD_USERNAME", "")
PASSWORD = os.environ.get("IOTMOD_PASS", "")

print("=== IoTmod M1.0 spike ===")
try:  # 1) TLS: публичный ли сертификат
    ctx = ssl.create_default_context()
    with socket.create_connection((HOST, PORT), timeout=10) as sock:
        with ctx.wrap_socket(sock, server_hostname=HOST) as s:
            cert = s.getpeercert()
            issuer = {k: v for rdn in cert.get("issuer", []) for k, v in rdn}
            print("TLS OK:", s.version(), "| issuer:", issuer, "| expires:", cert.get("notAfter"))
except Exception as e:
    print("TLS FAIL:", type(e).__name__, str(e)[:200])

received, t0 = [], time.time()
def on_connect(c, u, flags, rc, properties=None):
    print(f"CONNACK={rc} за {time.time()-t0:.2f}s")
    c.subscribe(f"{PREFIX}/#", qos=1)
    print("SUBSCRIBED", PREFIX + "/#")
    if os.environ.get("IOTMOD_HELLO") == "1":
        c.publish(PREFIX, "HELLO", qos=0)
        print("HELLO отправлен — захват конфигов включён")

def on_message(c, userdata, msg):
    p = msg.payload.decode("utf-8", "replace")
    received.append((msg.topic, p))
    if len(received) <= 30:
        flag = "RETAINED" if msg.retain else "live"
        print(f"MSG[{len(received)}] ({flag}, qos{msg.qos}) {msg.topic} -> {p[:150]}")

try:  # 2) MQTT over WSS, только подписка; HELLO выключен по умолчанию
    try:
        c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="iotmod-spike-01",
                        transport="websockets", protocol=mqtt.MQTTv311)
    except (AttributeError, TypeError):
        c = mqtt.Client(client_id="iotmod-spike-01", transport="websockets", protocol=mqtt.MQTTv311)
    c.ws_set_options(path=PATH)
    c.tls_set(cert_reqs=ssl.CERT_REQUIRED)
    c.username_pw_set(USERNAME or None, PASSWORD or None)
    c.on_connect, c.on_message = on_connect, on_message
    c.connect(HOST, PORT, keepalive=45)
    c.loop_start(); time.sleep(25); c.loop_stop(); c.disconnect()
    topics = sorted({t for t, _ in received})
    print(f"ИТОГО: {len(received)} сообщений, {len(topics)} топиков")
    for t in topics[:15]:
        print("  ", t)
except Exception as e:
    print("MQTT FAIL:", type(e).__name__, str(e)[:300])
