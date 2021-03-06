/** Copyright (C) 2011 Turn, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.turn.ttorrent.client;

import com.turn.ttorrent.bcodec.BDecoder;
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.InvalidBEncodingException;
import com.turn.ttorrent.common.Torrent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BitTorrent client tracker announce thread.
 * <p/>
 * <p>
 * A BitTorrent client must check-in to the torrent's tracker every now and
 * then, and when particular events happen.
 * </p>
 * <p/>
 * <p>
 * This Announce class implements a periodic announce request thread that will
 * notify announce request event listeners for each tracker response.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Tracker_Request_Parameters">BitTorrent tracker request specification</a>
 * @see com.turn.ttorrent.client.Announce.AnnounceEvent
 */
public class Announce implements Runnable, AnnounceResponseListener {

    private static final Logger logger =
            LoggerFactory.getLogger(Announce.class);

    /**
     * The torrent announced by this announce thread.
     */
    protected SharedTorrent torrent;

    /**
     * The peer ID we report to the tracker.
     */
    protected String id;

    /**
     * Our client address, to report our IP address and port to the tracker.
     */
    protected InetSocketAddress address;

    /**
     * The set of listeners to announce request answers.
     */
    protected Set<AnnounceResponseListener> listeners;

    /**
     * Announce thread and control.
     */
    protected Thread thread;
    protected boolean stop;
    protected boolean forceStop;

    /**
     * Announce interval, initial 'started' event control.
     */
    protected int interval;
    protected boolean initial;

    /**
     * Announce request event types.
     * <p/>
     * When the client starts exchanging on a torrent, it must contact the
     * torrent's tracker with a 'started' announce request, which notifies the
     * tracker this client now exchanges on this torrent (and thus allows the
     * tracker to report the existence of this peer to other clients).
     * <p/>
     * When the client stops exchanging, or when its download completes, it must
     * also send a specific announce request. Otherwise, the client must send an
     * eventless (NONE), periodic announce request to the tracker at an
     * interval specified by the tracker itself, allowing the tracker to
     * refresh this peer's status and acknowledge that it is still there.
     */
    public enum AnnounceEvent {
        NONE,
        STARTED,
        STOPPED,
        COMPLETED;
    }

    ;

    /**
     * Create a new announcer for the given torrent.
     *
     * @param torrent The torrent we're announing about.
     * @param id      Our client peer ID.
     * @param address Our client network address, used to extract our external
     *                IP and listening port.
     */
    public Announce(SharedTorrent torrent, String id, InetSocketAddress address) {
        this.torrent = torrent;
        this.id = id;
        this.address = address;

        this.listeners = new HashSet<AnnounceResponseListener>();
        this.thread = null;
        this.register(this);
    }

    /**
     * Register a new announce response listener.
     *
     * @param listener The listener to register on this announcer events.
     */
    public void register(AnnounceResponseListener listener) {
        this.listeners.add(listener);
    }

    /**
     * Start the announce request thread.
     */
    public void start() {
        this.stop = false;
        this.forceStop = false;

        if (this.thread == null || !this.thread.isAlive()) {
            this.thread = new Thread(this);
            this.thread.setName("bt-announce");
            this.thread.start();
        }
    }

    /**
     * Stop the announce thread.
     * <p/>
     * One last 'stopped' announce event will be sent to the tracker to
     * announce we're going away.
     */
    public void stop() {
        this.stop = true;

        if (this.thread != null && this.thread.isAlive()) {
            this.thread.interrupt();
        }

        this.thread = null;
    }

    /**
     * Stop the announce thread.
     *
     * @param hard Whether to force stop the announce thread or not, i.e. not
     *             send the final 'stopped' announce request or not.
     */
    protected void stop(boolean hard) {
        this.forceStop = true;
        this.stop();
    }

