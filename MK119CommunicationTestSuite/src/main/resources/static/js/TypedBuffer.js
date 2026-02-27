class TypedBuffer {
    constructor(mode, param) {
        if(mode==='hexstring') {
            if (!/^[0-9a-fA-F]*$/.test(param) || param.length % 2 !== 0) {
                throw new Error("Invalid hex string");
            }
            this.buffer = new Uint8Array(param.length / 2);
            for (let i = 0; i < param.length; i += 2) {
                this.buffer[i / 2] = parseInt(param.slice(i, i + 2), 16);
            }
            this.view = new DataView(this.buffer.buffer);
            this.offset = 0;
        } else if (mode === 'buffer') {
            this.buffer = new Uint8Array(parseInt(param));
            this.view = new DataView(this.buffer.buffer);
            this.offset = 0;
        }
    }

    read(type, position = this.offset) {
        const typeInfo = TypedBuffer.DataTypes[type];
        if (!typeInfo) throw new Error(`Unsupported data type: ${type}`);
        if (position + typeInfo.byteLength > this.buffer.length)
            throw new Error("Read exceeds buffer size");
        // 바이트 오더 적용
        const bytes = new Uint8Array(this.buffer.buffer, this.buffer.byteOffset + position, typeInfo.byteLength);
        const reordered = TypedBuffer.reorderBytes(bytes, typeInfo.order);
        const result = typeInfo.read(new DataView(reordered.buffer));
        this.offset = position + typeInfo.byteLength;
        return result;
    }

    write(position, type, value) {
        const typeInfo = TypedBuffer.DataTypes[type];
        if (!typeInfo) throw new Error(`Unsupported data type: ${type}`);
        if (position + typeInfo.byteLength > this.buffer.length)
            throw new Error("Write exceeds buffer size");
        // 임시 버퍼에 기록
        const temp = new ArrayBuffer(typeInfo.byteLength);
        const tempView = new DataView(temp);
        typeInfo.write(tempView, value);
        // 바이트 오더 적용
        const tempBytes = new Uint8Array(temp);
        const reordered = TypedBuffer.reorderBytes(tempBytes, typeInfo.order);
        // 실제 버퍼에 기록
        for (let i = 0; i < typeInfo.byteLength; i++) {
            this.buffer[position + i] = reordered[i];
        }
    }

    static reorderBytes(bytes, order) {
        return new Uint8Array(order.map(i => bytes[i]));
    }

    getCRC16() {
        let hi = 0xFF;
        let lo = 0xFF;

        for (let i = 0; i < this.buffer.length-2; i++) {
            const index = hi ^ this.buffer[i];
            hi = lo ^ tab_H[index];
            lo = tab_L[index];
        }

        return (hi << 8) | lo;
    }

    size() {
        return this.buffer.length
    }
}

