/**
 * This file is part of the Kompics component model runtime.
 *
 * Copyright (C) 2009 Swedish Institute of Computer Science (SICS) Copyright (C)
 * 2009 Royal Institute of Technology (KTH)
 *
 * Kompics is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package se.sics.gvod.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.udt.UdtChannel;
import io.netty.channel.udt.nio.NioUdtProvider;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.sics.gvod.address.Address;
import se.sics.gvod.common.msgs.DirectMsgNettyFactory;
import se.sics.gvod.common.msgs.Encodable;
import se.sics.gvod.config.VodConfig;
import se.sics.gvod.net.events.BandwidthStats;
import se.sics.gvod.net.events.NetworkException;
import se.sics.gvod.net.events.NetworkSessionClosed;
import se.sics.gvod.net.events.PortAllocRequest;
import se.sics.gvod.net.events.PortAllocResponse;
import se.sics.gvod.net.events.PortBindRequest;
import se.sics.gvod.net.events.PortBindResponse;
import se.sics.gvod.net.events.PortDeleteRequest;
import se.sics.gvod.net.events.PortDeleteResponse;
import se.sics.gvod.net.msgs.RewriteableMsg;
import se.sics.gvod.timer.SchedulePeriodicTimeout;
import se.sics.gvod.timer.Timeout;
import se.sics.gvod.timer.Timer;
import se.sics.gvod.util.UtilThreadFactory;
import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.Fault;
import se.sics.kompics.Handler;
import se.sics.kompics.Negative;
import se.sics.kompics.Positive;
import se.sics.kompics.Stop;

/**
 * The
 * <code>NettyNetwork</code> class.
 *
 * Usage: 1. Create NettyNetwork 2. Send Init event to this component 3. Send
 * PortBind or PortAlloc Request to this component for different transports
 *
 * @author Jim Dowling <jdowling@sics.se>
 */
/*
 * 
 * 
 * Ports in init
 * DirectMsgNettyFactory bug
 * upnp code
 * Connect API / asynchronous?
 * How to handle bind exceptions
 */
public final class NettyNetwork extends ComponentDefinition {

    // 1868836467 is the data packet size
    // public static final int MAX_OBJECT_SIZE = 1868836468;
    public static final int MAX_OBJECT_SIZE = 1048576; // default
    private static final int CHANNEL_POOLSIZE = 200;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int RECV_BUFFER_SIZE = 65536;
    private static final int SEND_BUFFER_SIZE = 65536;
    private static final Logger logger = LoggerFactory.getLogger(NettyNetwork.class);
    /**
     * The Network port.
     */
    Negative<VodNetwork> net = negative(VodNetwork.class);
    /**
     * The NetworkControl port.
     */
    Negative<NatNetworkControl> netControl = negative(NatNetworkControl.class);
    Positive<Timer> timer = positive(Timer.class);
    private int maxPacketSize;
    private Class<? extends MsgFrameDecoder> msgDecoderClass;
    /**
     * Locally bound socket
     */
    private Random rand;
    private NettyNetwork component;
    private Map<Integer, InetSocketAddress> udpPortsToSockets = new HashMap<Integer, InetSocketAddress>();
    private Map<InetSocketAddress, Bootstrap> udpSocketsToBootstraps = new HashMap<InetSocketAddress, Bootstrap>();
    private Map<InetSocketAddress, DatagramChannel> udpSocketsToChannels = new HashMap<InetSocketAddress, DatagramChannel>();
    private Map<Integer, InetSocketAddress> tcpPortsToSockets = new HashMap<Integer, InetSocketAddress>();
    private Map<InetSocketAddress, ServerBootstrap> tcpSocketsToServerBootstraps = new HashMap<InetSocketAddress, ServerBootstrap>();
    private Map<InetSocketAddress, Bootstrap> tcpSocketsToBootstraps = new HashMap<InetSocketAddress, Bootstrap>();
    private Map<InetSocketAddress, SocketChannel> tcpSocketsToChannels = new HashMap<InetSocketAddress, SocketChannel>();
    private Map<Integer, InetSocketAddress> udtPortsToSockets = new HashMap<Integer, InetSocketAddress>();
    private Map<InetSocketAddress, ServerBootstrap> udtSocketsToServerBootstraps = new HashMap<InetSocketAddress, ServerBootstrap>();
    private Map<InetSocketAddress, Bootstrap> udtSocketsToBootstraps = new HashMap<InetSocketAddress, Bootstrap>();
    private Map<InetSocketAddress, UdtChannel> udtSocketsToChannels = new HashMap<InetSocketAddress, UdtChannel>();
    // Bandwidth Measurement statistics
    private boolean enableBandwidthStats;
    private long prevTotalWrote;
    private long prevTotalRead;
    private static long totalWrittenBytes, totalReadBytes;
    private static AtomicLong lastSecRead = new AtomicLong();
    private static AtomicLong lastSecWrote = new AtomicLong();
    // 60 samples stored
    private static LinkedList<Integer> lastMinWrote = new LinkedList<Integer>();
    private static LinkedList<Integer> lastHourWrote = new LinkedList<Integer>();
    // 60 mins stored
    private static LinkedList<Integer> lastMinRead = new LinkedList<Integer>();
    // 12 hours stored
    private static LinkedList<Integer> lastHourRead = new LinkedList<Integer>();
    private int bwSampleCounter = 0;