    /**
     * Main announce loop.
     * <p/>
     * The announce thread starts by making the initial 'started' announce
     * request to register on the tracker and get the announce interval value.
     * Subsequent announce requests are ordinary, event-less, periodic requests
     * for peers.
     * <p/>
     * Unless forcefully stopped, the announce thread will terminate by sending
     * a 'stopped' announce request before stopping.
     */
    public void run() {
        logger.info("Starting announce thread for " +
                torrent.getName() + " to " +
                torrent.getAnnounceUrl() + "...");

        // Set an initial announce interval to 5 seconds. This will be updated
        // in real-time by the tracker's responses to our announce requests.
        this.interval = 5;
        this.initial = true;

        while (!this.stop) {
            this.announce(this.initial ?
                    AnnounceEvent.STARTED :
                    AnnounceEvent.NONE);

            try {
                logger.trace("Sending next announce in " + this.interval +
                        " seconds.");
                Thread.sleep(this.interval * 1000);
            } catch (InterruptedException ie) {
                // Ignore
            }
        }

        if (!this.forceStop) {
            // Send the final 'stopped' event to the tracker after a little
            // while.
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                // Ignore
            }

            this.announce(AnnounceEvent.STOPPED, true);
        }
    }

    /**
     * Build, send and process a tracker announce request.
     * <p/>
     * <p>
     * This function first builds an announce request for the specified event
     * with all the required parameters. Then, the request is made to the
     * tracker's announce URL and the response is read and B-decoded.
     * </p>
     * <p/>
     * <p>
     * All registered {@link AnnounceResponseListener} objects are then fired
     * with the decoded payload.
     * </p>
     *
     * @param event The announce event type (can be AnnounceEvent.NONE for
     *              periodic updates).
     * @return The decoded tracker response is also returned.
     */
    private AnnounceResponse announce(AnnounceEvent event) {
        return this.announce(event, false);
    }

    /**
     * Build, send and process a tracker announce request.
     * <p/>
     * <p>
     * Gives the ability to perform an announce request without notifying the
     * registered listeners.
     * </p>
     *
     *
     * @param event        The announce event type (can be AnnounceEvent.NONE for
     *                     periodic updates).
     * @param inhibitEvent Prevent event listeners from being notified.
     * @return The decoded tracker response is also returned.
     * @see #announce(AnnounceEvent event)
     */
    protected AnnounceResponse announce(AnnounceEvent event,
                                      boolean inhibitEvent) {
        Map<String, String> params = new HashMap<String, String>();

        try {
            params.put("info_hash",
                    new String(torrent.getInfoHash(), Torrent.BYTE_ENCODING));

            // Also throw in there the hex-encoded info-hash for easier
            // debugging of announce requests.
            params.put("info_hash_hex",
                    Torrent.toHexString(params.get("info_hash")));
        } catch (UnsupportedEncodingException uee) {
            logger.warn("{}", uee.getMessage());
        }

        params.put("peer_id", this.id);
        params.put("port", Integer.toString(this.address.getPort()));
        params.put("uploaded", Long.toString(this.torrent.getUploaded()));
        params.put("downloaded", Long.toString(this.torrent.getDownloaded()));
        params.put("left", Long.toString(this.torrent.getLeft()));

        if (!AnnounceEvent.NONE.equals(event)) {
            params.put("event", event.name().toLowerCase());
        }

        params.put("ip", this.address.getAddress().getHostAddress());
        params.put("compact", "1");

        AnnounceResponse result = null;
        try {
            logger.debug("Announcing " +
                    (!AnnounceEvent.NONE.equals(event) ?
                            event.name() + ' ' : "") + "to tracker with " +
                    this.torrent.getUploaded() + "U/" +
                    this.torrent.getDownloaded() + "D/" +
                    this.torrent.getLeft() + "L bytes for " +
                    this.torrent.getName() + "...");
            URL announce = this.buildAnnounceURL(params);
            URLConnection conn = announce.openConnection();
            conn.setConnectTimeout(2 * 60 * 1000);
            InputStream is = conn.getInputStream();
//            result = BDecoder.bdecode(is).getMap();
            result = BDecoder.convert(is);
            is.close();

            if (!inhibitEvent) {
                for (AnnounceResponseListener listener : this.listeners) {
                    listener.handleAnnounceResponse(result);
                }
            }
        } catch (UnsupportedEncodingException uee) {
            logger.error("{}", uee.getMessage(), uee);
            this.stop(true);
        } catch (MalformedURLException mue) {
            logger.error("{}", mue.getMessage(), mue);
            this.stop(true);
        } catch (InvalidBEncodingException ibee) {
            logger.error("Error parsing tracker response: {}",
                    ibee.getMessage(), ibee);
            this.stop(true);
        } catch (IOException ioe) {
            logger.warn("Error reading response from tracker: {}",
                    ioe.getMessage());
        } finally {
            if (result != null && result.gotError()) {
                    logger.warn("{}", result.getErrorString());
                result = null;
            }
        }

        return result;
    }

    /**
     * Build the announce request URL from the provided parameters.
     *
     * @param params The key/value parameters pairs in a map.
     * @return The URL object representing the announce request URL.
     */
    private URL buildAnnounceURL(Map<String, String> params)
            throws UnsupportedEncodingException, MalformedURLException {
        StringBuilder url = new StringBuilder(this.torrent.getAnnounceUrl());

        if (!params.isEmpty()) {
            url.append('?');
        }

        for (Map.Entry<String, String> param : params.entrySet()) {
            url.append(param.getKey())
                    .append('=')
                    .append(URLEncoder.encode(param.getValue(),
                            Torrent.BYTE_ENCODING))
                    .append('&');
        }

        return new URL(url.toString().substring(0, url.length() - 1));
    }

    /**
     * Handle an announce request answer to set the announce interval.
     */
    public void handleAnnounceResponse(AnnounceResponse answer) {
        if (answer != null) {
            this.interval = answer.getInterval();
            this.initial = false;
        }
    }
}
