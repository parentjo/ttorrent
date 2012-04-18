package com.turn.ttorrent.client.udp;

/**
 * Created by IntelliJ IDEA.
 * User: Johan Parent
 * Date: 19/02/12
 * Time: 11:53
 * To change this template use File | Settings | File Templates.
 */
public enum Events {
    NONE(0), COMPLETED(1), STARTED(2), STOPPED(3);

    public int event;

    Events(int event) {
        this.event = event;
    }
}