    private boolean bindAllNetworkIfs;
    
    private class ByteCounterTimeout extends Timeout {

        public ByteCounterTimeout(SchedulePeriodicTimeout spt) {
            super(spt);
        }
    }

    /**
     * Instantiates a new Netty network component.
     */
    public NettyNetwork() {
        this.component = this;
        // IPv4 addresses over IPv6 addresses when querying DNS
        // For java, Linux is still a dual IPv6/v4 address stack, while windows
        // uses a single address.
        System.setProperty("java.net.preferIPv4Stack", "true");

        subscribe(handleRewriteableMessage, net);
        subscribe(handlePortBindRequest, netControl);
        subscribe(handlePortAllocRequest, netControl);
        subscribe(handlePortDeleteRequest, netControl);
        subscribe(handleByteCounterTimeout, timer);
        subscribe(handleInit, control);
        subscribe(handleStop, control);
    }
    /**
     * The handle init.
     */
    Handler<NettyInit> handleInit = new Handler<NettyInit>() {
        @Override
        public void handle(NettyInit init) {
            rand = new Random(init.getSeed());
            maxPacketSize = init.getMTU();
            bindAllNetworkIfs = init.isBindAllNetworkIfs();
            
            if (maxPacketSize <= 0) {
                throw new IllegalArgumentException(
                        "Netty problem: max Packet Size must be set to greater than zero.");
            }

            msgDecoderClass = init.getMsgDecoderClass();
            DirectMsgNettyFactory.setMsgFrameDecoder(msgDecoderClass);

            enableBandwidthStats = init.isEnableBandwidthStats();


            if (enableBandwidthStats) {
                SchedulePeriodicTimeout spt = new SchedulePeriodicTimeout(0, 1000);
                ByteCounterTimeout bct = new ByteCounterTimeout(spt);
                trigger(spt, timer);
            }
        }
    };
    Handler<ByteCounterTimeout> handleByteCounterTimeout = new Handler<ByteCounterTimeout>() {
        @Override
        public void handle(ByteCounterTimeout event) {

            lastSecWrote.set(totalWrittenBytes - prevTotalWrote);
            lastSecRead.set(totalReadBytes - prevTotalRead);
            prevTotalWrote = totalWrittenBytes;
            prevTotalRead = totalReadBytes;

            if (lastSecWrote.longValue() > VodConfig.getMaxUploadBwCapacity()) {
                VodConfig.setMaxUploadBwCapacity(lastSecWrote.longValue());
            }

            if (lastMinRead.size() == 60) {
                lastMinRead.removeLast();
            }

            lastMinRead.addFirst((int) lastSecRead.longValue());
            if (lastMinWrote.size() == 60) {
                lastMinWrote.removeLast();
            }

            lastMinWrote.addFirst((int) lastSecWrote.longValue());
            if (bwSampleCounter == 60) {

                if (lastHourRead.size() == 24) {
                    lastHourRead.removeLast();
                }

                lastHourRead.addFirst(getLast(lastMinRead));
                if (lastHourWrote.size() == 24) {
                    lastHourWrote.removeLast();
                }

                lastHourWrote.addFirst(getLast(lastMinWrote));
                bwSampleCounter = 0;
            } else {
                bwSampleCounter++;
            }

            trigger(new BandwidthStats((int) lastSecRead.longValue(),
                    (int) lastSecWrote.longValue()), netControl);
        }
    };

