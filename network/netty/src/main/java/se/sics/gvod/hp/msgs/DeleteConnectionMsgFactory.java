package se.sics.gvod.hp.msgs;

import io.netty.buffer.ByteBuf;
import se.sics.gvod.common.msgs.MessageDecodingException;

public class DeleteConnectionMsgFactory extends HpMsgFactory.Oneway {

    protected DeleteConnectionMsgFactory() {
    }

    public static DeleteConnectionMsg fromBuffer(ByteBuf buffer)
            throws MessageDecodingException {
        return (DeleteConnectionMsg) new DeleteConnectionMsgFactory().decode(buffer);
    }

    @Override
    protected HpMsg.Oneway process(ByteBuf buffer) throws MessageDecodingException {
        return new DeleteConnectionMsg(vodSrc, vodDest, remoteClientId, msgTimeoutId);
    }

}
