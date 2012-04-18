package com.turn.ttorrent.client.udp;

/**
 * Created by IntelliJ IDEA.
 * User: Johan Parent
 * Date: 19/02/12
 * Time: 11:08
 * To change this template use File | Settings | File Templates.
 */
public enum Actions {
    CONNECT(0), ANNOUNCE(1), SCRAPE(2), ERROR(3);
    public int action;

    Actions(int action) {
        this.action = action;
    }
}
