"""EtherNet/IP(CIP) 최소 클라이언트 (순수 stdlib).

Logix 의 Read Tag(0x4C) / Write Tag(0x4D) 를 Unconnected Send 로 감싸 SendRRData 로 보낸다.
CTS 의 EtherNet/IP 장비는 포트를 장비마다 달리 쓰므로, 포트를 44818 로 고정하는 클라이언트 대신 이걸 쓴다.

    with EipClient('127.0.0.1', 15975) as c:
        st, ext, ctype, value = c.read_tag('TAG1')
        st, ext = c.write_tag('TAG1', CIP_REAL, 2.5)
"""
import socket
import struct

CIP_BOOL, CIP_SINT, CIP_INT, CIP_DINT, CIP_REAL, CIP_BITS = 0xC1, 0xC2, 0xC3, 0xC4, 0xCA, 0xD3

CIP_NAMES = {CIP_BOOL: 'BOOL', CIP_SINT: 'SINT', CIP_INT: 'INT', CIP_DINT: 'DINT',
             CIP_REAL: 'REAL', CIP_BITS: 'BITS'}

_PACK = {CIP_BOOL: '<B', CIP_SINT: '<b', CIP_INT: '<h', CIP_DINT: '<i', CIP_REAL: '<f', CIP_BITS: '<I'}


def _symbol_path(tag):
    """ANSI extended symbolic segment. 길이가 홀수면 1바이트를 채운다."""
    name = tag.encode('ascii')
    seg = bytes([0x91, len(name)]) + name
    if len(name) % 2:
        seg += b'\x00'
    return seg


class EipClient:
    def __init__(self, host, port, slot=0, timeout=5):
        self.host, self.port, self.slot, self.timeout = host, port, slot, timeout
        self.sock = None
        self.session = 0

    def __enter__(self):
        self.sock = socket.create_connection((self.host, self.port), self.timeout)
        self.sock.settimeout(self.timeout)
        reply = self._encap(0x0065, struct.pack('<HH', 1, 0))   # RegisterSession
        self.session = struct.unpack_from('<I', reply, 4)[0]
        return self

    def __exit__(self, *exc):
        try:
            self.sock.sendall(struct.pack('<HHIIQI', 0x0066, 0, self.session, 0, 0, 0))  # UnRegisterSession
        except Exception:
            pass
        self.sock.close()

    # ── 서비스 ────────────────────────────────────────────────────────────

    def read_tag(self, tag):
        """@return (general status, 확장 상태 목록, CIP 타입 코드, 값). 실패면 타입·값은 None."""
        path = _symbol_path(tag)
        req = bytes([0x4C, len(path) // 2]) + path + struct.pack('<H', 1)
        status, ext, data = self._unconnected(req)
        if status != 0 or len(data) < 2:
            return status, ext, None, None
        ctype = struct.unpack_from('<H', data, 0)[0]
        fmt = _PACK.get(ctype)
        value = struct.unpack_from(fmt, data, 2)[0] if fmt and len(data) >= 2 + struct.calcsize(fmt) else None
        return status, ext, ctype, value

    def write_tag(self, tag, ctype, value):
        """@return (general status, 확장 상태 목록)"""
        path = _symbol_path(tag)
        body = struct.pack('<HH', ctype, 1) + struct.pack(_PACK[ctype], value)
        req = bytes([0x4D, len(path) // 2]) + path + body
        status, ext, _ = self._unconnected(req)
        return status, ext

    # ── 전송 ──────────────────────────────────────────────────────────────

    def _unconnected(self, embedded):
        """Connection Manager(class 6, instance 1) 의 Unconnected Send(0x52) 로 감싼다."""
        msg = bytes([0x52, 2, 0x20, 0x06, 0x24, 0x01])          # service, path size(words), path
        msg += bytes([0x0A, 0xF0]) + struct.pack('<H', len(embedded)) + embedded
        if len(embedded) % 2:
            msg += b'\x00'
        msg += bytes([1, 0, 0x01, self.slot])                   # route path: port 1, slot
        cpf = struct.pack('<IHH', 0, 0, 2)                      # interface handle, timeout, item count
        cpf += struct.pack('<HH', 0x0000, 0)                    # null address item
        cpf += struct.pack('<HH', 0x00B2, len(msg)) + msg       # unconnected data item
        reply = self._encap(0x006F, cpf)
        # reply 의 CPF: handle(4) timeout(2) count(2) [type len data]...
        off = 24 + 8
        count = struct.unpack_from('<H', reply, 24 + 6)[0]
        data = b''
        for _ in range(count):
            itype, ilen = struct.unpack_from('<HH', reply, off)
            if itype == 0x00B2:
                data = reply[off + 4: off + 4 + ilen]
            off += 4 + ilen
        # MR reply: service|0x80, reserved, general status, ext status size(words), ext status..., data
        status = data[2]
        ext_words = data[3]
        # 확장 상태 크기만 적고 값을 싣지 않는 응답도 있다. 실린 만큼만 읽는다
        avail = min(ext_words, max(0, (len(data) - 4) // 2))
        ext = list(struct.unpack_from('<%dH' % avail, data, 4)) if avail else []
        return status, ext, data[4 + 2 * avail:]

    def _encap(self, command, data):
        header = struct.pack('<HHIIQI', command, len(data), self.session, 0, 0, 0)
        self.sock.sendall(header + data)
        head = self._recv(24)
        length = struct.unpack_from('<H', head, 2)[0]
        return head + self._recv(length)

    def _recv(self, n):
        buf = b''
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError('connection closed')
            buf += chunk
        return buf