// 타입 정의 (부호/비부호 8/16/32비트, float, double, 바이트 오더)
TypedBuffer.DataTypes = {
    // 8비트
    UI8: {
        byteLength: 1,
        order: [0],
        read: (view) => view.getUint8(0),
        write: (view, value) => view.setUint8(0, value)
    },
    SI8: {
        byteLength: 1,
        order: [0],
        read: (view) => view.getInt8(0),
        write: (view, value) => view.setInt8(0, value)
    },

    // 16비트
    UI16AB: {
        byteLength: 2,
        order: [0, 1],
        read: (view) => view.getUint16(0, false),
        write: (view, value) => view.setUint16(0, value, false)
    },
    UI16BA: {
        byteLength: 2,
        order: [1, 0],
        read: (view) => view.getUint16(0, false),
        write: (view, value) => view.setUint16(0, value, false)
    },
    SI16AB: {
        byteLength: 2,
        order: [0, 1],
        read: (view) => view.getInt16(0, false),
        write: (view, value) => view.setInt16(0, value, false)
    },
    SI16BA: {
        byteLength: 2,
        order: [1, 0],
        read: (view) => view.getInt16(0, false),
        write: (view, value) => view.setInt16(0, value, false)
    },

    // 32비트
    UI32AD: {
        byteLength: 4,
        order: [0, 1, 2, 3],
        read: (view) => view.getUint32(0, false),
        write: (view, value) => view.setUint32(0, value, false)
    },
    UI32CB: {
        byteLength: 4,
        order: [2, 3, 0, 1],
        read: (view) => view.getUint32(0, false),
        write: (view, value) => view.setUint32(0, value, false)
    },
    UI32BC: {
        byteLength: 4,
        order: [1, 0, 3, 2],
        read: (view) => view.getUint32(0, false),
        write: (view, value) => view.setUint32(0, value, false)
    },
    UI32DA: {
        byteLength: 4,
        order: [3, 2, 1, 0],
        read: (view) => view.getUint32(0, false),
        write: (view, value) => view.setUint32(0, value, false)
    },

    SI32AD: {
        byteLength: 4,
        order: [0, 1, 2, 3],
        read: (view) => view.getInt32(0, false),
        write: (view, value) => view.setInt32(0, value, false)
    },
    SI32CB: {
        byteLength: 4,
        order: [2, 3, 0, 1],
        read: (view) => view.getInt32(0, false),
        write: (view, value) => view.setInt32(0, value, false)
    },
    SI32BC: {
        byteLength: 4,
        order: [1, 0, 3, 2],
        read: (view) => view.getInt32(0, false),
        write: (view, value) => view.setInt32(0, value, false)
    },
    SI32DA: {
        byteLength: 4,
        order: [3, 2, 1, 0],
        read: (view) => view.getInt32(0, false),
        write: (view, value) => view.setInt32(0, value, false)
    },

    // 64비트 (BigInt)
    SI64AH: {
        byteLength: 8,
        order: [0, 1, 2, 3, 4, 5, 6, 7],
        read: (view) => view.getBigInt64(0, false),
        write: (view, value) => view.setBigInt64(0, BigInt(value), false)
    },
    SI64GB: {
        byteLength: 8,
        order: [6, 7, 4, 5, 2, 3, 0, 1],
        read: (view) => view.getBigInt64(0, false),
        write: (view, value) => view.setBigInt64(0, BigInt(value), false)
    },
    SI64BG: {
        byteLength: 8,
        order: [1, 0, 3, 2, 5, 4, 7, 6],
        read: (view) => view.getBigInt64(0, false),
        write: (view, value) => view.setBigInt64(0, BigInt(value), false)
    },
    SI64HA: {
        byteLength: 8,
        order: [7, 6, 5, 4, 3, 2, 1, 0],
        read: (view) => view.getBigInt64(0, false),
        write: (view, value) => view.setBigInt64(0, BigInt(value), false)
    },

    // 32비트 float
    FP32AD: {
        byteLength: 4,
        order: [0, 1, 2, 3],
        read: (view) => view.getFloat32(0, false),
        write: (view, value) => view.setFloat32(0, value, false)
    },
    FP32CB: {
        byteLength: 4,
        order: [2, 3, 0, 1],
        read: (view) => view.getFloat32(0, false),
        write: (view, value) => view.setFloat32(0, value, false)
    },
    FP32BC: {
        byteLength: 4,
        order: [1, 0, 3, 2],
        read: (view) => view.getFloat32(0, false),
        write: (view, value) => view.setFloat32(0, value, false)
    },
    FP32DA: {
        byteLength: 4,
        order: [3, 2, 1, 0],
        read: (view) => view.getFloat32(0, false),
        write: (view, value) => view.setFloat32(0, value, false)
    },

    // 64비트 float
    FP64AH: {
        byteLength: 8,
        order: [0, 1, 2, 3, 4, 5, 6, 7],
        read: (view) => view.getFloat64(0, false),
        write: (view, value) => view.setFloat64(0, value, false)
    },
    FP64GB: {
        byteLength: 8,
        order: [6, 7, 4, 5, 2, 3, 0, 1],
        read: (view) => view.getFloat64(0, false),
        write: (view, value) => view.setFloat64(0, value, false)
    },
    FP64BG: {
        byteLength: 8,
        order: [1, 0, 3, 2, 5, 4, 7, 6],
        read: (view) => view.getFloat64(0, false),
        write: (view, value) => view.setFloat64(0, value, false)
    },
    FP64HA: {
        byteLength: 8,
        order: [7, 6, 5, 4, 3, 2, 1, 0],
        read: (view) => view.getFloat64(0, false),
        write: (view, value) => view.setFloat64(0, value, false)
    }
};

