package com.turn.ttorrent.client;

import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: Johan Parent
 * Date: 20/02/12
 * Time: 21:46
 * To change this template use File | Settings | File Templates.
 */
public interface AnnounceResponse {
    boolean hasPeers();
    List<AnnounceReponsePeer> getPeers();
    int getInterval();

    boolean gotError();
    String getErrorString();
    void setErrorString(String errorString);
}
