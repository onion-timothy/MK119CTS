#!/usr/bin/env python3
"""XGT FEnet 전용 프로토콜(XGK) Protocol Definition 생성기.

- 정의 JSON 을 만든다(식은 여기서 조립해 같은 공식이 모든 곳에 똑같이 들어가게 한다).
- 시험 사례의 기대 바이트는 엔진과 무관한 기준 모델(RefPlc)로 계산한다.
출처: LS ELECTRIC XGL-EFMTB 사용설명서 V3.62 7.1(XGT 전용 프로토콜), 9.3(오류 코드).
"""
import json
import sys

# ── 메모리 영역 (XGK) ──────────────────────────────────────────────
# (영역 글자, 워드 수, access)  — 순서가 곧 find($AREAS, 글자) 의 번호
AREAS = [
    ("P", 32, "rw"), ("M", 128, "rw"), ("K", 32, "rw"), ("F", 32, "r"),
    ("T", 32, "rw"), ("C", 32, "rw"), ("Z", 32, "rw"), ("L", 32, "rw"),
    ("N", 32, "rw"), ("D", 512, "rw"), ("R", 128, "rw"),
]
AREA_LETTERS = "".join(a for a, _, _ in AREAS)
SPAN = 65536          # 영역마다 번지 공간(워드). 영역 k 의 첫 워드 = k * SPAN
MAX_BYTES = 1400
CONT = 20             # 0x14 연속


# ── 식 조립 ────────────────────────────────────────────────────────
def b0(V, DT):
    """영역 안의 바이트 위치"""
    return f"({DT} == 0 ? {V}.address // 8 : {DT} == {CONT} ? {V}.address : {V}.address * (1 << ({DT} - 1)))"


def unit_len(DT):
    """개별 읽기·쓰기 한 블록의 바이트 수 (X·B 1, W 2, D 4, L 8)"""
    return f"(1 << max({DT} - 1, 0))"


def base(V):
    return f"find($AREAS, {V}.area) * $SPAN"


def word_count(B0, L):
    return f"(({B0} + {L} + 1) // 2 - {B0} // 2)"


def range_bad(V, DT, L, fn):
    B0 = b0(V, DT)
    return (f"({B0} + {L} + 1) // 2 > $SPAN"
            f" || !{fn}('memory', {base(V)} + {B0} // 2, {word_count(B0, L)})")


def unit_letter(DT):
    return f"({DT} == {CONT} ? 'B' : substr('XBWDL', {DT}, 1))"


# 받는 메시지 공통 규칙
def head_rules(dt_expr):
    return [
        {"name": "badCompanyId",
         "when": "header.companyId != x'4C5349532D5847540000' && header.companyId != x'4C4749532D474C4F4641'",
         "reply": "nak", "args": {"code": "0x75", "dataType": dt_expr}},
        {"name": "badBcc", "when": "$checkBcc != 0 && !valid('bcc')",
         "reply": "nak", "args": {"code": "0x77", "dataType": dt_expr}},
    ]


def nak(name, when, code):
    return {"name": name, "when": when, "reply": "nak", "args": {"code": code, "dataType": "body.dataType"}}


