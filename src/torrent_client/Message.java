package torrent_client;

import java.io.*;
import java.nio.ByteBuffer;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.BitSet;
import java.util.Scanner;
import java.util.stream.Collectors;

/**
 * Factory Pattern implementation of Abstract Class for messages.
 * By abstracting, namely message type, then the client need only know how to create a 'Message' from 'type' and
 * 'payload' params, without knowledge of specific message (ex. 'ChokeMessage', 'RequestMessage') format and
 * creation.
 */
public abstract class Message {
    public static byte CHOKE = 0;
    public static byte UNCHOKE = 1;
    public static byte INTERESTED = 2;
    public static byte NOT_INTERESTED = 3;
    public static byte HAVE = 4;
    public static byte BITFIELD = 5;
    public static byte REQUEST = 6;
    public static byte PIECE = 7;

    public abstract byte getMessageType();
    public abstract int getMessageLength();
    public abstract byte[] getPayload();

    /**
     * Store message fields (interpretable) to byte array (transmittable)
     */
    public byte[] serialize() {
        // ( [length] + [type] + [payload] ) -> byte array
        byte type = getMessageType();
        byte[] payload = getPayload();
        int length = payload == null ? 1 : 1 + payload.length;

        ByteBuffer buffer = ByteBuffer.allocate(length + 4);
        buffer.putInt(length);      // 4-bytes
        buffer.put(type);           // 1-byte

        if (payload != null) {
            buffer.put(payload);    // n-bytes
        }

        return buffer.array();
    }
}

// Zero-payload messages

class ChokeMessage extends Message {
    @Override
    public byte getMessageType() { return CHOKE; }

    @Override
    public int getMessageLength() { return 1; }

    @Override
    public byte[] getPayload() { return null; }
}

class UnchokeMessage extends Message {
    @Override
    public byte getMessageType() { return UNCHOKE; }

    @Override
    public int getMessageLength() { return 1; }

    @Override
    public byte[] getPayload() { return null; }
}

class InterestedMessage extends Message {
    @Override
    public byte getMessageType() { return INTERESTED; }

    @Override
    public int getMessageLength() { return 1; }

    @Override
    public byte[] getPayload() { return null; }
}

class NotInterestedMessage extends Message {
    @Override
    public byte getMessageType() { return NOT_INTERESTED; }

    @Override
    public int getMessageLength() { return 1; }

    @Override
    public byte[] getPayload() { return null; }
}

// Payload messages

class HaveMessage extends Message {
    private int piece_index;

    public HaveMessage(int piece_index) { this.piece_index = piece_index; }

    public int getPieceIndex() { return piece_index; }

    @Override
    public byte getMessageType() { return HAVE; }

    @Override
    public int getMessageLength() { return 5; }     // type + piece index (4-bytes)

    @Override
    public byte[] getPayload() {
        return ByteBuffer.allocate(4).putInt(piece_index).array();
    }

    public static Message parse(byte[] bytes) {
        int piece_index = ByteBuffer.wrap(bytes).getInt();
        return new HaveMessage(piece_index);
    }
}

class BitfieldMessage extends Message {
    private BitSet bitfield;
    private int num_pieces;

    public BitfieldMessage(BitSet bitfield, int num_pieces) {
        this.bitfield = bitfield;
        this.num_pieces = num_pieces;
    }

    @Override
    public byte getMessageType() { return BITFIELD; }

    @Override
    public int getMessageLength() { return 1 + getPayload().length; }     // type + bitfield

    @Override
    public byte[] getPayload() {
        int num_bytes = (num_pieces + 7) / 8;   // round to nearest byte
        byte[] payload = new byte[num_bytes];

        // big-endian filling
        for (int i = 0; i < num_pieces; i++) {
            if (bitfield.get(i)) {
                int byte_index = i / 8;
                int bit_index = 7 - (i % 8);   // big endian (high to low)
                payload[byte_index] |= (1 << bit_index);
            }
        }

        return payload;
    }

    public static Message parse(byte[] payload, int num_pieces) {
        BitSet bitfield = new BitSet(num_pieces);

        for (int i = 0; i < num_pieces; i++) {
            int byte_index = i / 8;
            int bit_index = 7 - (i % 8);
            if (byte_index < payload.length) {
                if ((payload[byte_index] & (1 << bit_index)) != 0) {
                    bitfield.set(i);
                }
            }
        }

        return new BitfieldMessage(bitfield, num_pieces);
    }
}

class RequestMessage extends Message {
    private int piece_index;

    public RequestMessage(int piece_index) { this.piece_index = piece_index; }

    public int getPieceIndex() { return piece_index; }

    @Override
    public byte getMessageType() { return REQUEST; }

    @Override
    public int getMessageLength() { return 5; }     // type + piece index (4-bytes)

    @Override
    public byte[] getPayload() {
        return ByteBuffer.allocate(4).putInt(piece_index).array();
    }

    public static Message parse(byte[] bytes) {
        int piece_index = ByteBuffer.wrap(bytes).getInt();
        return new RequestMessage(piece_index);
    }
}

