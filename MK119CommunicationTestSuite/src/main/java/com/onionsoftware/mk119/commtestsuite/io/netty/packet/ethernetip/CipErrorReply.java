package com.onionsoftware.mk119.commtestsuite.io.netty.packet.ethernetip;

import etherip.protocol.MessageRouterProtocol;
import etherip.protocol.ProtocolAdapter;
import etherip.types.CNService;

import java.nio.ByteBuffer;

/**
 * 본문 없는 CIP 오류 응답(일반 상태 + 확장 상태).
 * <p>
 * etherip 의 {@link MessageRouterProtocol#getResponseSize} 는 길이를 "4 + 본문" 으로만 세고 확장 상태
 * 워드(2바이트씩)를 빼먹는다. 그런데 encodeResponse 는 그 워드를 실제로 쓰므로, 확장 상태가 붙은
 * 응답은 캡슐화 길이가 짧게 나가고 같은 연결의 다음 응답부터 어긋난다. 여기서 그 길이를 더해 준다.
 */
public class CipErrorReply extends MessageRouterProtocol {
    public CipErrorReply(CNService service, int status, int... extStatus) {
        super(service, null, new ProtocolAdapter());
        setStatus(status);
        setExt_status(extStatus);
    }

    @Override
    public int getResponseSize(ByteBuffer buf) throws Exception {
        return super.getResponseSize(buf) + 2 * getExt_status().length;
    }
}
