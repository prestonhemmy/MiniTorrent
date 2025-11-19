package torrent_client;

import java.nio.ByteBuffer;

public class HandshakeMessage {
    private static final String HEADER = "P2PFILESHARINGPROJ";
    private final int peer_ID;

    public HandshakeMessage(int peer_ID) { this.peer_ID = peer_ID; }

    public byte[] serialize() {
        // ( 'HEADER' + [0-bits] x 10 + [peer_ID] ) -> byte array
        ByteBuffer buffer = ByteBuffer.allocate(32);
        buffer.put(HEADER.getBytes());
        byte[] zeros = new byte[10];
        buffer.put(zeros);
        buffer.putInt(peer_ID);

        return buffer.array();
    }

    public static HandshakeMessage parse(byte[] bytes) {
        // byte array -> ( 'HEADER' + ... + [peer_ID] )
        ByteBuffer buffer = ByteBuffer.wrap(bytes);

        byte[] header = new byte[18];
        buffer.get(header);
        if (!HEADER.equals(new String(header))) {
            throw new IllegalArgumentException("Error: Unknown handshake header");
        }

        // skip 0-bits in [18, 27]

        buffer.position(28);
        int peer_ID = buffer.getInt();

        return new HandshakeMessage(peer_ID);   // -> defer checking of expected peer_ID to invoking method
    }

    String getHeader() { return HEADER; }
    int getPeerID() { return peer_ID; }
}

// Example usage:
// public void sendHandshake(int peer_ID, OutputStream out) throws IOException {
//    HandshakeMessage handshake = new HandshakeMessage(peer_ID);
//    out.write(handshake.serialize());
//    out.flush();
// }
