/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2011-2014 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2014 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.horizon.minion.icmp.jna;

import com.sun.jna.Platform;
import org.opennms.horizon.minion.jicmp.ip.ICMPEchoPacket;
import org.opennms.horizon.minion.jicmp.ip.ICMPPacket;
import org.opennms.horizon.minion.jicmp.ip.ICMPPacket.Type;
import org.opennms.horizon.minion.jicmp.ip.IPPacket;
import org.opennms.horizon.minion.jicmp.jna.NativeDatagramPacket;
import org.opennms.horizon.minion.jicmp.jna.NativeDatagramSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * PingListener
 *
 * @author brozow
 */
public class V4Pinger extends AbstractPinger<Inet4Address> {
    private static final Logger LOG = LoggerFactory.getLogger(V4Pinger.class);
    

    public V4Pinger(final int pingerId) throws Exception {
        super(pingerId, NativeDatagramSocket.create(NativeDatagramSocket.PF_INET, NativeDatagramSocket.IPPROTO_ICMP, pingerId));
        
        // Windows requires at least one packet sent before a receive call can be made without error
        // so we send a packet here to make sure...  This one should not match the normal ping requests
        // since it does not contain the cookie so it won't interface.
        if (Platform.isWindows()) {
            final ICMPEchoPacket packet = new ICMPEchoPacket(64);
            packet.setCode(0);
            packet.setType(Type.EchoRequest);
            packet.getContentBuffer().putLong(System.nanoTime());
            packet.getContentBuffer().putLong(System.nanoTime());
            getPingSocket().send(packet.toDatagramPacket(InetAddress.getLocalHost()));
        }
    }
    
    @Override
    public void run() {
        try {
            final int pingerId = getPingerId();
            while (!isFinished()) {
                final NativeDatagramPacket datagram = new NativeDatagramPacket(65535);
                getPingSocket().receive(datagram);
                final long received = System.nanoTime();
    
                final ICMPPacket icmpPacket = new ICMPPacket(getIPPayload(datagram));
                final V4PingReply echoReply = icmpPacket.getType() == Type.EchoReply ? new V4PingReply(icmpPacket, received) : null;
            
                if (echoReply != null && echoReply.getIdentifier() ==  pingerId && echoReply.isValid()) {
                    notifyPingListeners(datagram.getAddress(), echoReply);
                }
            }
        } catch(final Throwable e) {
            setThrowable(e);
            LOG.debug("Error caught while processing ping packets: {}", e.getMessage(), e);
        }
    }

    private ByteBuffer getIPPayload(final NativeDatagramPacket datagram) {
        return new IPPacket(datagram.getContent()).getPayload();
    }
    
    @Override
    public void ping(final Inet4Address addr, final int identifier, final int sequenceNumber, final long threadId, final long count, final long interval, final int packetSize) throws InterruptedException {
        final NativeDatagramSocket socket = getPingSocket();
        for(int i = sequenceNumber; i < sequenceNumber + count; i++) {
            final V4PingRequest request = new V4PingRequest(identifier, i, threadId,packetSize);
            request.send(socket, addr);
            Thread.sleep(interval);
        }
    }
}