class PieceMessage extends Message {
    private int piece_index;
    private byte[] content;

    public PieceMessage(int piece_index, byte[] content) {
        this.piece_index = piece_index;
        this.content = content;
    }

    public int getPieceIndex() { return piece_index; }
    public byte[] getContent() { return content; }

    @Override
    public byte getMessageType() { return PIECE; }

    @Override
    public int getMessageLength() { return 5 + content.length; }     // type + piece index + content

    @Override
    public byte[] getPayload() {
        ByteBuffer buffer = ByteBuffer.allocate(4 + content.length);
        buffer.putInt(piece_index);
        buffer.put(content);
        return buffer.array();
    }

    public static Message parse(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int piece_index = buffer.getInt();
        byte[] content = new byte[buffer.remaining()];
        buffer.get(content);

        return new PieceMessage(piece_index, content);
    }
}

// Example usage:
// public synchronized void sendMessage(Message msg, OutputStream out) {
//     // ^ 'synchronized' keyword to ensures only one message written at a given time
//     byte[] data = msg.serialize();   // converts Message to byte array
//     out.write(data);                 // transfer to output stream (socket)
//     out.flush();                     // send immediately
// }
//
// peer.sendMessage(new ChokeMessage(), outputStream);
// peer.sendMessage(new HaveMessage(piece_index), outputStream);
// peer.sendMessage(new PieceMessage(piece_index, content), outputStream);





// TODO: Move to new file 'CommonConfig.java' and make public
class CommonConfig {
    private final int num_pref_neighbors;
    private final int unchoking_interval;
    private final int opt_unchoking_interval;
    private final String file_name;
    private final int file_size;
    private final int piece_size;

    CommonConfig() {
        num_pref_neighbors = 2;
        unchoking_interval = 5;
        opt_unchoking_interval = 15;
        file_name = "TheFile.dat";
        file_size = 10000232;
        piece_size = 32768;
    }

    CommonConfig(int num_pref_neighbors, int unchoking_interval, int opt_unchoking_interval,
                 String file_name, int file_size, int piece_size) {
        this.num_pref_neighbors = num_pref_neighbors;
        this.unchoking_interval = unchoking_interval;
        this.opt_unchoking_interval = opt_unchoking_interval;
        this.file_name = file_name;
        this.file_size = file_size;
        this.piece_size = piece_size;
    }

    int getNumPrefNeighbors() { return num_pref_neighbors; }
    int getUnchokingInterval() { return unchoking_interval; }
    int getOptUnchokingInterval() { return opt_unchoking_interval; }
    String getFileName() { return file_name; }
    int getFileSize() { return file_size; }
    int getPieceSize() { return piece_size; }
}

// TODO: Move to new file 'ConfigParser.java' and make public
class ConfigParser {
    CommonConfig parseCommonConfig(String filename) {
        int num_pref_neighbors = 2;
        int unchoking_interval = 5;
        int opt_unchoking_interval = 15;
        String file_name = "TheFile.dat";
        int file_size = 10000232;
        int piece_size = 32768;

        try {
            Scanner scanner = new Scanner(new File(filename));
            while (scanner.hasNext()) {
                String property = scanner.next();

                if (!scanner.hasNext()) break;

                if (property.equals("NumberOfPreferredNeighbors")) { num_pref_neighbors = scanner.nextInt(); }
                else if (property.equals("UnchokingInterval")) { unchoking_interval = scanner.nextInt(); }
                else if (property.equals("OptimisticUnchokingInterval")) { opt_unchoking_interval = scanner.nextInt(); }
                else if (property.equals("FileName")) { file_name = scanner.next(); }
                else if (property.equals("FileSize")) { file_size = scanner.nextInt(); }
                else if (property.equals("PieceSize")) { piece_size = scanner.nextInt(); }
                else { scanner.next(); }
            }

            scanner.close();
        } catch (FileNotFoundException e) {
            System.err.println("Error parsing 'Common.cfg': " + e.getMessage());
        }

        return new CommonConfig(
                num_pref_neighbors, unchoking_interval, opt_unchoking_interval, file_name, file_size, piece_size
        );
    }

    // TODO: Consider moving 'PeerProcess.java' PeerInfo.cfg parsing here
    //  for unified config parsing functionality
}

// TODO: Move to new file 'MessageHandler.java' and make public
class MessageHandler {
    private final int num_pieces;
    private final int piece_size;

    public MessageHandler(CommonConfig config) {
        num_pieces = (int) Math.ceil((double) config.getFileSize() / config.getPieceSize());
        piece_size = config.getPieceSize();         // TODO: Need?
    }

    public Message parseMessage(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int length = buffer.getInt();
        byte type = buffer.get();

        byte[] payload = null;
        if (length > 1) {
            payload = new byte[length - 1];
            buffer.get(payload);
        }

        return createMessage(type, payload);
    }