    private int getLast(LinkedList<Integer> last) {
        int count = 0;
        for (int i = 0; i < last.size(); i++) {
            count += last.get(i);
        }
        return count;
    }
    Handler<Stop> handleStop = new Handler<Stop>() {
        @Override
        public void handle(Stop event) {
            for (Bootstrap b : udpSocketsToBootstraps.values()) {
                if (b != null && b.group() != null) {
                    b.group().shutdownGracefully();
                }
            }

            udpPortsToSockets.clear();
            udpSocketsToBootstraps.clear();
            udpSocketsToChannels.clear();

            for (ServerBootstrap b : tcpSocketsToServerBootstraps.values()) {
                if (b != null && b.group() != null) {
                    b.group().shutdownGracefully();
                }

                if (b != null && b.childGroup() != null) {
                    b.childGroup().shutdownGracefully();
                }
            }
            for (Bootstrap b : tcpSocketsToBootstraps.values()) {
                if (b != null & b.group() != null) {
                    b.group().shutdownGracefully();
                }
            }

            tcpPortsToSockets.clear();
            tcpSocketsToBootstraps.clear();
            tcpSocketsToChannels.clear();
            tcpSocketsToServerBootstraps.clear();

            for (ServerBootstrap b : udtSocketsToServerBootstraps.values()) {
                if (b != null && b.group() != null) {
                    b.group().shutdownGracefully();
                }

                if (b != null && b.childGroup() != null) {
                    b.childGroup().shutdownGracefully();
                }
            }
            for (Bootstrap b : udtSocketsToBootstraps.values()) {
                if (b != null & b.group() != null) {
                    b.group().shutdownGracefully();
                }
            }

            udtPortsToSockets.clear();
            udtSocketsToBootstraps.clear();
            udtSocketsToChannels.clear();
            udtSocketsToServerBootstraps.clear();
        }
    };
    /**
     * The handle msg.
     */
    Handler<RewriteableMsg> handleRewriteableMessage = new Handler<RewriteableMsg>() {
        @Override
        public void handle(RewriteableMsg msg) {

            if (msg.getDestination().getIp().equals(msg.getSource().getIp())
                    && msg.getDestination().getPort() == msg.getSource().getPort()) {
                // deliver locally
                trigger(msg, net);
                return;
            }

            if (!(msg instanceof Encodable)) {
                throw new Error("Netty can only serialize instances of Encodable. You need to"
                        + "make this class implement Encodable: " + msg.getClass());
            }

            Transport protocol = msg.getProtocol();
            if (protocol == Transport.UDP) {
                sendUdp(msg);
            } else if (protocol == Transport.TCP) {
                sendTcp(msg);
            } else if (protocol == Transport.UDT) {
                sendUdt(msg);
            } else {
                throw new Error("Unknown Transport type");
            }
        }
    };
    Handler<PortBindRequest> handlePortBindRequest = new Handler<PortBindRequest>() {
        @Override
        public void handle(PortBindRequest msg) {

            logger.debug("Received bind request for port : " + msg.getPort());
            PortBindResponse response = msg.getResponse();

            try {
                if (bindPort(msg.getIp(), msg.getPort(), msg.getTransport())) {
                    response.setStatus(PortBindResponse.Status.SUCCESS);
                } else {
                    response.setStatus(PortBindResponse.Status.FAIL);
                }
                // TODO this is never thrown
            } catch (ChannelException e) {
                response.setStatus(PortBindResponse.Status.PORT_ALREADY_BOUND);
            }
            trigger(response, netControl);
        }
    };
    /**
     * Used by HolePunchingClient to allocate a bunch of ports, where we don't
     * care what the ports actually are, just that they are free ports. It tries
     * to find ports in the 50,000+ range, as they are typically not used by as
     * many applications as other ports.
     *
     */
    Handler<PortAllocRequest> handlePortAllocRequest = new Handler<PortAllocRequest>() {
        @Override
        public void handle(PortAllocRequest msg) {
            int numPorts = msg.getNumPorts();

            logger.debug("Request to allocate " + numPorts + " ports for hole-punching.");

            Set<Integer> setPorts = udpPortsToSockets.keySet();
            Set<Integer> addedPorts = new HashSet<Integer>();

            for (int i = 0; i < numPorts; i++) {
                int randPort = -1;
                do {
                    // Allocate a port in the 50,000+ range.
                    randPort = 50000 + rand.nextInt(65535 - 50000);
                } while (setPorts.contains(randPort));
                if (bindPort(msg.getIp(), randPort, msg.getTransport()) == true) {
                    addedPorts.add(randPort);
                }
            }

            PortAllocResponse response = msg.getResponse();
            if (response == null) {
                throw new IllegalStateException("PortAllocResponse event was not set before "
                        + "sending PortAllocRequest to Netty.");
            }
            response.setAllocatedPorts(addedPorts);
            trigger(response, netControl);
        }
    };
    // TODO check and do proper for all protocols
    Handler<PortDeleteRequest> handlePortDeleteRequest = new Handler<PortDeleteRequest>() {
        @Override
        public void handle(PortDeleteRequest msg) {
            Map<Integer, InetSocketAddress> portsToSockets;

            Transport protocol = msg.getTransport();
            if (protocol == Transport.UDP) {
                portsToSockets = tcpPortsToSockets;
            } else if (protocol == Transport.TCP) {
                portsToSockets = udpPortsToSockets;
            } else if (protocol == Transport.UDT) {
                portsToSockets = udtPortsToSockets;
            } else {
                throw new Error("Unknown Transport type");
            }

            Set<Integer> p = msg.getPortsToDelete();
            Set<Integer> setPorts = portsToSockets.keySet();
            Set<InetSocketAddress> socketsToRemove = new HashSet<InetSocketAddress>();
            Set<Integer> portsDeleted = new HashSet<Integer>();

            for (int i : p) {
                if (setPorts.contains(i)) {
                    socketsToRemove.add(portsToSockets.get(i));
                    portsDeleted.add(i);
                }
            }

            for (InetSocketAddress toRemove : socketsToRemove) {
                removeLocalSocket(toRemove, msg.getTransport());
            }

            if (msg.getResponse() != null) {
                // if a response is requested, send it
                PortDeleteResponse response = msg.getResponse();
                response.setPorts(portsDeleted);
                trigger(response, netControl);
            }
        }
    };

