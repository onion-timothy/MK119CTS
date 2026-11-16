#!/usr/bin/env python3
"""BACnet/IP ReadProperty·WriteProperty 서버 Protocol Definition 생성기.

- 정의 JSON 을 만든다(같은 식이 여러 규칙에 똑같이 들어가게 여기서 조립한다).
- 시험 사례의 기대 바이트는 엔진과 무관한 기준 모델(RefDevice)로 계산한다.
범위: BVLC Original-Unicast/Broadcast-NPDU, NPDU(SNET 이 있으면 DNET 으로 응답), Confirmed-Request 의
ReadProperty(12)·WriteProperty(15). 그 밖의 서비스는 Reject, 분할 요청은 Abort. 출처: ANSI/ASHRAE 135 (BACnet) 6·20·15장.
"""
import json
import struct
import sys

# ── 객체 (형식: (객체 종류 번호, 순차 Target 묶음, 이름 앞글자, 개수, 아날로그?, 쓰기?)) ─────────
OBJECTS = [
    (0, "ai", "AI-", 4, True, False),    # analog-input
    (1, "ao", "AO-", 2, True, True),     # analog-output
    (2, "av", "AV-", 4, True, True),     # analog-value
    (3, "bi", "BI-", 4, False, False),   # binary-input
    (4, "bo", "BO-", 2, False, True),    # binary-output
    (5, "bv", "BV-", 4, False, True),    # binary-value
]
DEVICE = 8
MAPS = ",".join(m for _, m, _, _, _, _ in OBJECTS)          # 'ai,ao,av,bi,bo,bv' — 종류 t 의 묶음 = substr($MAPS, t*3, 2)
PREFIXES = "".join(p for _, _, p, _, _, _ in OBJECTS)       # 'AI-AO-AV-BI-BO-BV-'
DEVICE_NAME, VENDOR_NAME, MODEL_NAME = "CTS BACnet Device", "CTS Simulator", "CTS-BIP"
PROTOCOL_REVISION = 14

# 속성 번호 (135 21장 BACnetPropertyIdentifier)
OBJECT_IDENTIFIER, OBJECT_NAME, OBJECT_TYPE, PRESENT_VALUE = 75, 77, 79, 85
STATUS_FLAGS, OUT_OF_SERVICE = 111, 81
MODEL, PROTOCOL_VERSION, SYSTEM_STATUS, VENDOR_ID, VENDOR, PROTOCOL_REV = 70, 98, 112, 120, 121, 139
DEVICE_PROPS = [OBJECT_IDENTIFIER, OBJECT_NAME, OBJECT_TYPE, MODEL, PROTOCOL_VERSION, SYSTEM_STATUS, VENDOR_ID, VENDOR, PROTOCOL_REV]
IO_PROPS = [OBJECT_IDENTIFIER, OBJECT_NAME, OBJECT_TYPE, OUT_OF_SERVICE, PRESENT_VALUE, STATUS_FLAGS]

# 오류 (class, code) — 135 18장
UNKNOWN_OBJECT = (1, 31)
UNKNOWN_PROPERTY = (2, 32)
NOT_AN_ARRAY = (2, 50)
WRITE_DENIED = (2, 40)
INVALID_DATA_TYPE = (2, 9)
OUT_OF_RANGE = (2, 37)
REJECT_UNRECOGNIZED_SERVICE = 9
ABORT_SEGMENTATION_NOT_SUPPORTED = 4

# ── 식 조립 (규칙에 그대로 보이는 짧은 조각) ─────────────────────────────
T, I, P = "body.oid.type", "body.oid.instance", "unpack(body.propId)"
MAP = f"substr($MAPS, {T} * 3, 2)"
EXISTS = f"{T} == 8 ? {I} == $deviceInstance || {I} == 4194303 : {T} <= 5 && fits({MAP}, {I}, 1)"
KNOWN_PROPERTY = (f"{P} in ({T} == 8 ? [{', '.join(map(str, DEVICE_PROPS))}]"
                  f" : [{', '.join(map(str, IO_PROPS))}])")


def char_string(text_expr):
    """CharacterString(UTF-8). 길이(문자 집합 1바이트 포함)가 5 이상이라 늘 0x75 + 길이 꼴이다"""
    return f"x'75' + pack(len({text_expr}) + 1, 1) + x'00' + bytes({text_expr})"


def error(name, when, ec):
    return {"name": name, "when": when, "reply": "error", "args": {"errorClass": str(ec[0]), "errorCode": str(ec[1])}}


