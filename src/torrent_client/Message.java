package torrent_client;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.BitSet;


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
    public static byte TERMINATE = 8;

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

    public BitSet getBitfield() {
        return bitfield;
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
class TerminateMessage extends Message {
    @Override
    public byte getMessageType() { return TERMINATE; }

    @Override
    public int getMessageLength() { return 1; }

    @Override
    public byte[] getPayload() { return null; }
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