    private boolean bindPort(InetAddress addr, int port, Transport protocol) {
        switch (protocol) {
            case TCP:
                return bindTcpPort(addr, port, bindAllNetworkIfs);
            case UDP:
                return bindUdpPort(addr, port, bindAllNetworkIfs);
            case UDT:
                return bindUdtPort(addr, port, bindAllNetworkIfs);
            default:
                throw new Error("Unknown Transport type");
        }
    }

    private boolean bindUdpPort(final InetAddress addr, final int port, final boolean bindAllNetworkIfs) {

        if (udpPortsToSockets.containsKey(port)) {
            return true;
        } else if (udtPortsToSockets.containsKey(port)) {
            return false;
        }

        EventLoopGroup group = new NioEventLoopGroup();
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group).channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline().addLast(
                        new NettyUdpHandler(component, addr, port, msgDecoderClass));
            }
        });

        // Allow packets as large as up to 1600 bytes (default is 768).
        // You could increase or decrease this value to avoid truncated packets
        // or to improve memory footprint respectively.
        //
        // Please also note that a large UDP packet might be truncated or
        // dropped by your router no matter how you configured this option.
        // In UDP, a packet is truncated or dropped if it is larger than a
        // certain size, depending on router configuration. IPv4 routers
        // truncate and IPv6 routers drop a large packet. That's why it is
        // safe to send small packets in UDP.
        bootstrap.option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(1500));
        bootstrap.option(ChannelOption.SO_RCVBUF, RECV_BUFFER_SIZE);
        bootstrap.option(ChannelOption.SO_SNDBUF, SEND_BUFFER_SIZE);
        // bootstrap.setOption("trafficClass", trafficClass);
        // bootstrap.setOption("soTimeout", soTimeout);
        // bootstrap.setOption("broadcast", broadcast);
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS);
        bootstrap.option(ChannelOption.SO_REUSEADDR, true);

        try {
            // TODO - bind on all network interfaces ??
            DatagramChannel c;
            if (bindAllNetworkIfs) {
                c = (DatagramChannel) bootstrap.bind(new InetSocketAddress(port)).sync().channel();
            } else {
                c = (DatagramChannel) bootstrap.bind(new InetSocketAddress(addr, port)).sync()
                        .channel();
            }

            addLocalUdpSocket(c, new InetSocketAddress(addr, port));

            logger.info("Successfully bound to ip:port {}:{}", addr, port);
            // TODO how to handle bind expections
        } catch (InterruptedException e) {
            logger.warn("Problem when trying to bind to {}:{}", addr.getHostAddress(), port);
            trigger(new Fault(e.getCause()), control);
            return false;
        }

        udpSocketsToBootstraps.put(new InetSocketAddress(addr, port), bootstrap);

        return true;
    }

    private boolean bindTcpPort(InetAddress addr, int port, boolean bindAllNetworkIfs) {

        if (tcpPortsToSockets.containsKey(port)) {
            return true;
        }

        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        NettyTcpServerHandler handler = new NettyTcpServerHandler(component, addr, port);
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
                .childHandler((new NettyTcpInitializer(handler, msgDecoderClass)))
                .option(ChannelOption.SO_REUSEADDR, true);

        try {
            // TODO - bind on all network interfaces ??
            if (bindAllNetworkIfs) {
                bootstrap.bind(new InetSocketAddress(port)).sync();
            } else {
                bootstrap.bind(new InetSocketAddress(addr, port)).sync();
            }

            logger.info("Successfully bound to ip:port {}:{}", addr, port);
        } catch (InterruptedException e) {
            logger.warn("Problem when trying to bind to {}:{}", addr.getHostAddress(), port);
            trigger(new Fault(e.getCause()), control);
            return false;
        }

        InetSocketAddress iAddr = new InetSocketAddress(addr, port);
        tcpPortsToSockets.put(port, iAddr);
        tcpSocketsToServerBootstraps.put(iAddr, bootstrap);

        return true;
    }

    private boolean bindUdtPort(InetAddress addr, int port, boolean bindAllNetworkIfs) {

        if (udtPortsToSockets.containsKey(port)) {
            return true;
        } else if (udpPortsToSockets.containsKey(port)) {
            return false;
        }

        ThreadFactory bossFactory = new UtilThreadFactory("boss");
        ThreadFactory workerFactory = new UtilThreadFactory("worker");
        NioEventLoopGroup bossGroup = new NioEventLoopGroup(1, bossFactory,
                NioUdtProvider.BYTE_PROVIDER);
        NioEventLoopGroup workerGroup = new NioEventLoopGroup(1, workerFactory,
                NioUdtProvider.BYTE_PROVIDER);
        NettyUdtServerHandler handler = new NettyUdtServerHandler(component, addr, port);
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup).channelFactory(NioUdtProvider.BYTE_ACCEPTOR)
                .childHandler(new NettyUdtInitializer(handler, msgDecoderClass))
                .option(ChannelOption.SO_REUSEADDR, true);

        try {
            // TODO - bind on all network interfaces ??
            if (bindAllNetworkIfs) {
                bootstrap.bind(new InetSocketAddress(port)).sync();
            } else {
                bootstrap.bind(new InetSocketAddress(addr, port)).sync();
            }

            logger.info("Successfully bound to ip:port {}:{}", addr, port);
            // TODO how to handle bind exceptions
        } catch (InterruptedException e) {
            logger.warn("Problem when trying to bind to {}:{}", addr.getHostAddress(), port);
            trigger(new Fault(e.getCause()), control);
            return false;
        }

        InetSocketAddress iAddr = new InetSocketAddress(addr, port);
        udtPortsToSockets.put(port, iAddr);
        udtSocketsToServerBootstraps.put(iAddr, bootstrap);

        return true;
    }

    private boolean connectTcp(InetAddress addr, int port) {
        InetSocketAddress iAddr = new InetSocketAddress(addr, port);

        if (tcpSocketsToBootstraps.containsKey(iAddr)) {
            return true;
        }

        EventLoopGroup group = new NioEventLoopGroup();
        NettyTcpHandler handler = new NettyTcpHandler(component, addr, port);
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group).channel(NioSocketChannel.class)
                .handler(new NettyTcpInitializer(handler, msgDecoderClass))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .option(ChannelOption.SO_REUSEADDR, true);

        try {
            // TODO asynchronous?
            SocketChannel c = (SocketChannel) bootstrap.connect(iAddr).sync().channel();
            addLocalTcpSocket(c, iAddr);

            logger.info("Successfully connected to ip:port {}:{}", addr, port);
        } catch (InterruptedException e) {
            logger.warn("Problem when trying to connect to {}:{}", addr.getHostAddress(), port);
            trigger(new Fault(e.getCause()), control);
            return false;
        }

        tcpSocketsToBootstraps.put(iAddr, bootstrap);

        return true;
    }

    private boolean connectUdt(InetAddress addr, int port) {
        InetSocketAddress iAddr = new InetSocketAddress(addr, port);

        if (udtSocketsToBootstraps.containsKey(iAddr)) {
            return true;
        }

        ThreadFactory workerFactory = new UtilThreadFactory("clientWorker");
        NioEventLoopGroup workerGroup = new NioEventLoopGroup(1, workerFactory,
                NioUdtProvider.BYTE_PROVIDER);
        NettyUdtHandler handler = new NettyUdtHandler(component, addr, port);
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup).channelFactory(NioUdtProvider.BYTE_CONNECTOR)
                .handler(new NettyUdtInitializer(handler, msgDecoderClass));
        // .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
        // .option(ChannelOption.SO_REUSEADDR, true);

        try {
            // TODO asynchronous?
            UdtChannel c = (UdtChannel) bootstrap.connect(iAddr).sync().channel();
            addLocalUdtSocket(c, iAddr);

            logger.info("Successfully connected to ip:port {}:{}", addr, port);
        } catch (InterruptedException e) {
            logger.warn("Problem when trying to connect to {}:{}", addr.getHostAddress(), port);
            trigger(new Fault(e.getCause()), control);
            return false;
        }

        udtSocketsToBootstraps.put(iAddr, bootstrap);

        return true;
    }

    private void addLocalUdpSocket(DatagramChannel channel, InetSocketAddress addr) {
        udpPortsToSockets.put(addr.getPort(), addr);
        udpSocketsToChannels.put(addr, channel);
    }

    void addLocalTcpSocket(SocketChannel channel, InetSocketAddress addr) {
        tcpSocketsToChannels.put(addr, channel);
    }

    void addLocalUdtSocket(UdtChannel channel, InetSocketAddress addr) {
        udtSocketsToChannels.put(addr, channel);
    }

    private void removeLocalSocket(InetSocketAddress addr, Transport protocol) {
        switch (protocol) {
            case TCP:
                // TODO do something smart
                break;
            case UDP:
                // TODO Does that work?
                udpPortsToSockets.remove(addr.getPort());
                udpSocketsToChannels.remove(addr);
                Bootstrap b = udpSocketsToBootstraps.remove(addr);
                if (b != null) {
                    EventLoopGroup group = b.group();
                    if (group != null) {
                        group.shutdownGracefully();
                    }
                }
                break;
            case UDT:
                // TODO do something smart
                break;
            default:
                throw new Error("Unknown Transport type");
        }
    }

    private InetSocketAddress address2SocketAddress(Address address) {
        return new InetSocketAddress(address.getIp(), address.getPort());
    }

    private void sendUdp(RewriteableMsg msg) {
        InetSocketAddress src = address2SocketAddress(msg.getSource());
        InetSocketAddress dest = address2SocketAddress(msg.getDestination());

        if (src == null) {
            String strError = "Source for msg " + "of type " + msg.getClass()
                    + " is not bound at network component: " + msg.getSource();
            logger.error(strError);
            trigger(new Fault(new IllegalArgumentException(strError)), control);
            return;
        }

        // use one channel per local socket
        // session-less UDP. This means that remoteAddresses cannot be found in
        // the channel object, but only in the MessageEvent object.
        DatagramChannel channel = udpSocketsToChannels.get(src);
        if (channel == null) {
            throw new IllegalStateException(
                    "Port not bound at client, cannot send message. "
                    + "Need to first allocate port: " + src.getPort());
        }
        try {
            logger.trace("Sending " + msg.getClass().getCanonicalName() + " from {} to {} ",
                    msg.getSource().getId(), msg.getDestination().getId());
            channel.write(new DatagramPacket(((Encodable) msg).toByteArray(), dest));
            totalWrittenBytes += msg.getSize();
        } catch (Exception ex) {
            logger.warn("Problem trying to write msg of type: "
                    + msg.getClass().getCanonicalName() + " with src address: "
                    + src.toString());
            trigger(new Fault(ex), control);
        }
    }

    private void sendTcp(RewriteableMsg msg) {
        InetSocketAddress dst = address2SocketAddress(msg.getDestination());
        SocketChannel channel = tcpSocketsToChannels.get(dst);

        if (channel == null) {
            if (connectTcp(msg.getDestination().getIp(), msg.getDestination().getPort()) == false) {
                logger.warn("Channel was null when trying to write msg of type: "
                        + msg.getClass().getCanonicalName() + " with dst address: "
                        + dst.toString());
                trigger(new Fault(new IllegalStateException(
                        "Could not send messge because connection could not be established to "
                        + dst.toString())), control);
                return;
            }
            channel = tcpSocketsToChannels.get(dst);
        }

        try {
            logger.trace("Sending " + msg.getClass().getCanonicalName() + " from {} to {} ",
                    msg.getSource().getId(), msg.getDestination().getId());
            // TODO should not overwrite the given address and port!
            msg.getSource().setIp(channel.localAddress().getAddress());
            msg.getSource().setPort(channel.localAddress().getPort());
            channel.write(msg).sync();
            totalWrittenBytes += msg.getSize();
        } catch (Exception ex) {
            logger.warn("Problem trying to write msg of type: "
                    + msg.getClass().getCanonicalName() + " with dst address: "
                    + dst.toString());
            trigger(new Fault(ex), control);
        }
    }

    private void sendUdt(RewriteableMsg msg) {
        InetSocketAddress dst = address2SocketAddress(msg.getDestination());
        UdtChannel channel = udtSocketsToChannels.get(dst);

        if (channel == null) {
            if (connectUdt(msg.getDestination().getIp(), msg.getDestination().getPort()) == false) {
                logger.warn("Channel was null when trying to write msg of type: "
                        + msg.getClass().getCanonicalName() + " with dst address: "
                        + dst.toString());
                trigger(new Fault(new IllegalStateException(
                        "Could not send messge because connection could not be established to "
                        + dst.toString())), control);
                return;
            }
            channel = udtSocketsToChannels.get(dst);
        }

        try {
            logger.trace("Sending " + msg.getClass().getCanonicalName() + " from {} to {} ",
                    msg.getSource().getId(), msg.getDestination().getId());
            // TODO should not overwrite the given address and port!
            msg.getSource().setIp(channel.localAddress().getAddress());
            msg.getSource().setPort(channel.localAddress().getPort());
            channel.write(msg).sync();
            totalWrittenBytes += msg.getSize();
        } catch (Exception ex) {
            logger.warn("Problem trying to write msg of type: "
                    + msg.getClass().getCanonicalName() + " with dst address: "
                    + dst.toString());
            trigger(new Fault(ex), control);
        }
    }

    final void deliverMessage(RewriteableMsg msg) {
        logger.trace("Receiving " + msg.getClass().getCanonicalName() + " source {} dest {} ",
                msg.getSource().getId(), msg.getDestination().getId());
        trigger(msg, net);
        totalReadBytes += msg.getSize();
    }

    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
        logger.warn("Fault for " + e.getCause().getMessage());
        trigger(new Fault(e.getCause()), control);
    }

    /**
     * Network exception.
     *
     * @param event the event
     */
    final void networkException(NetworkException event) {
        logger.trace("NetworkException for " + event.getRemoteAddress().toString());
        trigger(event, netControl);
    }

    final void channelInactive(ChannelHandlerContext ctx, Transport protocol) {
        Channel c = ctx.channel();
        SocketAddress addr;

        switch (protocol) {
            case UDP:
                addr = c.localAddress();
                break;
            case UDT:
            case TCP:
                addr = c.remoteAddress();
                break;
            default:
                throw new Error("Unknown Transport type");
        }

        InetSocketAddress clientSocketAddress = null;
        if (addr instanceof InetSocketAddress) {
            clientSocketAddress = (InetSocketAddress) addr;
            removeLocalSocket(clientSocketAddress, protocol);
            logger.trace("Channel closed");
        }

        trigger(new NetworkSessionClosed(clientSocketAddress, protocol), netControl);
    }

    public static long getNumBytesReadLastSec() {
        return lastSecRead.longValue();
    }

    public static long getNumBytesReadLastMin() {

        return lastSecRead.longValue();
    }

    public static long getNumBytesWroteLastSec() {
        return lastSecWrote.longValue();
    }
}