def ack(name, when, value):
    return {"name": name, "when": when, "reply": "readAck", "args": {"value": value}}


def head_rules():
    return [error("unknownObject", f"!({EXISTS})", UNKNOWN_OBJECT),
            error("unknownProperty", f"!({KNOWN_PROPERTY})", UNKNOWN_PROPERTY)]


def read_rules():
    io_name = f"substr($PREFIXES, {T} * 3, 3) + text({I})"
    return head_rules() + [
        error("notAnArray", "has(body.arrayIndex)", NOT_AN_ARRAY),
        ack("objectIdentifier", f"{P} == {OBJECT_IDENTIFIER}", f"x'C4' + pack(({T} << 22) | ({T} == 8 ? $deviceInstance : {I}), 4)"),
        ack("objectType", f"{P} == {OBJECT_TYPE}", f"x'91' + pack({T}, 1)"),
        ack("deviceName", f"{P} == {OBJECT_NAME} && {T} == 8", char_string("$DEVICE_NAME")),
        ack("objectName", f"{P} == {OBJECT_NAME}", f"x'75' + pack(len(text({I})) + 4, 1) + x'00' + bytes({io_name})"),
        ack("analogPresentValue", f"{P} == {PRESENT_VALUE} && {T} <= 2", f"x'44' + read({MAP}, {I}, 1)"),
        ack("binaryPresentValue", f"{P} == {PRESENT_VALUE}", f"x'91' + read({MAP}, {I}, 1)"),
        ack("statusFlags", f"{P} == {STATUS_FLAGS}", "x'820400'"),
        ack("outOfService", f"{P} == {OUT_OF_SERVICE}", "x'10'"),
        ack("systemStatus", f"{P} == {SYSTEM_STATUS}", "x'9100'"),
        ack("vendorIdentifier", f"{P} == {VENDOR_ID}", "x'22' + pack($vendorId, 2)"),
        ack("vendorName", f"{P} == {VENDOR}", char_string("$VENDOR_NAME")),
        ack("modelName", f"{P} == {MODEL}", char_string("$MODEL_NAME")),
        ack("protocolVersion", f"{P} == {PROTOCOL_VERSION}", "x'2101'"),
        ack("protocolRevision", f"{P} == {PROTOCOL_REV}", f"x'21{PROTOCOL_REVISION:02X}'"),
    ]


def write_rules():
    rules = head_rules() + [
        error("notWritable", f"{P} != {PRESENT_VALUE}", WRITE_DENIED),
        error("notAnArray", "has(body.arrayIndex)", NOT_AN_ARRAY),
        error("readOnly", f"{T} == 8 || !writable({MAP}, {I}, 1)", WRITE_DENIED),
        error("badPriority", "has(body.priority) && (body.priority < 1 || body.priority > 16)", OUT_OF_RANGE),
        {"name": "relinquish", "when": "body.valueTag == 0", "reply": "simpleAck",
         "doc": "Null 은 우선순위 배열에서 값을 거두는 것이다. 우선순위 배열이 없으므로 받아들이기만 한다"},
        error("analogNeedsReal", f"{T} <= 2 && body.valueTag != 0x44", INVALID_DATA_TYPE),
        error("binaryNeedsEnumerated", f"{T} > 2 && body.valueTag != 0x91", INVALID_DATA_TYPE),
        error("binaryOutOfRange", f"{T} > 2 && unpack(body.value) > 1", OUT_OF_RANGE),
    ]
    for t, m, _, _, _, rw in OBJECTS:
        if rw:
            rules.append({"name": "write" + m.upper(), "when": f"{T} == {t}", "reply": "simpleAck",
                          "write": [{"map": m, "address": I, "bytes": "body.value"}]})
    return rules


def u8(name=None, **kw):
    f = {"type": "UINT", "size": 1}
    if name:
        f = {"name": name, **f}
    f.update(kw)
    return f


def apdu_head(service):
    return [u8("pduType", expect="this == 0x00 || this == 0x02", doc="Confirmed-Request, 분할하지 않음 (0x02 는 분할 응답 수락 표시)"),
            u8("maxResponse"), u8("invokeId"), u8("service", const=service),
            u8(const=0x0C, doc="문맥 태그 0, 4바이트 — Object Identifier"),
            {"name": "oid", "type": "BITS", "size": 4, "fields": [{"name": "type", "bit": 22, "bits": 10}, {"name": "instance", "bit": 0, "bits": 22}]},
            u8("propTag", expect="(this & 0xF8) == 0x18 && (this & 7) >= 1 && (this & 7) <= 4", doc="문맥 태그 1 — Property Identifier, 1~4바이트"),
            {"name": "propId", "type": "BYTES", "size": "propTag & 7"}]


