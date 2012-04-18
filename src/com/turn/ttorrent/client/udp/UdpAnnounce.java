package com.turn.ttorrent.client.udp;

import com.turn.ttorrent.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Random;

/**
 * Created by IntelliJ IDEA.
 * User: Johan Parent
 * Date: 19/02/12
 * Time: 11:46
 * To change this template use File | Settings | File Templates.
 */
public class UdpAnnounce extends Announce {
    private static final Logger logger =
            LoggerFactory.getLogger(UdpAnnounce.class);

    // Randomized by client.
    int transactionId;
    //
    int peers;
    //
    ByteBuffer byteBuffer;

    /**
     * Must be initialized to 0x41727101980 in network byte order. This will identify the protocol.
     * <p/>
     * A connection id, this is used when further information is exchanged with the tracker,
     * to identify you. This connection id can be reused for multiple requests, but if it's
     * cached for too long, it will not be valid anymore.
     */
    long connectionId = -1;

    DatagramSocket socket;
    private Random random = new Random(System.currentTimeMillis());
    private long downloaded;
    private long left;
    private long uploaded;
    private int key;

    public UdpAnnounce(SharedTorrent torrent, String myPeerId, InetSocketAddress address) {
        super(torrent, myPeerId, address);
    }

    @Override
    protected AnnounceResponse announce(Announce.AnnounceEvent event, boolean inhibitEvent) {
        AnnounceResponse result = null;
        try {
            switch (event) {
                case STARTED:
                    connect(); // NOTE: we want to fall through!
                case NONE:
                    result = announce(event);

                case COMPLETED:
                    break;

                case STOPPED:
                    break;
            }
        } catch (IOException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (URISyntaxException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }

        if (!inhibitEvent && result != null) {
            for (AnnounceResponseListener listener : this.listeners) {
                listener.handleAnnounceResponse(result);
            }
        }

        return result;
    }

    /*
   connecting
Client sends packet:

size	name	description
int64_t	connection_id	Must be initialized to 0x41727101980 in network byte order. This will identify the protocol.
int32_t	action	0 for a connection request
int32_t	transaction_id	Randomized by client.
Server replies with packet:

size	name	description
int32_t	action	Describes the type of packet, in this case it should be 0, for connect. If 3 (for error) see errors.
int32_t	transaction_id	Must match the transaction_id sent from the client.
int64_t	connection_id	A connection id, this is used when further information is exchanged with the tracker, to identify you.
This connection id can be reused for multiple requests, but if it's cached for too long, it will not be valid anymore.
    */
    public void connect() throws IOException, URISyntaxException, UnknownHostException {
        socket = new DatagramSocket();
        URI uri = new URI(torrent.getAnnounceUrl());
        byte[] buf = new byte[256];
        InetAddress address =
                InetAddress.getByName(uri.getHost());
        DatagramPacket packet = new DatagramPacket(
                buf, buf.length,
                address, uri.getPort()
        );
        //
        transactionId = random.nextInt();
        //
        byteBuffer = ByteBuffer.wrap(new byte[128]);
        byteBuffer.putLong(0x41727101980L);
        byteBuffer.putInt(Actions.CONNECT.action);
        byteBuffer.putInt(transactionId);
        packet.setData(byteBuffer.array(), 0, byteBuffer.position());
        socket.send(packet);
        //
        packet = new DatagramPacket(buf, buf.length);
        socket.receive(packet);
        byteBuffer = ByteBuffer.wrap(packet.getData());
        //
        check(Actions.CONNECT);
    }

    /*
    int64_t	connection_id	The connection id acquired from establishing the connection.
int32_t	action	Action. in this case, 1 for announce. See actions.
int32_t	transaction_id	Randomized by client.
int8_t[20]	info_hash	The info-hash of the torrent you want announce yourself in.
int8_t[20]	peer_id	Your peer id.
int64_t	downloaded	The number of byte you've downloaded in this session.
int64_t	left	The number of bytes you have left to download until you're finished.
int64_t	uploaded	The number of bytes you have uploaded in this session.
int32_t	event
The event, one of

none = 0
completed = 1
started = 2
stopped = 3
uint32_t	ip	Your ip address. Set to 0 if you want the tracker to use the sender of this udp packet.
uint32_t	key	A unique key that is randomized by the client.
int32_t	num_want	The maximum number of peers you want in the reply. Use -1 for default.
uint16_t	port	The port you're listening on.
uint16_t	extensions	See extensions
     */
    public AnnounceResponse announce(AnnounceEvent event) throws IOException, URISyntaxException {
        byte[] buffer = new byte[8 + 4 + 4 + 20 + 20 + 8 + 8 + 8 + 4 + 4 + 4 + 4 + 2 + 2];
        byteBuffer = ByteBuffer.wrap(buffer);
        //
        byteBuffer.putLong(connectionId).putInt(Actions.ANNOUNCE.action).putInt(transactionId);
        byteBuffer.put(torrent.getInfoHash());
        byte[] pb = id.getBytes();
        byteBuffer.put(pb, 0, pb.length);
        left = torrent.getLeft();
        downloaded = torrent.getDownloaded();
        uploaded = torrent.getUploaded();
        byteBuffer.putLong(downloaded).putLong(left).putLong(uploaded);
        byteBuffer.putInt(event.ordinal());
        byteBuffer.put(new byte[]{0, 0, 0, 0});  // Use address from datagram
        key = random.nextInt();
        byteBuffer.putInt(key).putInt(-1).putShort((short) socket.getPort());
        byteBuffer.putShort((short) 0); // nothing set, according to specs this is a bitmask thing
        //
        URI uri = new URI(torrent.getAnnounceUrl());
        InetAddress address =
                InetAddress.getByName(uri.getHost());
        DatagramPacket packet = new DatagramPacket(byteBuffer.array(), byteBuffer.position(), address, uri.getPort());
        socket.send(packet);
        //
        byte[] in = new byte[400];
        packet = new DatagramPacket(in, in.length);
        socket.receive(packet);
        byteBuffer = ByteBuffer.wrap(packet.getData());
        //
        AnnounceResponse error = check(Actions.ANNOUNCE);
        if (error != null) {
            return error;
        }
        interval = byteBuffer.getInt();
        int leechers = byteBuffer.getInt();
        int seeders = byteBuffer.getInt();
        // todo is this correct?
        peers = leechers + seeders;
        //
        AnnounceResponse response = AnnounceReponseFactory.create(interval);
        //
        byte[] bytes = new byte[4];
        while (byteBuffer.remaining() > 6) {
            byteBuffer.get(bytes);
            if (bytes[0] == 0 && bytes[1] == 0 && bytes[2] == 0 && bytes[3] == 0)
                break;

            InetAddress inetAddress = InetAddress.getByAddress(bytes);
            String ip = inetAddress.getHostAddress();
            AnnounceReponsePeer peer = AnnounceResponsePeerFactory.create(
                    null,
                    ip,
                    byteBuffer.getShort() & 0xffff
            );
            response.getPeers().add(peer);
        }

        return response;
    }

    /*

size	name	description
int32_t	action	The action, should in this case be 2 for scrape. If 3 (for error) see errors.
int32_t	transaction_id	Must match the sent transaction id.
The rest of the packet contains the following structures once for each info-hash you asked in the scrape request.

size	name	description
int32_t	complete	The current number of connected seeds.
int32_t	downloaded	The number of times this torrent has been downloaded.
int32_t	incomplete
     */
    public void scrape() throws URISyntaxException, IOException {
        byte[] buffer = new byte[8 + 4 + 4 + 20];
        byteBuffer = ByteBuffer.wrap(buffer);
        //
        byteBuffer.putLong(connectionId).putInt(Actions.SCRAPE.action).putInt(transactionId);
        byteBuffer.put(torrent.getInfoHash());
        //
        URI uri = new URI(torrent.getAnnounceUrl());
        InetAddress address =
                InetAddress.getByName(uri.getHost());
        DatagramPacket packet = new DatagramPacket(byteBuffer.array(), byteBuffer.position(), address, uri.getPort());
        socket.send(packet);
        //
        buffer = new byte[4 + 4 + 4 + 4 + 4];
        //
        packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        byteBuffer = ByteBuffer.wrap(packet.getData());
        //
        check(Actions.SCRAPE);
        int seeds = byteBuffer.getInt();
        int dls = byteBuffer.getInt();
        int leechers = byteBuffer.getInt();
    }

    public String error() {
        int txId = byteBuffer.getInt();
        byte[] bytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(bytes);
        return new String(bytes);

    }

    AnnounceResponse check(Actions action) {
        if (byteBuffer.remaining() < 16) {
            logger.error("Reply to short: {} bytes", byteBuffer.remaining());
            stop(true);
            return createErrorAnnounceResponse("reply too short");
        }
        int a = byteBuffer.getInt();
        if (a != action.action) {
            if (a == Actions.ERROR.action)
                logger.error("wrong action {}", error());
            else
                logger.error("action should be ERROR {}", error());
            stop(true);
            return createErrorAnnounceResponse("Wrong action");
        }
        int txId = byteBuffer.getInt();
        if (txId != transactionId) {
            logger.error("transaction ID mismatch {} vs {} (expected)", txId, transactionId);
            stop(true);
            return createErrorAnnounceResponse("transaction ID mismatch");
        }

        return null;
    }

    private AnnounceResponse createErrorAnnounceResponse(String s) {
        AnnounceResponse ret = AnnounceReponseFactory.create(-1);
        ret.setErrorString(s);
        return ret;
    }
}
