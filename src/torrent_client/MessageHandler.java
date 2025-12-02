package torrent_client;

import java.nio.ByteBuffer;

public class MessageHandler {
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
        else if (type == Message.TERMINATE) { return new TerminateMessage(); }
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

