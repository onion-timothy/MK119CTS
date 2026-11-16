#!/usr/bin/env python3
"""
MK119CTS BACnet 시뮬레이터 테스트 클라이언트 (순수 stdlib, 설치 불필요).

기능:
  - discover : 장비(Device) 발견. 인스턴스 범위를 ReadProperty 로 프로빙한다.
  - objects  : 한 장비의 Object 목록(object-list) 조회.
  - read     : 한 Object 의 Present Value(또는 임의 property) 조회.

왜 프로빙으로 발견하나:
  MK119CTS 는 UDP 47808 을 점유하고, Who-Is 에 대한 I-Am 응답을 <broadcast>:47808 로
  하드코딩해서 보낸다(BACnetDecoder). 같은 PC 에서 47808 을 다시 바인딩할 수 없어 I-Am 을
  받을 수 없다. 반면 Read/RPM 응답은 요청을 보낸 실제 소스 포트로 돌아오므로(MkHandler),
  47809 등 빈 포트에서 유니캐스트로 보내면 정상 수신된다.
  따라서 장비 발견도 "인스턴스별 ReadProperty(object-name)" 프로빙으로 처리한다.

주소 지정(캡처된 마스터 BACnet4J 패킷과 동일):
  NPDU control=0x24(목적지+응답기대), DNET=2, DLEN=6, DADR=00 00 00 00 00 <instance>, hop=FF.
  대상 장비는 DADR 마지막 바이트(=instance)로 식별된다.

예시:
  python3 tools/mk119_bacnet_client.py discover --host 127.0.0.1 --range 1-16
  python3 tools/mk119_bacnet_client.py objects  --host 127.0.0.1 --device 1
  python3 tools/mk119_bacnet_client.py read     --host 127.0.0.1 --device 1 --type analog-input --inst 10
"""
import argparse
import socket
import struct
import sys

# --- BACnet 상수 ---
BVLC_TYPE = 0x81
BVLC_FUNC_UNICAST = 0x0A
NPDU_VERSION = 0x01
NPDU_CONTROL_DEST_EXPECTING_REPLY = 0x24  # 목적지 지정 + 응답 기대
NPDU_CONTROL_SRC_PRESENT = 0x08
NPDU_CONTROL_DEST_PRESENT = 0x20

SVC_READ_PROPERTY = 0x0C
APDU_TYPE_COMPLEX_ACK = 0x30
APDU_TYPE_ERROR = 0x50

PROP_OBJECT_NAME = 0x4D
PROP_OBJECT_LIST = 0x4C
PROP_PRESENT_VALUE = 0x55

# object type 이름 <-> 번호 (BACnetConstants 와 동일)
OBJECT_TYPES = {
    "analog-input": 0, "analog-output": 1, "analog-value": 2,
    "binary-input": 3, "binary-output": 4, "binary-value": 5,
    "multi-state-input": 13, "multi-state-output": 14, "multi-state-value": 19,
    "device": 8,
}
OBJECT_TYPE_NAMES = {v: k for k, v in OBJECT_TYPES.items()}


# ---------------- 패킷 생성 ----------------
def _oid_bytes(obj_type: int, obj_inst: int) -> bytes:
    return struct.pack(">I", ((obj_type & 0x3FF) << 22) | (obj_inst & 0x3FFFFF))


def _npdu(instance: int, dnet: int = 2) -> bytes:
    return bytes([
        NPDU_VERSION,
        NPDU_CONTROL_DEST_EXPECTING_REPLY,
        (dnet >> 8) & 0xFF, dnet & 0xFF,          # DNET
        0x06,                                      # DLEN
        0x00, 0x00, 0x00, 0x00, 0x00, instance & 0xFF,  # DADR (마지막=인스턴스)
        0xFF,                                      # hop count
    ])


def _wrap_bvlc(payload: bytes) -> bytes:
    total = 4 + len(payload)
    return bytes([BVLC_TYPE, BVLC_FUNC_UNICAST, (total >> 8) & 0xFF, total & 0xFF]) + payload


def build_read_property(instance, obj_type, obj_inst, prop_id, invoke_id):
    apdu = bytes([
        0x00,              # PDU type/flags: confirmed request
        0x05,              # max segments / max APDU accepted
        invoke_id & 0xFF,
        SVC_READ_PROPERTY,
    ])
    apdu += bytes([0x0C]) + _oid_bytes(obj_type, obj_inst)  # context tag 0: OID
    apdu += bytes([0x19, prop_id & 0xFF])                   # context tag 1: property id
    return _wrap_bvlc(_npdu(instance) + apdu)


