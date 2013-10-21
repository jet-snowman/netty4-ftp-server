package com.jetsnegovik.ftp.server;

import com.jetsnegovik.ftp.server.handlers.FtpHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

/**
 *
 * @author Вадим
 */
public class ServerInitializer extends ChannelInitializer<SocketChannel> {

    private static final StringDecoder DECODER = new StringDecoder();
    private static final StringEncoder ENCODER = new StringEncoder();

    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline p = ch.pipeline();
        p.addLast("framer", new DelimiterBasedFrameDecoder(256, Delimiters.lineDelimiter()));
        p.addLast("decoder", DECODER);
        p.addLast("encoder", ENCODER);
        p.addLast("handler", new FtpHandler());
    }
}