def rw_rules(is_write):
    DT = "body.dataType"
    rules = head_rules(DT)
    rules += [
        nak("badDataType", f"!({DT} in [0, 1, 2, 3, 4, {CONT}])", "0x02"),
        nak("badBlockCount", f"{DT} == {CONT} ? body.blockCount != 1 : body.blockCount < 1 || body.blockCount > 16", "0x01"),
        nak("unitMismatch", f"any(body.vars, it.unit != {unit_letter(DT)})", "0x02"),
        nak("badDevice", "any(body.vars, find($AREAS, it.area) < 0 || it.unit == 'X' && it.area in ['T', 'C'])", "0x03"),
    ]
    if is_write:
        rules += [
            nak("badCount", f"{DT} == {CONT} && (body.blocks[0].size < 1 || body.blocks[0].size > $MAX_BYTES)", "0x05"),
            nak("badDataSize", f"{DT} != {CONT} && any(body.blocks, it.size != {unit_len(DT)})", "0x04"),
        ]
        L = f"({DT} == {CONT} ? body.blocks[0].size : {unit_len(DT)})"
        rules.append(nak("outOfRange", f"any(body.vars, {range_bad('it', DT, L, 'writable')})", "0x04"))
        # 블록 i 를 쓴다: 그 바이트가 걸친 워드를 읽어 고친 뒤 통째로 다시 쓴다
        V, D = "body.vars[i]", "body.blocks[i].data"
        B0 = b0(V, DT)
        L = f"len({D})"
        old = f"read('memory', {base(V)} + {B0} // 2, {word_count(B0, L)})"
        off = f"{B0} % 2"
        bit = f"({V}.address % 8)"
        new = (f"({DT} == 0 ? pack((unpack(substr({old}, {off}, 1)) & ~(1 << {bit}))"
               f" | ((unpack({D}) != 0 ? 1 : 0) << {bit}), 1) : {D})")
        rules.append({
            "name": "writeOk",
            "write": [{"map": "memory", "count": "body.blockCount",
                       "address": f"{base(V)} + {B0} // 2",
                       "bytes": f"substr({old}, 0, {off}) + {new} + substr({old}, {off} + {L})"}],
            "reply": "writeReply"})
    else:
        rules.append(nak("badCount", f"{DT} == {CONT} && (body.dataCount < 1 || body.dataCount > $MAX_BYTES)", "0x05"))
        L = f"({DT} == {CONT} ? body.dataCount : {unit_len(DT)})"
        rules.append(nak("outOfRange", f"any(body.vars, {range_bad('it', DT, L, 'fits')})", "0x04"))
        rules.append({"name": "readOk", "reply": "readReply"})
    return rules


def u16(name, **kw):
    f = {"name": name, "type": "UINT", "size": 2, "byteOrder": "little"}
    f.update(kw)
    return f


