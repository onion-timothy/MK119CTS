package com.onionsoftware.mk119.commtestsuite.db.bean;

import com.onionsoftware.mk119.commtestsuite.enums.ErrorMode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component
@Getter
@Setter
public class Error {
    private int device_id;
    private int intMode;
    private ErrorMode mode;
    private int frequency;
    private int enabled;
    private int timeout;
    private int garbageLength;

    public static final int ERROR_DISABLED = 0;
    public static final int ERROR_ENABLED = 1;
    static Random random = new Random();

    public void setIntMode(int intMode) {
        this.intMode = intMode;
        this.mode = ErrorMode.values()[intMode];
    }

    /**
     * 에러의 빈도 설정과 발생 모드를 참조하여 에러 동작을 수행한다.
     * Random으로 빈도 미만의 숫자일 때 동작한다.
     * Mode에 따라 아래의 정의대로 리턴한다.
     *
     * @return 동일 인스턴스를 반환할 경우 에러 없음, null일 경우 Discard, 길이가 0일 경우 Timeout, 길이가 있을 경우 Garbage
     */
    public ErrorResult performErrorSimulation(ByteBuf out) {
        ErrorResult errorResult = new ErrorResult();
        if (random.nextInt(100) < this.getFrequency() && this.getEnabled() == Error.ERROR_ENABLED) {
            if (mode == ErrorMode.DISCARD) {
                errorResult.setResultType(ErrorResult.RESULT_TYPE_DISCARD);
                return errorResult;
            } else if (mode == ErrorMode.TIMEOUT) {
                try {
                    Thread.sleep(getTimeout() * 1000L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                errorResult.setResultType(ErrorResult.RESULT_TYPE_TIMEOUT);
                errorResult.setBufToWrite(out);
                return errorResult;
            } else if (mode == ErrorMode.GARBAGE) {
                ByteBuf ret = Unpooled.buffer();
                for (int i = 0; i < getGarbageLength(); i++) {
                    ret.writeByte(random.nextInt(255));
                }
                errorResult.setResultType(ErrorResult.RESULT_TYPE_GARBAGE);
                errorResult.setBufToWrite(ret);
                return errorResult;
            } else {
                errorResult.setResultType(ErrorResult.RESULT_TYPE_DISCONNECT);
                return errorResult;
            }
        }
        return null;
    }
}
