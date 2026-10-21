package com.onionsoftware.mk119.commtestsuite.debug;


import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SIMULATION 에러 검증용 디버그 폴러.
 * modbus-tcp FC=3 register=0 을 1초마다 읽어서 원본값/에러값 발생 비율을 로그로 기록한다.
 * application.properties 에서 debug.modbus-poll.enabled=true 세팅 시 활성화.
 */
@Component
public class ModbusPollDebugger {
    @Value("${debug.modbus-poll.enabled:false}")
    private boolean enabled;
    @Value("${debug.modbus-poll.host:localhost}")
    private String host;
    @Value("${debug.modbus-poll.port:502}")
    private int port;
    @Value("${debug.modbus-poll.unitId:1}")
    private int unitId;
    @Value("${debug.modbus-poll.expected.original:30}")
    private int originalValue;
    @Value("${debug.modbus-poll.expected.error:100}")
    private int errorValue;

    private final AtomicLong totalSamples = new AtomicLong();
    private final AtomicLong originalCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicLong otherCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final AtomicInteger tid = new AtomicInteger();

    /** 1초마다 register 0 을 읽어 값을 카운트한다. */
    @Scheduled(fixedRate = 1000)
    public void poll() {
        if (!enabled) return;

        totalSamples.incrementAndGet();
        Integer value = readRegister();
        if (value == null) {
            failedCount.incrementAndGet();
        } else if (value == originalValue) {
            originalCount.incrementAndGet();
        } else if (value == errorValue) {
            errorCount.incrementAndGet();
        } else {
            otherCount.incrementAndGet();
        }
    }

    /** 60초마다 누적 비율을 로그로 남긴다. */
    @Scheduled(fixedRate = 60_000, initialDelay = 60_000)
    public void report() {
        if (!enabled) return;
        long total = totalSamples.get();
        if (total == 0) return;

        long orig = originalCount.get();
        long err = errorCount.get();
        long other = otherCount.get();
        long failed = failedCount.get();
        long ok = orig + err + other;

        if (ok == 0) {
            LoggerFactory.getLogger("debug").info("ModbusPoll: total={}, no successful reads (failed={})", total, failed);
            return;
        }

        double pOrig = 100.0 * orig / ok;
        double pErr = 100.0 * err / ok;
        double pOther = 100.0 * other / ok;

        String msg = String.format(
                "ModbusPoll ratio (ok=%d, failed=%d) -> V(=%d):%d(%.2f%%), E(=%d):%d(%.2f%%), other:%d(%.2f%%)",
                ok, failed,
                originalValue, orig, pOrig,
                errorValue, err, pErr,
                other, pOther);
        LoggerFactory.getLogger("debug").info(msg);
    }

    /**
     * Modbus TCP FC=3 로 register 0 을 1개 읽는다. 실패 시 null.
     * 의존성 없이 raw socket 으로 처리 (디버그 전용).
     */
    private Integer readRegister() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            socket.setSoTimeout(2000);

            int txId = tid.incrementAndGet() & 0xFFFF;
            byte[] request = new byte[] {
                    (byte) (txId >> 8), (byte) txId,   // Transaction ID
                    0, 0,                                // Protocol ID
                    0, 6,                                // Length (unit + fc + start + qty)
                    (byte) unitId,                       // Unit ID
                    3,                                   // FC = Read Holding Registers
                    0, 0,                                // Start address = 0
                    0, 1                                 // Quantity = 1 register
            };

            OutputStream out = socket.getOutputStream();
            out.write(request);
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            in.readShort();                              // TID (echoed)
            in.readShort();                              // Protocol ID
            in.readUnsignedShort();                      // Length
            in.readByte();                               // Unit ID
            int fc = in.readByte() & 0xFF;
            if (fc != 3) return null;                    // exception response or wrong FC
            int byteCount = in.readByte() & 0xFF;
            if (byteCount < 2) return null;
            return in.readUnsignedShort();
        } catch (Exception e) {
            return null;
        }
    }
}