def build_definition(tests):
    items = []
    for k, (letter, words, access) in enumerate(AREAS):
        for n in range(words):
            it = {}
            if n == 0:
                it["at"] = k * SPAN
            it.update({"target": f"{letter}{n}", "type": "UINT", "size": 2, "byteOrder": "little"})
            if access == "rw":
                it["access"] = "rw"
            items.append(it)

    # 읽기 응답 블록의 데이터
    DT, V = "req.body.dataType", "req.body.vars[i]"
    B0 = b0(V, DT)
    raw = f"substr(read('memory', {base(V)} + {B0} // 2, {word_count(B0, 'size')}), {B0} % 2, size)"
    data_value = f"{DT} == 0 ? pack((unpack({raw}) >> ({V}.address % 8)) & 1, 1) : {raw}"

    other_dt = "len(body.rest) >= 2 ? unpack(substr(body.rest, 0, 2), 'little') : 0"
    other_bc = "len(body.rest) >= 6 ? unpack(substr(body.rest, 4, 2), 'little') : 0"

    doc = {
        "format": "cts-protocol",
        "version": 1,
        "name": "xgt-fenet",
        "doc": ("LS ELECTRIC XGT FEnet 전용 프로토콜(XGK, TCP 2004·UDP 2005). 개별 읽기·쓰기(%PX·%MB·%DW·%MD·%ML 등, 최대 16블록), "
                "연속 읽기·쓰기(바이트, 최대 1400), 상태 요청(0x00B0)을 지원한다. 디바이스는 P M K F T C Z L N D R 이고 "
                "Target 은 워드 하나씩(D100 등, UINT 16비트)이며 비트·바이트·DWord·LWord 는 그 워드들에서 꺼낸다. "
                "오류는 NAK(Error status 0xFFFF + 오류 코드, 매뉴얼 9.3). 출처: XGL-EFMTB 사용설명서 V3.62 7.1절."),
        "defaults": {"onOverflow": "wrap"},
        "params": {
            "cpuInfo": {"value": "integer", "default": 160, "min": 0, "max": 255,
                        "doc": "헤더 CPU Info. XGK 0xA0(160), XGI 0xA4, XGR 0xA8, XGB(MK) 0xB0, XGB(IEC) 0xB4"},
            "cpuType": {"value": "integer", "default": 1, "min": 0, "max": 63,
                        "doc": "PLC Info bit0~5 CPU 종류. XGK-CPUH 1, CPUS 2, CPUA 3, CPUE 4, CPUU 5, CPUHN 0x11, CPUSN 0x12"},
            "sysState": {"value": "integer", "default": 1, "min": 0, "max": 31,
                         "doc": "운전 상태. RUN 1, STOP 2, ERROR 4, DEBUG 8 (PLC Info bit8~12, 상태 응답 _SYS_STATE)"},
            "position": {"value": "integer", "default": 0, "min": 0, "max": 255,
                         "doc": "FEnet 모듈 위치. bit0~3 슬롯, bit4~7 베이스"},
            "checkBcc": {"value": "integer", "default": 0, "min": 0, "max": 1,
                         "doc": "1 이면 헤더 BCC 가 틀린 요청에 NAK 0x77. 0 이면 BCC 를 보지 않는다(BCC 를 0 으로 보내는 클라이언트용)"},
        },
        "consts": {"READ_REQ": 84, "WRITE_REQ": 88, "STATUS_REQ": 176, "AREAS": AREA_LETTERS, "SPAN": SPAN,
                   "MAX_BYTES": MAX_BYTES, "OS_VERSION": 256},
        "checksums": {"bcc": {"algorithm": "SUM", "width": 8, "covers": {"from": "header.companyId", "to": "header.position"},
                              "onMismatch": "flag", "doc": "Reserved 2(BCC): Company ID 부터 FEnet Position 까지의 바이트 합"}},
        "framing": {"syncOn": "0x4C", "maxLength": 1500, "idleFlushMs": 500},
        "targets": {
            "memory": {
                "kind": "sequence", "base": 0, "slotSize": 2,
                "doc": ("디바이스 메모리. 칸 하나가 워드 하나(UINT 16비트, little endian). 영역 k(" + AREA_LETTERS +
                        " 순서)의 워드 n 은 번지 k×65536 + n. 영역을 넓히려면 그 영역 끝에 항목을 더한다. F 는 읽기 전용"),
                "items": items,
            },
            "status": {"kind": "named", "doc": "상태 응답(0x00B1)의 고장·경고 플래그",
                       "items": [{"target": "_CNF_ER", "value": "integer", "doc": "시스템 에러(중고장) 플래그"},
                                 {"target": "_CNF_WAR", "value": "integer", "doc": "시스템 경고 플래그"}]},
        },
        "structures": {
            "variable": {"doc": "직접 변수 이름. 길이(2) + '%' + 영역 글자 + 크기 글자(X·B·W·D·L) + 10진 번지", "fields": [
                u16("nameLength"),
                {"type": "TEXT", "size": 1, "const": "%"},
                {"name": "area", "type": "TEXT", "size": 1},
                {"name": "unit", "type": "TEXT", "size": 1},
                {"name": "address", "type": "TEXTNUM", "size": "nameLength - 3", "expect": "this >= 0 && this == floor(this)",
                 "doc": "10진 정수 번지. 소수점(XGI 꼴 %MX0.1)이나 음수면 이 메시지가 아니다"},
            ]},
            "writeBlock": {"doc": "쓰기 요청의 데이터 블록", "fields": [
                u16("size"),
                {"name": "data", "type": "BYTES", "size": "size"},
            ]},
            "readBlock": {"doc": "읽기 응답의 데이터 블록. 블록 i 는 요청의 변수 i", "fields": [
                u16("size", value=f"{DT} == {CONT} ? req.body.dataCount : {unit_len(DT)}"),
                {"name": "data", "type": "BYTES", "size": "size", "value": data_value},
            ]},
            "statusData": {"doc": "상태 데이터 24바이트", "fields": [
                {"name": "slotInfo", "type": "UINT", "size": 4, "byteOrder": "little", "value": "($position & 0xFF) << 16"},
                u16("cpuType", value="0xA000 | ($cpuType & 0x3F)"),
                u16("padtCnf", value="0"),
                {"name": "sysState", "type": "UINT", "size": 4, "byteOrder": "little", "value": "$sysState"},
                {"name": "cnfEr", "type": "UINT", "size": 4, "byteOrder": "little", "target": "_CNF_ER"},
                {"name": "cnfWar", "type": "UINT", "size": 4, "byteOrder": "little", "target": "_CNF_WAR"},
                u16("verNum", value="$OS_VERSION"),
                u16("reserved", value="0"),
            ]},
        },
        "frames": {
            "xgt": {
                "doc": "Application header 20바이트. 길이는 본문(명령부터 끝까지) 바이트 수",
                "header": [
                    {"name": "companyId", "type": "BYTES", "size": 10,
                     "value": "req.header.companyId == x'4C4749532D474C4F4641' ? req.header.companyId : x'4C5349532D5847540000'",
                     "doc": "'LSIS-XGT' 00 00 또는 'LGIS-GLOFA'. 응답은 요청의 것을 따른다"},
                    u16("plcInfo", value="($cpuType & 0x3F) | (($sysState & 0x1F) << 8)"),
                    {"name": "cpuInfo", "type": "UINT", "size": 1, "value": "$cpuInfo"},
                    {"name": "source", "type": "UINT", "size": 1, "expect": "this == 0x33", "value": "0x11",
                     "doc": "클라이언트→PLC 0x33, PLC→클라이언트 0x11"},
                    u16("invokeId", value="req.header.invokeId"),
                    u16("length", value="sizeof(body)"),
                    {"name": "position", "type": "UINT", "size": 1, "value": "$position"},
                    {"name": "bcc", "type": "UINT", "size": 1, "checksum": "bcc"},
                ],
                "bodySize": "header.length",
                "footer": [],
            }
        },
        "messages": [
            {"name": "read", "direction": "rx", "frame": "xgt", "match": "body.command == $READ_REQ",
             "doc": "읽기 요청 0x0054. 개별(0x0000 bit ~ 0x0004 lword) 또는 연속(0x0014, 바이트)",
             "body": [u16("command"), u16("dataType"), u16("reserved"), u16("blockCount"),
                      {"name": "vars", "structure": "variable", "count": "blockCount"},
                      u16("dataCount", when=f"dataType == {CONT}")],
             "respond": rw_rules(False)},
            {"name": "write", "direction": "rx", "frame": "xgt", "match": "body.command == $WRITE_REQ",
             "doc": "쓰기 요청 0x0058. 변수 이름 블록들 다음에 데이터 블록들이 같은 수만큼 온다",
             "body": [u16("command"), u16("dataType"), u16("reserved"), u16("blockCount"),
                      {"name": "vars", "structure": "variable", "count": "blockCount"},
                      {"name": "blocks", "structure": "writeBlock", "count": "blockCount"}],
             "respond": rw_rules(True)},
            {"name": "status", "direction": "rx", "frame": "xgt", "match": "body.command == $STATUS_REQ",
             "doc": "상태 요청 0x00B0",
             "body": [u16("command"), u16("dataType"), u16("reserved")],
             "respond": head_rules("body.dataType") + [{"name": "statusOk", "reply": "statusReply"}]},
            {"name": "other", "direction": "rx", "frame": "xgt",
             "doc": "앞 메시지로 읽지 못한 요청: 모르는 명령(0x78), 데이터 타입·블록 수가 틀린 요청, 읽을 수 없는 변수 이름(XGI 꼴 %IX0.0.0, ZR 등 → 0x03)",
             "body": [u16("command"), {"name": "rest", "type": "BYTES", "size": "header.length - 2"}],
             "respond": head_rules(other_dt) + [
                 {"name": "badCommand", "when": "!(body.command in [$READ_REQ, $WRITE_REQ, $STATUS_REQ])",
                  "reply": "nak", "args": {"code": "0x78", "dataType": other_dt}},
                 {"name": "badDataType", "when": f"!(({other_dt}) in [0, 1, 2, 3, 4, {CONT}])",
                  "reply": "nak", "args": {"code": "0x02", "dataType": other_dt}},
                 {"name": "badBlockCount",
                  "when": f"({other_bc}) < 1 || ({other_bc}) > 16 || ({other_dt}) == {CONT} && ({other_bc}) != 1",
                  "reply": "nak", "args": {"code": "0x01", "dataType": other_dt}},
                 {"name": "badVariable", "reply": "nak", "args": {"code": "0x03", "dataType": other_dt}},
             ]},
            {"name": "readReply", "direction": "tx", "frame": "xgt", "doc": "읽기 응답 0x0055 (ACK)",
             "body": [u16("command", value="req.body.command + 1"), u16("dataType", value="req.body.dataType"),
                      u16("reserved", value="0"), u16("errorStatus", value="0"),
                      u16("blockCount", value="req.body.blockCount"),
                      {"name": "blocks", "structure": "readBlock", "count": "req.body.blockCount"}]},
            {"name": "writeReply", "direction": "tx", "frame": "xgt", "doc": "쓰기 응답 0x0059 (ACK)",
             "body": [u16("command", value="req.body.command + 1"), u16("dataType", value="req.body.dataType"),
                      u16("reserved", value="0"), u16("errorStatus", value="0"),
                      u16("blockCount", value="req.body.blockCount")]},
            {"name": "statusReply", "direction": "tx", "frame": "xgt", "doc": "상태 응답 0x00B1",
             "body": [u16("command", value="req.body.command + 1"), u16("dataType", value="0"),
                      u16("reserved", value="0"), u16("errorStatus", value="0"), u16("reserved2", value="0"),
                      u16("dataSize", value="sizeof(body.status)"),
                      {"name": "status", "structure": "statusData"}]},
            {"name": "nak", "direction": "tx", "frame": "xgt",
             "doc": "NAK. 명령은 요청 + 1, Error status 0xFFFF, 오류 코드(args.code): 01 블록 수, 02 데이터 타입, 03 디바이스, 04 영역 초과, 05 1400바이트 초과, 75 회사 ID, 77 BCC, 78 명령",
             "body": [u16("command", value="req.body.command + 1"), u16("dataType", value="args.dataType"),
                      u16("reserved", value="0"), u16("errorStatus", value="0xFFFF"), u16("errorCode", value="args.code")]},
        ],
        "tests": tests,
    }
    return doc


