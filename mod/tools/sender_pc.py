"""Тест-отправитель: веб-камера ПК (или любого устройства) -> UDP на телефон/ПК с Minecraft.
pip install mediapipe opencv-python
python sender_pc.py 192.168.1.50      # IP телефона (или 127.0.0.1 если Minecraft на этом же ПК)
"""
import socket, sys, cv2, mediapipe as mp

host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
hands = mp.solutions.hands.Hands(max_num_hands=2, model_complexity=0,
                                 min_detection_confidence=0.6, min_tracking_confidence=0.6)
cap = cv2.VideoCapture(0)
while cap.isOpened():
    ok, frame = cap.read()
    if not ok: break
    frame = cv2.flip(frame, 1)                       # зеркало: x растёт вправо от пользователя, метки рук верные
    res = hands.process(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
    if res.multi_hand_landmarks:
        for lm, hd in zip(res.multi_hand_landmarks, res.multi_handedness):
            idx = 0 if hd.classification[0].label == "Left" else 1
            vals = ",".join(f"{p.x:.4f},{p.y:.4f},{p.z:.4f}" for p in lm.landmark)
            sock.sendto(f"{idx},{vals}".encode(), (host, 25566))
    cv2.imshow("hands (q - exit)", frame)
    if cv2.waitKey(1) & 0xFF == ord('q'): break