def tx_head(pdu, service=None, service_value=None):
    body = [u8(const=pdu), u8("invokeId", value="req.body.invokeId")]
    if service is not None:
        body.append(u8(const=service))
    if service_value is not None:
        body.append(u8("service", value=service_value))
    return body


def build_definition(tests):
    targets = {}
    for t, m, prefix, n, analog, rw in OBJECTS:
        items = []
        for k in range(1, n + 1):
            if analog:
                it = {"target": f"{prefix}{k}", "type": "FLOAT", "size": 4}
            else:
                it = {"type": "BITS", "size": 1, "fields": [{"bit": 0, "target": f"{prefix}{k}"}]}
            if rw:
                it["access"] = "rw"
            items.append(it)
        kind = ("analog" if analog else "binary") + ("-" + ("output" if t in (1, 4) else "value" if t in (2, 5) else "input"))
        targets[m] = {"kind": "sequence", "base": 1, "slotSize": 4 if analog else 1,
                      "doc": f"{kind} 객체(종류 {t}). 번지가 곧 인스턴스 번호(1부터). "
                             + ("Present_Value 는 REAL" if analog else "Present_Value 는 0(inactive)·1(active)")
                             + ("" if rw else ", 읽기 전용"),
                      "items": items}

    rx_read = {"name": "readProperty", "direction": "rx", "frame": "bip", "doc": "ReadProperty 요청 (서비스 12)",
               "body": apdu_head(12) + [
                   u8("indexTag", when="header.length - sizeof(header) > 10 + len(propId)",
                      expect="(this & 0xF8) == 0x28 && (this & 7) >= 1 && (this & 7) <= 4",
                      doc="문맥 태그 2 — Property Array Index. 본문이 Property Identifier 뒤에도 남았을 때만"),
                   {"name": "arrayIndex", "type": "BYTES", "size": "indexTag & 7", "when": "has(body.indexTag)"}],
               "respond": read_rules()}
    rx_write = {"name": "writeProperty", "direction": "rx", "frame": "bip", "doc": "WriteProperty 요청 (서비스 15)",
                "body": apdu_head(15) + [
                    u8("nextTag", expect="this == 0x3E || (this & 0xF8) == 0x28 && (this & 7) >= 1 && (this & 7) <= 4",
                       doc="여는 태그 3(값의 시작) 또는 문맥 태그 2(Property Array Index)"),
                    {"name": "arrayIndex", "type": "BYTES", "size": "nextTag & 7", "when": "nextTag != 0x3E"},
                    u8("openTag", const=0x3E, when="nextTag != 0x3E"),
                    u8("valueTag", expect="(this & 0x08) == 0 && (this & 7) <= 4",
                       doc="응용 태그 하나 (길이 4 이하). 0x00 Null, 0x44 REAL, 0x91 Enumerated 1바이트 …"),
                    {"name": "value", "type": "BYTES", "size": "(valueTag >> 4) == 1 ? 0 : valueTag & 7",
                     "doc": "Boolean(태그 1)은 값이 태그 안에 있어 내용이 없다"},
                    u8(const=0x3F, doc="닫는 태그 3"),
                    u8("priorityTag", const=0x49,
                       when="header.length - sizeof(header) > 13 + len(propId) + len(value) + (has(body.arrayIndex) ? len(body.arrayIndex) + 1 : 0)",
                       doc="문맥 태그 4 — Priority. 값 뒤에도 본문이 남았을 때만"),
                    u8("priority", when="has(body.priorityTag)")],
                "respond": write_rules()}
    rx_seg = {"name": "segmentedRequest", "direction": "rx", "frame": "bip", "doc": "분할된 Confirmed-Request — 분할을 지원하지 않는다",
              "body": [u8("pduType", expect="(this & 0xF0) == 0 && (this & 0x08) != 0"), u8("maxResponse"), u8("invokeId"),
                       {"name": "rest", "type": "BYTES", "size": "header.length - sizeof(header) - 3"}],
              "respond": [{"reply": "abort", "args": {"reason": str(ABORT_SEGMENTATION_NOT_SUPPORTED)}}]}
    rx_other = {"name": "otherService", "direction": "rx", "frame": "bip", "doc": "그 밖의 Confirmed 서비스 — Reject(unrecognized-service)",
                "body": [u8("pduType", expect="this == 0x00 || this == 0x02"), u8("maxResponse"), u8("invokeId"), u8("service"),
                         {"name": "rest", "type": "BYTES", "size": "header.length - sizeof(header) - 4"}],
                "respond": [{"reply": "reject", "args": {"reason": str(REJECT_UNRECOGNIZED_SERVICE)}}]}

    read_ack = {"name": "readAck", "direction": "tx", "frame": "bip", "doc": "ReadProperty-ACK (Complex-ACK). 값의 인코딩은 응답 규칙의 args.value",
                "body": tx_head(0x30, service=12) + [
                    u8(const=0x0C), {"name": "oid", "type": "UINT", "size": 4, "value": "(req.body.oid.type << 22) | req.body.oid.instance"},
                    u8("propTag", value="req.body.propTag"),
                    {"name": "propId", "type": "BYTES", "size": "len(req.body.propId)", "value": "req.body.propId"},
                    u8(const=0x3E), {"name": "value", "type": "BYTES", "size": "len(args.value)", "value": "args.value"}, u8(const=0x3F)]}
    simple_ack = {"name": "simpleAck", "direction": "tx", "frame": "bip", "doc": "WriteProperty 의 Simple-ACK", "body": tx_head(0x20, service=15)}
    err = {"name": "error", "direction": "tx", "frame": "bip", "doc": "Error PDU. args.errorClass, args.errorCode",
           "body": tx_head(0x50, service_value="req.body.service") + [
               u8(const=0x91), u8("errorClass", value="args.errorClass"), u8(const=0x91), u8("errorCode", value="args.errorCode")]}
    reject = {"name": "reject", "direction": "tx", "frame": "bip", "doc": "Reject PDU", "body": tx_head(0x60) + [u8("reason", value="args.reason")]}
    abort = {"name": "abort", "direction": "tx", "frame": "bip", "doc": "Abort PDU (서버가 보냄)", "body": tx_head(0x71) + [u8("reason", value="args.reason")]}

    return {
        "format": "cts-protocol",
        "version": 1,
        "name": "bacnet-ip",
        "doc": ("BACnet/IP 서버의 ReadProperty·WriteProperty (UDP 47808). 객체는 Device 하나와 AI·AO·AV·BI·BO·BV 이고, "
                "I/O 객체의 Present_Value 가 Target 하나씩이다(AV-1 등). Device 는 Object_Name·Vendor 정보 같은 고정 속성만 읽힌다. "
                "SNET 이 있는 요청(라우터 경유)에는 DNET 으로 응답한다. 다른 서비스는 Reject, 분할 요청은 Abort, "
                "Who-Is 같은 Unconfirmed 요청에는 응답하지 않는다. 우선순위 배열은 없으며 Priority 는 범위만 검사한다."),
        "params": {
            "deviceInstance": {"value": "integer", "default": 1001, "min": 0, "max": 4194302, "doc": "Device 객체의 인스턴스 번호"},
            "vendorId": {"value": "integer", "default": 999, "min": 0, "max": 65535, "doc": "Vendor_Identifier"},
        },
        "consts": {"MAPS": MAPS, "PREFIXES": PREFIXES, "DEVICE_NAME": DEVICE_NAME, "VENDOR_NAME": VENDOR_NAME, "MODEL_NAME": MODEL_NAME},
        "targets": targets,
        "frames": {
            "bip": {
                "doc": "BVLC(4바이트) + NPDU. 본문은 APDU",
                "header": [
                    u8("bvlcType", const=0x81, doc="BACnet/IP"),
                    u8("bvlcFunction", value="0x0A", expect="this == 0x0A || this == 0x0B", doc="Original-Unicast-NPDU(0x0A) · Original-Broadcast-NPDU(0x0B)"),
                    {"name": "length", "type": "UINT", "size": 2,
                     "value": "6 + ((npduControl & 0x28) != 0 ? 3 + addressLength : 0) + ((npduControl & 0x20) != 0 ? 1 : 0) + sizeof(body)",
                     "doc": "BVLC 길이 — 패킷 전체. 헤더(BVLC 4 + NPDU 2 + DNET 이 있으면 3 + 주소 + 홉 수 1)와 본문의 합. "
                            "자기 자신이 든 sizeof(header) 는 쓸 수 없어 헤더 크기를 직접 센다"},
                    u8("npduVersion", const=1),
                    u8("npduControl", value="(req.header.npduControl & 0x08) != 0 ? 0x20 : 0", expect="(this & 0xF0) == 0",
                       doc="받을 때 DNET(0x20)·네트워크 메시지(0x80)는 받지 않는다. SNET(0x08)이 있으면 DNET 으로 응답"),
                    {"name": "net", "type": "UINT", "size": 2, "when": "(npduControl & 0x28) != 0", "value": "req.header.net",
                     "doc": "받을 때 SNET, 보낼 때 DNET"},
                    u8("addressLength", when="(npduControl & 0x28) != 0", value="req.header.addressLength"),
                    {"name": "address", "type": "BYTES", "size": "addressLength", "when": "(npduControl & 0x28) != 0", "value": "req.header.address"},
                    u8("hopCount", when="(npduControl & 0x20) != 0", value="255"),
                ],
                "footer": [],
                "bodySize": "header.length - sizeof(header)",
            }
        },
        "messages": [rx_read, rx_write, rx_seg, rx_other, read_ack, simple_ack, err, reject, abort],
        "tests": tests,
    }


