package com.turn.ttorrent.client;

import java.util.LinkedList;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: Johan Parent
 * Date: 20/02/12
 * Time: 21:57
 * To change this template use File | Settings | File Templates.
 */
public class AnnounceReponseFactory {
    public static AnnounceResponse create(int interval) {
        return new AnnounceReponseUdp(interval);
    }

    public static class AnnounceReponseUdp implements AnnounceResponse {
        final List<AnnounceReponsePeer> peers = new LinkedList<AnnounceReponsePeer>();
        final int interval;
        private String errorString = null;

        public AnnounceReponseUdp(int interval) {
            this.interval = interval;
        }

        public boolean hasPeers() {
            return peers.isEmpty();
        }

        public List<AnnounceReponsePeer> getPeers() {
            return peers;
        }

        public int getInterval() {
            return interval;
        }

        public boolean gotError() {
            return errorString != null;
        }

        public String getErrorString() {
            return errorString;
        }

        public void setErrorString(String errorString) {
            this.errorString = errorString;
        }
    }
}
