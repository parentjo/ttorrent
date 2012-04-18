package com.turn.ttorrent.client;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Created by IntelliJ IDEA.
 * User: Johan Parent
 * Date: 20/02/12
 * Time: 22:00
 * To change this template use File | Settings | File Templates.
 */
public class AnnounceResponsePeerFactory {
    public static AnnounceReponsePeer create(byte[] peerId, String ip, int port) {
        return new AnnounceResponsePeerUdp(peerId, ip, port);
    }

    public static class AnnounceResponsePeerUdp implements AnnounceReponsePeer {
        final byte[] peerId;
        final String ip;
        final int port;

        public AnnounceResponsePeerUdp(byte[] peerId, String ip, int port) {
            this.peerId = peerId;
            this.ip = ip;
            this.port = port;
        }

        public byte[] getPeerId() {
            return peerId;
        }

        public String getIp() {
            return ip;
        }

        public int getPort() {
            return port;
        }

        @Override
        public String toString() {
            String s = null;
            try {
                s = InetAddress.getByName(ip.substring(1)).getHostName();
            } catch (UnknownHostException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }
            return "peerId=" + peerId +
                    ", ip='" + ip + '\'' +
                    ", port=" + port + " " + s;
        }
    }
}