# ── 기준 모델 (엔진과 무관하게 BACnet 을 직접 인코딩) ─────────────────────────
def enc_unsigned(v):
    n = 1 if v < 0x100 else 2 if v < 0x10000 else 3 if v < 0x1000000 else 4
    return v.to_bytes(n, "big")


def ctx(tag, data):
    assert len(data) <= 4
    return bytes([(tag << 4) | 0x08 | len(data)]) + data


def app_real(v):
    return b"\x44" + struct.pack(">f", v)


def app_enum(v):
    return b"\x91" + bytes([v])


def app_unsigned(v):
    d = enc_unsigned(v)
    return bytes([0x20 | len(d)]) + d


def app_string(s):
    d = b"\x00" + s.encode("utf-8")
    return (bytes([0x70 | len(d)]) if len(d) <= 4 else bytes([0x75, len(d)])) + d


def app_oid(t, inst):
    return b"\xC4" + ((t << 22) | inst).to_bytes(4, "big")


def npdu(control=0x04, snet=None):
    if snet is None:
        return bytes([1, control])
    net, adr = snet
    return bytes([1, control | 0x08]) + net.to_bytes(2, "big") + bytes([len(adr)]) + adr


def bvlc(rest, function=0x0A):
    return bytes([0x81, function]) + (4 + len(rest)).to_bytes(2, "big") + rest