# ---------------- 응답 파싱 ----------------
class ParseError(Exception):
    pass


def _skip_npdu(buf, i):
    """BVLC(4) 이후 NPDU 를 건너뛰고 APDU 시작 인덱스를 돌려준다."""
    version = buf[i]; i += 1
    control = buf[i]; i += 1
    if control & NPDU_CONTROL_DEST_PRESENT:
        i += 2                       # DNET
        dlen = buf[i]; i += 1
        i += dlen                    # DADR
    if control & NPDU_CONTROL_SRC_PRESENT:
        i += 2                       # SNET
        slen = buf[i]; i += 1
        i += slen                    # SADR
    if control & NPDU_CONTROL_DEST_PRESENT:
        i += 1                       # hop count
    return i


def _walk_tags(buf, i, end):
    """[i, end) 구간의 application 태그들을 (kind, value) 로 yield."""
    while i < end:
        tag = buf[i]; i += 1
        tag_class = (tag >> 3) & 1
        tag_num = (tag >> 4) & 0xF
        lvt = tag & 7
        if tag_class == 1:           # context 태그(opening/closing 등)는 건너뜀
            if lvt in (6, 7):
                continue
            i += lvt
            continue
        # application 태그
        if lvt == 5:                 # extended length
            length = buf[i]; i += 1
        else:
            length = lvt
        if tag_num == 12:            # Object Identifier
            val = struct.unpack(">I", buf[i:i + 4])[0]; i += 4
            yield ("oid", ((val >> 22) & 0x3FF, val & 0x3FFFFF))
        elif tag_num == 4:           # Real
            yield ("real", struct.unpack(">f", buf[i:i + 4])[0]); i += 4
        elif tag_num == 7:           # Character String
            enc = buf[i]; i += 1
            s = buf[i:i + length - 1].decode("utf-8", "replace"); i += length - 1
            yield ("str", s)
        elif tag_num == 2:           # Unsigned Int
            yield ("uint", int.from_bytes(buf[i:i + length], "big")); i += length
        elif tag_num == 9:           # Enumerated
            yield ("enum", int.from_bytes(buf[i:i + length], "big")); i += length
        elif tag_num == 1:           # Boolean (값이 lvt 에 들어있음)
            yield ("bool", lvt != 0)
        else:
            yield ("raw", buf[i:i + length].hex()); i += length


def parse_read_response(resp):
    """ReadProperty COMPLEX_ACK 를 파싱해 값 목록을 돌려준다. 에러면 ParseError."""
    if len(resp) < 6 or resp[0] != BVLC_TYPE:
        raise ParseError("BVLC 아님")
    i = _skip_npdu(resp, 4)
    apdu_type = resp[i]
    if apdu_type == APDU_TYPE_ERROR:
        raise ParseError("BACnet Error PDU")
    if apdu_type != APDU_TYPE_COMPLEX_ACK:
        raise ParseError(f"예상치 못한 APDU type 0x{apdu_type:02x}")
    i += 1
    i += 1                                   # invoke id
    i += 1                                   # service choice
    # 여기서부터 결과 태그들. opening 태그 [3](0x3E) 이후 ~ closing [3](0x3F) 사이가 값.
    open_idx = resp.find(0x3E, i)
    close_idx = resp.rfind(0x3F)
    if open_idx == -1 or close_idx == -1 or close_idx <= open_idx:
        raise ParseError("결과 브래킷[3] 없음")
    return list(_walk_tags(resp, open_idx + 1, close_idx))


# ---------------- 송수신 ----------------
def make_socket(bind_port, timeout):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", bind_port))
    s.settimeout(timeout)
    return s


_invoke = 0


def next_invoke():
    global _invoke
    _invoke = (_invoke + 1) & 0xFF
    return _invoke


def request(sock, host, port, pkt):
    sock.sendto(pkt, (host, port))
    # 우리가 보낸 목적지 외 다른 소스 포트에서 와도 받도록 한 번 더 수신 시도
    resp, _ = sock.recvfrom(4096)
    return resp


