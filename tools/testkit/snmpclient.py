"""SNMP v1 GET 을 직접 만들어 보내는 최소 클라이언트 (순수 stdlib).

라이브러리를 설치하지 않고도 '표준 매니저가 받는 것' 을 그대로 볼 수 있어야 한다.
응답을 파싱하지 않고 BER 태그를 그대로 돌려주므로, 값뿐 아니라 타입을 확인할 수 있다.
"""
import socket

#: BER application tag → 사람이 읽는 이름
TAGS = {0x02: 'Integer32', 0x04: 'OctetString', 0x41: 'Counter32',
        0x42: 'Gauge32', 0x43: 'TimeTicks', 0x44: 'Opaque'}


def _len(n):
    return bytes([n]) if n < 0x80 else bytes([0x81, n])


def _tlv(tag, body):
    return bytes([tag]) + _len(len(body)) + body


def encode_oid(oid):
    parts = [int(x) for x in oid.strip('.').split('.')]
    body = bytes([parts[0] * 40 + parts[1]])
    for p in parts[2:]:
        if p < 128:
            body += bytes([p])
        else:
            chunks = []
            while p:
                chunks.insert(0, p & 0x7F)
                p >>= 7
            body += bytes([c | 0x80 for c in chunks[:-1]] + [chunks[-1]])
    return _tlv(0x06, body)


def get(host, port, community, oid, timeout=5):
    """GET 요청을 보내고 원본 응답 바이트를 돌려준다."""
    varbind = _tlv(0x30, encode_oid(oid) + _tlv(0x05, b''))
    pdu = _tlv(0xA0, _tlv(0x02, b'\x00\x00\x00\x01')
               + _tlv(0x02, b'\x00') + _tlv(0x02, b'\x00') + _tlv(0x30, varbind))
    msg = _tlv(0x30, _tlv(0x02, b'\x00') + _tlv(0x04, community.encode()) + pdu)
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(timeout)
    try:
        s.sendto(msg, (host, port))
        data, _ = s.recvfrom(4096)
        return data
    finally:
        s.close()


def value_of(resp, oid):
    """응답에서 그 OID 의 (타입 이름, 값) 을 꺼낸다. 못 찾으면 (None, None)."""
    enc = encode_oid(oid)
    i = resp.find(enc)
    if i < 0:
        return None, None
    j = i + len(enc)
    tag, length = resp[j], resp[j + 1]
    raw = resp[j + 2:j + 2 + length]
    if tag == 0x04:
        return TAGS.get(tag, hex(tag)), raw.decode(errors='replace')
    return TAGS.get(tag, hex(tag)), int.from_bytes(raw, 'big', signed=(tag == 0x02))