def rp(invoke, t, inst, prop, index=None, snet=None, pdu=0x00):
    apdu = bytes([pdu, 0x05, invoke, 12]) + ctx(0, ((t << 22) | inst).to_bytes(4, "big")) + ctx(1, enc_unsigned(prop))
    if index is not None:
        apdu += ctx(2, enc_unsigned(index))
    return bvlc(npdu(snet=snet) + apdu)


def wp(invoke, t, inst, prop, value, index=None, priority=None, snet=None):
    apdu = bytes([0x00, 0x05, invoke, 15]) + ctx(0, ((t << 22) | inst).to_bytes(4, "big")) + ctx(1, enc_unsigned(prop))
    if index is not None:
        apdu += ctx(2, enc_unsigned(index))
    apdu += b"\x3E" + value + b"\x3F"
    if priority is not None:
        apdu += ctx(4, bytes([priority]))
    return bvlc(npdu(snet=snet) + apdu)


class RefDevice:
    def __init__(self, instance=1001, vendor=999):
        self.instance, self.vendor = instance, vendor
        self.values = {}                       # 'AV-1' → 값
        self.objects = {(t, k): (prefix + str(k), analog, rw)
                        for t, _, prefix, n, analog, rw in OBJECTS for k in range(1, n + 1)}

    def reply_npdu(self, snet):
        if snet is None:
            return bytes([1, 0])
        net, adr = snet
        return bytes([1, 0x20]) + net.to_bytes(2, "big") + bytes([len(adr)]) + adr + b"\xFF"

    def error(self, invoke, service, ec, snet=None):
        return bvlc(self.reply_npdu(snet) + bytes([0x50, invoke, service]) + app_enum(ec[0]) + app_enum(ec[1]))

    def exists(self, t, inst):
        return (inst in (self.instance, 4194303)) if t == DEVICE else (t, inst) in self.objects

    def known(self, t, prop):
        return prop in (DEVICE_PROPS if t == DEVICE else IO_PROPS)

    def property_value(self, t, inst, prop):
        if prop == OBJECT_IDENTIFIER:
            return app_oid(t, self.instance if t == DEVICE else inst)
        if prop == OBJECT_TYPE:
            return app_enum(t)
        if t == DEVICE:
            return {OBJECT_NAME: app_string(DEVICE_NAME), SYSTEM_STATUS: app_enum(0), VENDOR_ID: app_unsigned(self.vendor),
                    VENDOR: app_string(VENDOR_NAME), MODEL: app_string(MODEL_NAME), PROTOCOL_VERSION: app_unsigned(1),
                    PROTOCOL_REV: app_unsigned(PROTOCOL_REVISION)}[prop]
        name, analog, _ = self.objects[(t, inst)]
        if prop == OBJECT_NAME:
            return app_string(name)
        if prop == PRESENT_VALUE:
            v = self.values.get(name, 0)
            return app_real(v) if analog else app_enum(int(v))
        if prop == STATUS_FLAGS:
            return b"\x82\x04\x00"
        if prop == OUT_OF_SERVICE:
            return b"\x10"
        raise AssertionError(prop)

    def read(self, invoke, t, inst, prop, index=None, snet=None):
        if not self.exists(t, inst):
            return self.error(invoke, 12, UNKNOWN_OBJECT, snet)
        if not self.known(t, prop):
            return self.error(invoke, 12, UNKNOWN_PROPERTY, snet)
        if index is not None:
            return self.error(invoke, 12, NOT_AN_ARRAY, snet)
        apdu = (bytes([0x30, invoke, 12]) + ctx(0, ((t << 22) | inst).to_bytes(4, "big")) + ctx(1, enc_unsigned(prop))
                + b"\x3E" + self.property_value(t, inst, prop) + b"\x3F")
        return bvlc(self.reply_npdu(snet) + apdu)

    def write(self, invoke, t, inst, prop, value, index=None, priority=None, snet=None):
        """→ (응답 바이트, 쓴 값 {Target: 값})"""
        e = lambda ec: (self.error(invoke, 15, ec, snet), None)
        if not self.exists(t, inst):
            return e(UNKNOWN_OBJECT)
        if not self.known(t, prop):
            return e(UNKNOWN_PROPERTY)
        if prop != PRESENT_VALUE:
            return e(WRITE_DENIED)
        if index is not None:
            return e(NOT_AN_ARRAY)
        name, analog, rw = self.objects[(t, inst)]
        if not rw:
            return e(WRITE_DENIED)
        if priority is not None and not 1 <= priority <= 16:
            return e(OUT_OF_RANGE)
        ok = bvlc(self.reply_npdu(snet) + bytes([0x20, invoke, 15]))
        if value == b"\x00":
            return ok, None
        if analog:
            if value[0] != 0x44:
                return e(INVALID_DATA_TYPE)
            v = struct.unpack(">f", value[1:])[0]
        else:
            if value[0] != 0x91:
                return e(INVALID_DATA_TYPE)
            if value[1] > 1:
                return e(OUT_OF_RANGE)
            v = value[1]
        self.values[name] = v
        return ok, {name: v}