window.TypedBuffer = TypedBuffer;

const tab_H = [
    0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
    0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
    0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
    0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
    0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
    0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
    0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
    0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
    0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
    0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
    0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
    0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
    0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
    0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
    0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
    0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40
];

const tab_L = [
    0x00, 0xC0, 0xC1, 0x01, 0xC3, 0x03, 0x02, 0xC2, 0xC6, 0x06, 0x07, 0xC7, 0x05, 0xC5, 0xC4, 0x04,
    0xCC, 0x0C, 0x0D, 0xCD, 0x0F, 0xCF, 0xCE, 0x0E, 0x0A, 0xCA, 0xCB, 0x0B, 0xC9, 0x09, 0x08, 0xC8,
    0xD8, 0x18, 0x19, 0xD9, 0x1B, 0xDB, 0xDA, 0x1A, 0x1E, 0xDE, 0xDF, 0x1F, 0xDD, 0x1D, 0x1C, 0xDC,
    0x14, 0xD4, 0xD5, 0x15, 0xD7, 0x17, 0x16, 0xD6, 0xD2, 0x12, 0x13, 0xD3, 0x11, 0xD1, 0xD0, 0x10,
    0xF0, 0x30, 0x31, 0xF1, 0x33, 0xF3, 0xF2, 0x32, 0x36, 0xF6, 0xF7, 0x37, 0xF5, 0x35, 0x34, 0xF4,
    0x3C, 0xFC, 0xFD, 0x3D, 0xFF, 0x3F, 0x3E, 0xFE, 0xFA, 0x3A, 0x3B, 0xFB, 0x39, 0xF9, 0xF8, 0x38,
    0x28, 0xE8, 0xE9, 0x29, 0xEB, 0x2B, 0x2A, 0xEA, 0xEE, 0x2E, 0x2F, 0xEF, 0x2D, 0xED, 0xEC, 0x2C,
    0xE4, 0x24, 0x25, 0xE5, 0x27, 0xE7, 0xE6, 0x26, 0x22, 0xE2, 0xE3, 0x23, 0xE1, 0x21, 0x20, 0xE0,
    0xA0, 0x60, 0x61, 0xA1, 0x63, 0xA3, 0xA2, 0x62, 0x66, 0xA6, 0xA7, 0x67, 0xA5, 0x65, 0x64, 0xA4,
    0x6C, 0xAC, 0xAD, 0x6D, 0xAF, 0x6F, 0x6E, 0xAE, 0xAA, 0x6A, 0x6B, 0xAB, 0x69, 0xA9, 0xA8, 0x68,
    0x78, 0xB8, 0xB9, 0x79, 0xBB, 0x7B, 0x7A, 0xBA, 0xBE, 0x7E, 0x7F, 0xBF, 0x7D, 0xBD, 0xBC, 0x7C,
    0xB4, 0x74, 0x75, 0xB5, 0x77, 0xB7, 0xB6, 0x76, 0x72, 0xB2, 0xB3, 0x73, 0xB1, 0x71, 0x70, 0xB0,
    0x50, 0x90, 0x91, 0x51, 0x93, 0x53, 0x52, 0x92, 0x96, 0x56, 0x57, 0x97, 0x55, 0x95, 0x94, 0x54,
    0x9C, 0x5C, 0x5D, 0x9D, 0x5F, 0x9F, 0x9E, 0x5E, 0x5A, 0x9A, 0x9B, 0x5B, 0x99, 0x59, 0x58, 0x98,
    0x88, 0x48, 0x49, 0x89, 0x4B, 0x8B, 0x8A, 0x4A, 0x4E, 0x8E, 0x8F, 0x4F, 0x8D, 0x4D, 0x4C, 0x8C,
    0x44, 0x84, 0x85, 0x45, 0x87, 0x47, 0x46, 0x86, 0x82, 0x42, 0x43, 0x83, 0x41, 0x81, 0x80, 0x40
];