# ── 기준 모델 (엔진과 무관) ────────────────────────────────────────
LSIS = b"LSIS-XGT\x00\x00"
GLOFA = b"LGIS-GLOFA"


def le16(v):
    return (v & 0xFFFF).to_bytes(2, "little")


def frame(body, invoke=0, source=0x33, company=LSIS, plc_info=0, cpu_info=0, position=0, bcc=None):
    h = company + le16(plc_info) + bytes([cpu_info, source]) + le16(invoke) + le16(len(body)) + bytes([position])
    h += bytes([sum(h) & 0xFF if bcc is None else bcc])
    return h + body


def hx(b):
    return " ".join(f"{x:02X}" for x in b)


def var(name):
    n = name.encode("ascii")
    return le16(len(n)) + n


class RefPlc:
    """XGT 메모리 기준 모델: 영역마다 바이트 배열(워드 little endian)"""
    UNIT = {"X": 0, "B": 1, "W": 2, "D": 3, "L": 4}

    def __init__(self, cpu_info=0xA0, cpu_type=1, sys_state=1, position=0):
        self.mem = {a: bytearray(2 * w) for a, w, _ in AREAS}
        self.access = {a: acc for a, _, acc in AREAS}
        self.cpu_info, self.cpu_type, self.sys_state, self.position = cpu_info, cpu_type, sys_state, position
        self.flags = {"_CNF_ER": 0, "_CNF_WAR": 0}

    def set_word(self, target, v):
        a, n = target[0], int(target[1:])
        self.mem[a][2 * n:2 * n + 2] = le16(v)

    def word(self, target):
        a, n = target[0], int(target[1:])
        return int.from_bytes(self.mem[a][2 * n:2 * n + 2], "little")

    def reply(self, body, invoke=0, company=LSIS):
        h = (company + le16((self.cpu_type & 0x3F) | ((self.sys_state & 0x1F) << 8)) + bytes([self.cpu_info, 0x11])
             + le16(invoke) + le16(len(body)) + bytes([self.position]))
        return h + bytes([sum(h) & 0xFF]) + body

    def nak(self, cmd, dt, code, invoke=0, company=LSIS):
        return self.reply(le16(cmd + 1) + le16(dt) + le16(0) + le16(0xFFFF) + le16(code), invoke, company)

    @staticmethod
    def parse(name):
        assert name[0] == "%"
        return name[1], name[2], int(name[3:])

    def span(self, dt, unit, n, length=None):
        if dt == 0x14:
            return n, length
        if unit == "X":
            return n // 8, 1
        size = 1 << (self.UNIT[unit] - 1)
        return n * size, size

    def check(self, dt, names, lengths, write):
        if dt not in (0, 1, 2, 3, 4, 0x14):
            return 2
        cnt = len(names)
        if (dt == 0x14 and cnt != 1) or (dt != 0x14 and not 1 <= cnt <= 16):
            return 1
        want = "B" if dt == 0x14 else "XBWDL"[dt]
        for nm in names:
            if self.parse(nm)[1] != want:
                return 2
        for nm in names:
            a, u, _ = self.parse(nm)
            if a not in self.mem or (u == "X" and a in "TC"):
                return 3
        if dt == 0x14 and not 1 <= lengths[0] <= MAX_BYTES:
            return 5
        if write and dt != 0x14:
            unit_size = 1 if dt <= 1 else 1 << (dt - 1)
            if any(L != unit_size for L in lengths):
                return 4
        for k, nm in enumerate(names):
            a, u, n = self.parse(nm)
            b, L = self.span(dt, u, n, lengths[k] if dt == 0x14 else None)
            if b + L > len(self.mem[a]) or (write and self.access[a] != "rw"):
                return 4
        return 0

    def read(self, dt, names, count=None, invoke=0):
        lengths = [count] if dt == 0x14 else [0] * len(names)
        err = self.check(dt, names, lengths, False)
        if err:
            return self.nak(0x54, dt, err, invoke)
        blocks = b""
        for nm in names:
            a, u, n = self.parse(nm)
            b, L = self.span(dt, u, n, count)
            data = bytes(self.mem[a][b:b + L])
            if dt == 0:
                data = bytes([(data[0] >> (n % 8)) & 1])
            blocks += le16(len(data)) + data
        return self.reply(le16(0x55) + le16(dt) + le16(0) + le16(0) + le16(len(names)) + blocks, invoke)

    def write(self, dt, names, datas, invoke=0):
        err = self.check(dt, names, [len(d) for d in datas], True)
        if err:
            return self.nak(0x58, dt, err, invoke), {}
        touched = {}
        for nm, d in zip(names, datas):
            a, u, n = self.parse(nm)
            b, L = self.span(dt, u, n, len(d))
            if dt == 0:
                bit = n % 8
                v = self.mem[a][b]
                self.mem[a][b] = (v & ~(1 << bit) & 0xFF) | ((1 if d[0] else 0) << bit)
            else:
                self.mem[a][b:b + L] = d
            for w in range(b // 2, (b + L + 1) // 2):
                touched[f"{a}{w}"] = int.from_bytes(self.mem[a][2 * w:2 * w + 2], "little")
        return self.reply(le16(0x59) + le16(dt) + le16(0) + le16(0) + le16(len(names)), invoke), touched

    def status(self, invoke=0):
        data = ((self.position & 0xFF) << 16).to_bytes(4, "little") + le16(0xA000 | (self.cpu_type & 0x3F)) + le16(0) \
            + self.sys_state.to_bytes(4, "little") + self.flags["_CNF_ER"].to_bytes(4, "little") \
            + self.flags["_CNF_WAR"].to_bytes(4, "little") + le16(256) + le16(0)
        return self.reply(le16(0xB1) + le16(0) + le16(0) + le16(0) + le16(0) + le16(len(data)) + data, invoke)


def read_req(dt, names, count=None):
    body = le16(0x54) + le16(dt) + le16(0) + le16(len(names)) + b"".join(var(n) for n in names)
    if dt == 0x14:
        body += le16(count)
    return body


def write_req(dt, names, datas):
    return (le16(0x58) + le16(dt) + le16(0) + le16(len(names)) + b"".join(var(n) for n in names)
            + b"".join(le16(len(d)) + d for d in datas))


def make_tests():
    plc = RefPlc()
    tests = []

    def t(name, send, expect, data=None, writes=None, params=None):
        x = {"name": name}
        if data:
            x["data"] = data
        x["send"] = hx(send)
        x["expect"] = None if expect is None else hx(expect)
        if writes:
            x["expectWrites"] = writes
        if params:
            x["params"] = params
        tests.append(x)

    seed = {"D100": 0x1234, "D101": 0xABCD, "M1": 0x0203, "M4": 0x1111, "M5": 0x2222, "M6": 0x3333, "M7": 0x4444,
            "P0": 0x0400, "D20": 0x5678, "D21": 0x9ABC, "M2": 0x00F0, "D5": 0x0102, "D6": 0x0304, "D7": 0, "F3": 0x00AA}
    for k, v in seed.items():
        plc.set_word(k, v)
    first = dict(seed)

    # 매뉴얼 표 5-6 의 요청(%MW0 개별 읽기)을 그대로 — 헤더 BCC 0x3C
    req = frame(read_req(2, ["%MW0"]), cpu_info=0xA0)
    assert req[19] == 0x3C, hex(req[19])
    t("매뉴얼 예: %MW0 워드 읽기", req, plc.read(2, ["%MW0"]), data=first)
    t("%DW100 워드, Invoke ID 되돌림", frame(read_req(2, ["%DW100"]), invoke=0x1234), plc.read(2, ["%DW100"], invoke=0x1234))
    t("워드 3블록 %DW100 %DW101 %MW1", frame(read_req(2, ["%DW100", "%DW101", "%MW1"])), plc.read(2, ["%DW100", "%DW101", "%MW1"]))
    t("DWord %DD10 (D20·D21)", frame(read_req(3, ["%DD10"])), plc.read(3, ["%DD10"]))
    t("LWord %ML1 (M4~M7)", frame(read_req(4, ["%ML1"])), plc.read(4, ["%ML1"]))
    t("Byte %MB3 (M1 윗바이트)", frame(read_req(1, ["%MB3"])), plc.read(1, ["%MB3"]))
    t("Bit %MX17 %PX10 %MX16", frame(read_req(0, ["%MX17", "%PX10", "%MX16"])), plc.read(0, ["%MX17", "%PX10", "%MX16"]))
    t("연속 읽기 %DB201 5바이트 (홀수 시작)", frame(read_req(0x14, ["%DB201"], 5)), plc.read(0x14, ["%DB201"], 5))
    t("연속 읽기 %MB0 16바이트", frame(read_req(0x14, ["%MB0"], 16)), plc.read(0x14, ["%MB0"], 16))
    t("LGIS-GLOFA 회사 ID 는 그대로 응답", frame(read_req(2, ["%DW100"]), company=GLOFA),
      plc.reply(plc.read(2, ["%DW100"])[20:], company=GLOFA))

    rep, w = plc.write(2, ["%DW7"], [le16(0xBEEF)])
    t("워드 쓰기 %DW7 = 0xBEEF", frame(write_req(2, ["%DW7"], [le16(0xBEEF)])), rep, writes=w)
    rep, w = plc.write(0, ["%MX33", "%MX36"], [b"\x01", b"\x01"])
    t("비트 쓰기 %MX33 %MX36 켜기 (같은 워드 두 블록)", frame(write_req(0, ["%MX33", "%MX36"], [b"\x01", b"\x01"])), rep, writes=w)
    rep, w = plc.write(0, ["%MX36"], [b"\x00"])
    t("비트 쓰기 %MX36 끄기", frame(write_req(0, ["%MX36"], [b"\x00"])), rep, writes=w)
    rep, w = plc.write(1, ["%MB5"], [b"\xAB"])
    t("바이트 쓰기 %MB5 (M2 윗바이트)", frame(write_req(1, ["%MB5"], [b"\xAB"])), rep, writes=w)
    dd = (0xCAFEBABE).to_bytes(4, "little")
    rep, w = plc.write(3, ["%DD20"], [dd])
    t("DWord 쓰기 %DD20 (D40·D41)", frame(write_req(3, ["%DD20"], [dd])), rep, writes=w)
    rep, w = plc.write(0x14, ["%DB11"], [b"\x11\x22\x33"])
    t("연속 쓰기 %DB11 3바이트 (홀수 시작)", frame(write_req(0x14, ["%DB11"], [b"\x11\x22\x33"])), rep, writes=w)
    t("쓴 값 다시 읽기 %DW5 %DW6 %MW2", frame(read_req(2, ["%DW5", "%DW6", "%MW2"])), plc.read(2, ["%DW5", "%DW6", "%MW2"]))

    # 오류
    many = [f"%DW{k}" for k in range(17)]
    t("블록 17개 → NAK 01", frame(read_req(2, many)), plc.read(2, many))
    t("데이터 타입 0x0005 → NAK 02", frame(read_req(5, ["%DW1"])), plc.nak(0x54, 5, 2))
    t("타입과 변수 크기가 다름 (W 에 %DB) → NAK 02", frame(read_req(2, ["%DB1"])), plc.read(2, ["%DB1"]))
    t("없는 디바이스 %QW0 → NAK 03", frame(read_req(2, ["%QW0"])), plc.read(2, ["%QW0"]))
    t("타이머 접점 %TX5 → NAK 03", frame(read_req(0, ["%TX5"])), plc.read(0, ["%TX5"]))
    t("XGI 꼴 이름 %MX0.1 → NAK 03", frame(read_req(0, ["%MX0.1"])), plc.nak(0x54, 0, 3))
    t("영역 밖 %DW512 → NAK 04", frame(read_req(2, ["%DW512"])), plc.read(2, ["%DW512"]))
    t("영역 밖 %DD256 (D512·D513) → NAK 04", frame(read_req(3, ["%DD256"])), plc.read(3, ["%DD256"]))
    t("연속 읽기 1401바이트 → NAK 05", frame(read_req(0x14, ["%DB0"], 1401)), plc.read(0x14, ["%DB0"], 1401))
    rep, _ = plc.write(2, ["%FW3"], [le16(1)])
    t("읽기 전용 F 쓰기 → NAK 04", frame(write_req(2, ["%FW3"], [le16(1)])), rep)
    rep, _ = plc.write(2, ["%DW1"], [b"\x01"])
    t("데이터 크기 틀림 (워드에 1바이트) → NAK 04", frame(write_req(2, ["%DW1"], [b"\x01"])), rep)
    t("모르는 명령 0x0060 → NAK 78", frame(le16(0x60) + le16(2) + le16(0)), plc.nak(0x60, 2, 0x78))
    t("회사 ID 틀림 → NAK 75", frame(read_req(2, ["%DW1"]), company=b"ABCD-EFGH\x00"), plc.nak(0x54, 2, 0x75))
    bad = frame(read_req(2, ["%DW100"]), bcc=0x00)
    t("BCC 0 (checkBcc 0 이면 받음)", bad, plc.read(2, ["%DW100"]))
    t("BCC 틀림, checkBcc 1 → NAK 77", bad, plc.nak(0x54, 2, 0x77), params={"checkBcc": 1})
    t("PLC 가 보낸 프레임(0x11) → 응답 없음", frame(read_req(2, ["%DW100"]), source=0x11), None)
    t("앞에 잡음이 있어도 읽음", b"\xFF\x00" + frame(read_req(2, ["%DW100"])), plc.read(2, ["%DW100"]))

    plc.flags["_CNF_WAR"] = 0x10
    t("상태 요청 0x00B0", frame(le16(0xB0) + le16(0) + le16(0)), plc.status(), data={"_CNF_ER": 0, "_CNF_WAR": 16})
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
    # 항목·필드는 한 줄씩 (사람이 읽기 좋게)
    text = pretty(d)
    with open(out, "w", encoding="utf-8") as f:
        f.write(text + "\n")
    print(out, len(text), "bytes,", len(d["tests"]), "tests")