    private Message createMessage(int type, byte[] payload) throws IllegalArgumentException {
        // TODO: Consider validation checking (ex. null payload) -> Needed?
        if (type == Message.CHOKE) { return new ChokeMessage(); }
        else if (type == Message.UNCHOKE) { return new UnchokeMessage(); }
        else if (type == Message.INTERESTED) { return new InterestedMessage(); }
        else if (type == Message.NOT_INTERESTED) { return new NotInterestedMessage(); }
        else if (type == Message.HAVE) { return HaveMessage.parse(payload); }
        else if (type == Message.BITFIELD) { return BitfieldMessage.parse(payload, num_pieces); }
        else if (type == Message.REQUEST) { return RequestMessage.parse(payload); }
        else if (type == Message.PIECE) { return PieceMessage.parse(payload); }
        else {throw new IllegalArgumentException("Error: Unknown message type '" + type + "'");}
    }
}

// Example usage:
// public Message receiveMessage(DataInputStream in) throws IOException {
//     int length = in.readInt();                      // extract length
//     byte[] data = new byte[4 + length];             // length + (type + payload)
//
//     ByteBuffer.wrap(data).putInt(length);           // prepend data with length bytes
//     in.readFully(data, 4, length);                  // extract (type + payload) into data
//     // ^ 'readFully()' blocks until all bytes received
//
//     return MessageHandler.parseMessage(data);       // converts byte array to Message
// }





// TODO: Move to new file 'Handshake.java' and make public
class HandshakeMessage {
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





// TODO: Move to new file 'Logger.java' and make public
class Logger implements AutoCloseable {
    private final PrintWriter writer;   // wrapper
    private final int peer_ID;          // sending peer

    public Logger(int peer_ID) throws IOException {
        this.peer_ID = peer_ID;
        writer = new PrintWriter(
                new BufferedWriter(
                        new FileWriter("log_peer_" + peer_ID + ".log", true)
                ), true);
    }

    private void log(String msg) {
        // '[Time]: [message].'
        LocalTime now = LocalTime.now();
        writer.printf("%s: %s.%n", now.format(DateTimeFormatter.ofPattern("HH:mm:ss")), msg);
    }

    public void logTCPConnection(int peer_ID_1, int peer_ID_2) {
        // 'Peer [peer_ID_1] makes a connection to Peer [peer_ID_2]'
        log(String.format("Peer %d makes a connection to Peer %d", peer_ID_1, peer_ID_2));
    }

    public void logChangeOfPrefNeighbors(int peer_ID, List<Integer> neighbors_list) {
        // 'Peer [peer_ID] has the preferred neighbors [peer_ID (, peer_ID)*]'
        String neighbors_csv = neighbors_list.stream().map(String::valueOf).collect(Collectors.joining(","));
        log(String.format("Peer %d has the preferred neighbors %s", peer_ID, neighbors_csv));
    }

    public void logChangeOfOptUnchokedNeighbor(int peer_ID_1, int peer_ID_2) {
        // 'Peer [peer_ID_1] has the optimistically unchoked neighbor [peer_ID_2]'
        log(String.format("Peer %d has the optimistically unchoked neighbor %d", peer_ID_1, peer_ID_2));
    }

    public void logUnchoking(int peer_ID_1, int peer_ID_2) {
        // 'Peer [peer_ID 1] is unchoked by [peer_ID 2]'
        log(String.format("Peer %d is unchoked by %d", peer_ID_1, peer_ID_2));
    }

    public void logChoking(int peer_ID_1, int peer_ID_2) {
        // 'Peer [peer_ID_1] is choked by [peer_ID_2]'
        log(String.format("Peer %d is choked by %d", peer_ID_1, peer_ID_2));
    }

    public void logReceivingHave(int peer_ID_1, int peer_ID_2, int piece_index) {
        // 'Peer [peer_ID_1] received the ‘have’ message from [peer_ID_2] for the piece [piece index]'
        log(String.format("Peer %d received the 'have' message from %d for the piece %d", peer_ID_1, peer_ID_2, piece_index));

    }

    public void logReceivingInterested(int peer_ID_1, int peer_ID_2) {
        // ' Peer [peer_ID_1] received the ‘interested’ message from [peer_ID_2]'
        log(String.format("Peer %d received the 'interested' message from %d", peer_ID_1, peer_ID_2));
    }

    public void logReceivingNotInterested(int peer_ID_1, int peer_ID_2) {
        // ' Peer [peer_ID 1] received the ‘not interested’ message from [peer_ID 2]'
        log(String.format("Peer %d received the 'not interested' message from %d ", peer_ID_1, peer_ID_2));
    }

    public void logDownloadingPiece(int peer_ID_1, int peer_ID_2, int piece_index, int num_pieces) {
        // 'Peer [peer_ID_1] has downloaded the piece [piece_index] from [peer_ID_2]. Now the number of pieces it has is [num_pieces]'
        log(String.format("Peer %d has downloaded the piece %d from %d. Now the number of pieces it has is %d", peer_ID_1, piece_index, peer_ID_2, num_pieces));
    }

    public void logCompletionOfDownload(int peer_ID) {
        // 'Peer [peer_ID] has downloaded the complete file'
        log(String.format("Peer %d completed the download", peer_ID));
    }

    @Override
    public void close() {
        writer.close();
    }
}