# M1.7: смоук стенда tcp/ws/wss. pip install paho-mqtt; стенд поднят в broker/
import ssl, sys, time
import paho.mqtt.client as mqtt
HOST, USER, PASS, PREFIX = "127.0.0.1", "craiot", "test123", "/ver407"
ok = True
for name, port, transport, tls in [("tcp", 1883, "tcp", False), ("ws", 9001, "websockets", False), ("wss", 8083, "websockets", True)]:
    got, done = [], {"rc": None}
    def on_connect(c, u, f, rc, props=None):
        done["rc"] = getattr(rc, "value", rc)
        if done["rc"] == 0:
            c.subscribe(PREFIX + "/#", qos=1)
            c.publish(PREFIX + "/smoke", "ping-" + name, qos=1)
    def on_message(c, u, m): got.append(m.topic)
    try:
        c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="smoke-" + name, transport=transport, protocol=mqtt.MQTTv311)
    except (AttributeError, TypeError):
        c = mqtt.Client(client_id="smoke-" + name, transport=transport, protocol=mqtt.MQTTv311)
    if tls: c.tls_set(cert_reqs=ssl.CERT_NONE)  # self-signed стенд, только смоук
    c.username_pw_set(USER, PASS)
    c.on_connect, c.on_message = on_connect, on_message
    try:
        c.connect(HOST, port, keepalive=30); c.loop_start()
        t0 = time.time()
        while time.time() - t0 < 5 and not got: time.sleep(0.1)
        c.loop_stop(); c.disconnect()
        good = done["rc"] == 0 and bool(got)
        ok &= good
        print(f"{name:4}: {'OK' if good else 'FAIL'} rc={done['rc']} msgs={len(got)}")
    except Exception as e:
        ok = False
        print(f"{name:4}: FAIL {type(e).__name__}: {e}")
sys.exit(0 if ok else 1)
