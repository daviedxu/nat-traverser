package se.sics.gvod.stun.msgs;

import org.jboss.netty.buffer.ChannelBuffer;
import se.sics.gvod.common.msgs.MessageDecodingException;
import se.sics.gvod.common.msgs.DirectMsgNettyFactory;
import se.sics.gvod.net.msgs.DirectMsg;
import se.sics.gvod.net.util.UserTypesDecoderFactory;

public class ReportMsgFactory  {

    public static class Request extends DirectMsgNettyFactory {

        private Request() {
        }

        public static ReportMsg.Request fromBuffer(ChannelBuffer buffer)
                
                throws MessageDecodingException {
            return (ReportMsg.Request) new ReportMsgFactory.Request().decode(buffer, true);
        }

        @Override
        protected DirectMsg process(ChannelBuffer buffer) throws MessageDecodingException {
         
            String msg = UserTypesDecoderFactory.readStringLength256(buffer);
            ReportMsg.Request r = new ReportMsg.Request(vodSrc, vodDest, timeoutId, msg);
            return r;
        }
    }

    public static class Response extends DirectMsgNettyFactory {

        private Response() {
        }

        public static ReportMsg.Response fromBuffer(ChannelBuffer buffer)
                
                throws MessageDecodingException {
            return (ReportMsg.Response)
                    new ReportMsgFactory.Response().decode(buffer, true);
        }

        @Override
        protected DirectMsg process(ChannelBuffer buffer) throws MessageDecodingException {
            return new ReportMsg.Response(vodSrc, vodDest, timeoutId);
        }


    }
};