# ---------------- 명령 ----------------
def cmd_discover(args, sock):
    lo, hi = parse_range(args.range)
    print(f"장비 발견 프로빙: instance {lo}..{hi} (→ {args.host}:{args.port})")
    found = 0
    for inst in range(lo, hi + 1):
        pkt = build_read_property(inst, OBJECT_TYPES["device"], inst,
                                  PROP_OBJECT_NAME, next_invoke())
        try:
            resp = request(sock, args.host, args.port, pkt)
            vals = parse_read_response(resp)
            name = next((v for k, v in vals if k == "str"), "(이름 없음)")
            print(f"  ● Device {inst:<5} object-name = {name!r}")
            found += 1
        except (socket.timeout, ParseError, IndexError):
            continue
    print(f"발견된 장비: {found} 개")


def cmd_objects(args, sock):
    pkt = build_read_property(args.device, OBJECT_TYPES["device"], args.device,
                              PROP_OBJECT_LIST, next_invoke())
    print(f"Device {args.device} 의 object-list 조회 → {args.host}:{args.port}")
    try:
        resp = request(sock, args.host, args.port, pkt)
        vals = parse_read_response(resp)
    except socket.timeout:
        print("  응답 없음(timeout). device 인스턴스/host/port 확인.")
        sys.exit(1)
    except ParseError as e:
        print(f"  파싱 실패: {e}")
        sys.exit(1)
    oids = [v for k, v in vals if k == "oid"]
    if not oids:
        print("  object 없음.")
        return
    for t, n in oids:
        tname = OBJECT_TYPE_NAMES.get(t, f"type{t}")
        print(f"  - {tname}, {n}")


def cmd_read(args, sock):
    obj_type = resolve_type(args.type)
    prop = args.prop if args.prop is not None else PROP_PRESENT_VALUE
    pkt = build_read_property(args.device, obj_type, args.inst, prop, next_invoke())
    tname = OBJECT_TYPE_NAMES.get(obj_type, f"type{obj_type}")
    print(f"Device {args.device} / {tname},{args.inst} / property 0x{prop:02x} 조회")
    try:
        resp = request(sock, args.host, args.port, pkt)
        vals = parse_read_response(resp)
    except socket.timeout:
        print("  응답 없음(timeout). 해당 object 시뮬레이션이 활성화돼 있는지 확인.")
        sys.exit(1)
    except ParseError as e:
        print(f"  파싱 실패: {e}")
        sys.exit(1)
    if not vals:
        print("  값 없음.")
        return
    for k, v in vals:
        print(f"  {k}: {v}")


# ---------------- 보조 ----------------
def parse_range(s):
    if "-" in s:
        a, b = s.split("-", 1)
        return int(a), int(b)
    n = int(s)
    return n, n


def resolve_type(s):
    if s in OBJECT_TYPES:
        return OBJECT_TYPES[s]
    return int(s)


def main():
    # 공통 옵션: 서브커맨드 앞/뒤 어디서든 쓸 수 있도록 부모 파서로 둔다.
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--host", default="127.0.0.1", help="시뮬레이터 host (기본 127.0.0.1)")
    common.add_argument("--port", type=int, default=47808, help="시뮬레이터 port (기본 47808)")
    common.add_argument("--bind-port", type=int, default=47809,
                        help="클라이언트 바인딩 포트 (기본 47809, 47808 충돌 회피)")
    common.add_argument("--timeout", type=float, default=1.5, help="응답 대기 초 (기본 1.5)")

    ap = argparse.ArgumentParser(description="MK119CTS BACnet 테스트 클라이언트", parents=[common])
    sub = ap.add_subparsers(dest="cmd", required=True)

    p_disc = sub.add_parser("discover", parents=[common], help="장비 발견(인스턴스 프로빙)")
    p_disc.add_argument("--range", default="1-16", help="프로빙할 instance 범위 (예: 1-16)")

    p_obj = sub.add_parser("objects", parents=[common], help="object-list 조회")
    p_obj.add_argument("--device", type=int, required=True)

    p_rd = sub.add_parser("read", parents=[common], help="Present Value 등 property 조회")
    p_rd.add_argument("--device", type=int, required=True)
    p_rd.add_argument("--type", required=True, help="object type 이름 또는 번호 (예: analog-input)")
    p_rd.add_argument("--inst", type=int, required=True)
    p_rd.add_argument("--prop", type=lambda x: int(x, 0), default=None,
                      help="property id (기본 present-value=0x55)")

    args = ap.parse_args()
    sock = make_socket(args.bind_port, args.timeout)
    try:
        {"discover": cmd_discover, "objects": cmd_objects, "read": cmd_read}[args.cmd](args, sock)
    finally:
        sock.close()


if __name__ == "__main__":
    main()