def hx(b):
    return " ".join(f"{x:02X}" for x in b)


def num(v):
    return int(v) if float(v).is_integer() else v


def make_tests():
    dev = RefDevice()
    tests = []

    def t(name, send, expect, data=None, writes=None, params=None):
        x = {"name": name}
        if data:
            x["data"] = {k: num(v) for k, v in data.items()}
        x["send"] = hx(send)
        x["expect"] = None if expect is None else hx(expect)
        if writes:
            x["expectWrites"] = {k: num(v) for k, v in writes.items()}
        if params:
            x["params"] = params
        tests.append(x)

    seed = {"AI-1": 21.5, "AI-2": -3.25, "AO-1": 50, "AV-1": 12.75, "AV-2": 0, "BI-1": 1, "BI-2": 0, "BO-1": 1, "BV-1": 0, "BV-3": 1}
    dev.values.update(seed)

    # 읽기 — Present_Value
    t("AI-1 Present_Value (REAL)", rp(1, 0, 1, PRESENT_VALUE), dev.read(1, 0, 1, PRESENT_VALUE), data=seed)
    t("AI-2 음수 REAL", rp(2, 0, 2, PRESENT_VALUE), dev.read(2, 0, 2, PRESENT_VALUE))
    t("AV-1 Present_Value", rp(3, 2, 1, PRESENT_VALUE), dev.read(3, 2, 1, PRESENT_VALUE))
    t("BI-1 Present_Value (Enumerated active)", rp(4, 3, 1, PRESENT_VALUE), dev.read(4, 3, 1, PRESENT_VALUE))
    t("BV-1 Present_Value (inactive)", rp(5, 5, 1, PRESENT_VALUE), dev.read(5, 5, 1, PRESENT_VALUE))
    # 읽기 — 그 밖의 속성
    t("AV-1 Object_Name", rp(6, 2, 1, OBJECT_NAME), dev.read(6, 2, 1, OBJECT_NAME))
    t("AI-4 Object_Identifier", rp(7, 0, 4, OBJECT_IDENTIFIER), dev.read(7, 0, 4, OBJECT_IDENTIFIER))
    t("BO-2 Object_Type", rp(8, 4, 2, OBJECT_TYPE), dev.read(8, 4, 2, OBJECT_TYPE))
    t("AO-1 Status_Flags", rp(9, 1, 1, STATUS_FLAGS), dev.read(9, 1, 1, STATUS_FLAGS))
    t("BV-3 Out_Of_Service", rp(10, 5, 3, OUT_OF_SERVICE), dev.read(10, 5, 3, OUT_OF_SERVICE))
    # Device
    t("Device Object_Name", rp(11, DEVICE, 1001, OBJECT_NAME), dev.read(11, DEVICE, 1001, OBJECT_NAME))
    t("Device 와일드카드 인스턴스로 Object_Identifier", rp(12, DEVICE, 4194303, OBJECT_IDENTIFIER), dev.read(12, DEVICE, 4194303, OBJECT_IDENTIFIER))
    t("Device Vendor_Identifier", rp(13, DEVICE, 1001, VENDOR_ID), dev.read(13, DEVICE, 1001, VENDOR_ID))
    t("Device Vendor_Name", rp(14, DEVICE, 1001, VENDOR), dev.read(14, DEVICE, 1001, VENDOR))
    t("Device System_Status", rp(15, DEVICE, 1001, SYSTEM_STATUS), dev.read(15, DEVICE, 1001, SYSTEM_STATUS))
    t("Device Protocol_Revision", rp(16, DEVICE, 1001, PROTOCOL_REV), dev.read(16, DEVICE, 1001, PROTOCOL_REV))
    dev2 = RefDevice(instance=77, vendor=300)
    t("파라미터로 바꾼 Device 인스턴스·Vendor", rp(17, DEVICE, 77, VENDOR_ID), dev2.read(17, DEVICE, 77, VENDOR_ID),
      params={"deviceInstance": 77, "vendorId": 300})
    # 읽기 — 오류
    t("없는 객체 AV-9 → unknown-object", rp(18, 2, 9, PRESENT_VALUE), dev.read(18, 2, 9, PRESENT_VALUE))
    t("인스턴스 0 → unknown-object", rp(19, 0, 0, PRESENT_VALUE), dev.read(19, 0, 0, PRESENT_VALUE))
    t("다른 Device 인스턴스 → unknown-object", rp(20, DEVICE, 1002, OBJECT_NAME), dev.read(20, DEVICE, 1002, OBJECT_NAME))
    t("Priority_Array(87) → unknown-property", rp(21, 2, 1, 87), dev.read(21, 2, 1, 87))
    t("2바이트 속성 번호(512) → unknown-property", rp(22, 2, 1, 512), dev.read(22, 2, 1, 512))
    t("Device 에 Present_Value → unknown-property", rp(23, DEVICE, 1001, PRESENT_VALUE), dev.read(23, DEVICE, 1001, PRESENT_VALUE))
    t("배열 번호를 붙이면 → property-is-not-an-array", rp(24, 2, 1, PRESENT_VALUE, index=1), dev.read(24, 2, 1, PRESENT_VALUE, index=1))

    # 쓰기
    def w(name, invoke, t_, inst, prop, value, **kw):
        reply, writes = dev.write(invoke, t_, inst, prop, value, **kw)
        t(name, wp(invoke, t_, inst, prop, value, **kw), reply, writes=writes)

    w("AV-2 에 REAL 55.5 쓰기", 30, 2, 2, PRESENT_VALUE, app_real(55.5))
    t("쓴 값 다시 읽기 AV-2", rp(31, 2, 2, PRESENT_VALUE), dev.read(31, 2, 2, PRESENT_VALUE))
    w("AO-2 에 우선순위 8 로 쓰기", 32, 1, 2, PRESENT_VALUE, app_real(-12.5), priority=8)
    w("BV-1 을 active 로", 33, 5, 1, PRESENT_VALUE, app_enum(1))
    t("쓴 값 다시 읽기 BV-1", rp(34, 5, 1, PRESENT_VALUE), dev.read(34, 5, 1, PRESENT_VALUE))
    w("BO-1 을 inactive 로", 35, 4, 1, PRESENT_VALUE, app_enum(0), priority=16)
    w("Null 쓰기(우선순위 해제) → 값은 그대로", 36, 2, 1, PRESENT_VALUE, b"\x00", priority=8)
    w("AI-1 쓰기 → write-access-denied", 37, 0, 1, PRESENT_VALUE, app_real(1.0))
    w("BI-2 쓰기 → write-access-denied", 38, 3, 2, PRESENT_VALUE, app_enum(1))
    w("AV-1 Object_Name 쓰기 → write-access-denied", 39, 2, 1, OBJECT_NAME, app_string("X-1"))
    w("AV-1 에 Unsigned 쓰기 → invalid-data-type", 40, 2, 1, PRESENT_VALUE, app_unsigned(5))
    w("BV-2 에 2 쓰기 → value-out-of-range", 41, 5, 2, PRESENT_VALUE, app_enum(2))
    w("BV-2 에 Boolean 쓰기 → invalid-data-type", 42, 5, 2, PRESENT_VALUE, b"\x11")
    w("우선순위 0 → value-out-of-range", 43, 2, 3, PRESENT_VALUE, app_real(1.0), priority=0)
    w("없는 객체 BV-5 쓰기 → unknown-object", 44, 5, 5, PRESENT_VALUE, app_enum(1))
    w("배열 번호를 붙인 쓰기 → property-is-not-an-array", 45, 2, 3, PRESENT_VALUE, app_real(1.0), index=2)

    # 라우터 경유·그 밖의 PDU
    snet = (5, b"\x0A\x00\x00\x07")
    t("SNET 이 있는 요청 → DNET 으로 응답", rp(50, 2, 1, PRESENT_VALUE, snet=snet), dev.read(50, 2, 1, PRESENT_VALUE, snet=snet))
    rpm = bvlc(npdu() + bytes([0x00, 0x05, 51, 14]) + ctx(0, ((2 << 22) | 1).to_bytes(4, "big")) + b"\x1E\x09\x55\x1F")
    t("ReadPropertyMultiple → Reject(unrecognized-service)", rpm, bvlc(bytes([1, 0, 0x60, 51, REJECT_UNRECOGNIZED_SERVICE])))
    seg = bvlc(npdu() + bytes([0x08, 0x05, 52, 0, 4, 12]) + ctx(0, ((2 << 22) | 1).to_bytes(4, "big")) + ctx(1, bytes([85])))
    t("분할 요청 → Abort(segmentation-not-supported)", seg, bvlc(bytes([1, 0, 0x71, 52, ABORT_SEGMENTATION_NOT_SUPPORTED])))
    t("Who-Is(Unconfirmed) → 응답 없음", bvlc(npdu(control=0) + b"\x10\x08", function=0x0B), None)
    t("BVLC 종류가 0x81 이 아니면 응답 없음", b"\x82" + rp(53, 2, 1, PRESENT_VALUE)[1:], None)
    return tests


def pretty(v, ind=0, width=150):
    """짧은 객체·목록은 한 줄로, 나머지는 들여쓰기"""
    one = json.dumps(v, ensure_ascii=False, separators=(", ", ": "))
    if len(one) + ind <= width or not isinstance(v, (dict, list)) or not v:
        return one
    pad, inner = " " * ind, " " * (ind + 2)
    if isinstance(v, dict):
        rows = [inner + json.dumps(k, ensure_ascii=False) + ": " + pretty(x, ind + 2, width) for k, x in v.items()]
        return "{\n" + ",\n".join(rows) + "\n" + pad + "}"
    rows = [inner + pretty(x, ind + 2, width) for x in v]
    return "[\n" + ",\n".join(rows) + "\n" + pad + "]"


if __name__ == "__main__":
    out = sys.argv[1]
    d = build_definition(make_tests())
    text = pretty(d)
    with open(out, "w", encoding="utf-8") as f:
        f.write(text + "\n")
    print(out, len(text), "bytes,", len(d["tests"]), "tests")
