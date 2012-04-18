package com.turn.ttorrent.client;

/**
 * Created by IntelliJ IDEA.
 * User: Johan Parent
 * Date: 20/02/12
 * Time: 21:49
 * To change this template use File | Settings | File Templates.
 */
public interface AnnounceReponsePeer {
    byte[] getPeerId();
    String getIp();
    int getPort();
}
