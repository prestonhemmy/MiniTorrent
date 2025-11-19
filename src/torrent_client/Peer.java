package torrent_client;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CHANGES MADE:
 *  Switched from String text sending to Message class sending
 *  Replaced BufferedReader/PrintWriter with DataInputSteam/DataOutputStream
 *  peerOutputs map for sending data to specific peers
 *  uses 'ConcurrentHashMap' and 'synchronized' to ensure concurrency
 *
 *  Integrated 'HandshakeMessage' class
 *  Had to update 'sendMessage()' to take params destination 'destPeerID' and 'HandshakeMessage message'
 *  Tried to leave previous code commented out or unchanged
 */
public class Peer {
    int peerID;
    String hostname;
    int port;
    boolean[] hasChunks;
//    boolean[] hasChunks = new boolean[4]; // for testing purposes, change later
    ArrayList<Peer> neighbors;
//    Map<Integer, Socket> sockets = new HashMap<>();
//    Socket currentSocket;
//    PrintWriter out;

    // 'ConcurrentHashMap' used for thread safety (provides synchronization)
    Map<Integer, DataOutputStream> peerOutputs = new ConcurrentHashMap<>();
    Map<Integer, Socket> sockets = new ConcurrentHashMap<>();

    public Peer(int peerID, String hostname, int port, boolean[] hasChunks) {
        this.peerID = peerID;
        this.hostname = hostname;
        this.port = port;
        this.hasChunks = hasChunks;
    }

    public String getHostname() { return hostname; }
    public int getPort() { return port; }
    public int getPeerID() { return peerID; }
    public boolean[] getHasChunks() { return hasChunks; }

    void setNeighbors(ArrayList<Peer> neighbors){
        this.neighbors = neighbors;
    }

    ArrayList<boolean[]> getChunkListFromNeighbors(ArrayList<Peer> neighbors) {
        ArrayList<boolean[]> chunkList = new ArrayList<>();
        for (Peer neighbor : neighbors) {
            chunkList.add(neighbor.hasChunks);
        }
        return chunkList;
    }

    // Each peer must have its own server, so we create a it here and call it when needed
    // Use threads so it doesnt take forever
    public void start() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) { // Starts listening on port
                System.out.println("Peer " + peerID + " is listening on port " + port);

                while (true) {
                    Socket socket = serverSocket.accept(); // Accept connection if found

                    new Thread(() -> handleIncomingConnection(socket)).start();
//                    new Thread(() -> {
//                        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) { // Read in bytes)
//                            String message;
//                            while ((message = in.readLine()) != null) {
//                                System.out.println("Peer " + peerID + " received: " + message);
//                            }
//                        } catch (IOException e) {
//                            throw new RuntimeException(e);
//                        }
//                    }).start();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    // each peer "acting as a server" ( parallel method to 'establishConnection()' )
    public void handleIncomingConnection(Socket socket) {
        try {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // (2). receive handshake
            byte[] handshakeBytes = new byte[32];
            in.readFully(handshakeBytes);           // blocks until all 32 bytes received
            HandshakeMessage rcvHandshake = HandshakeMessage.parse(handshakeBytes);

            int remotePeerID = rcvHandshake.getPeerID();
            System.out.println("Peer " + peerID + " received handshake from peer " + remotePeerID);

            // (3). send handshake response
            HandshakeMessage sendHandshake = new HandshakeMessage(peerID);
            out.write(sendHandshake.serialize());   // transfer byte array representation to output stream
            out.flush();                            // send immediately

            sockets.put(remotePeerID, socket);
            peerOutputs.put(remotePeerID, out);

            // (6). listen for messages
            while (!socket.isClosed()) {
                int length = in.readInt();

                if (length > 0) {
                    byte[] msgData = new byte[length];
                    in.readFully(msgData);

                    System.out.println("Peer " + peerID + " received message of length " + length + " from peer " + remotePeerID);

                    // In the future (for handling messages):
                    // ByteBuffer buffer = ByteBuffer.allocate(length + msgData.length);
                    // buffer.putInt(length);
                    // buffer.put(msgData);
                    // byte[] data = buffer.array();
                    // Message msg = MessageHandler.parseMessage(data);
                }
            }
        } catch (IOException e) {
            System.err.println("Connection error: " + e.getMessage());
            // Example: remotePeer only sends 10 bytes but in.readFully() is waiting for 32 byte header
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid handshake message: " + e.getMessage());
            // Example: if remotePeer sends message with invalid handshake header
        }
    }

    //Each peer must also act as a client to connect other peer servers
    public void establishConnection(int peerConnectionId, String serverPeerHost, int serverPeerPort) {
        new Thread(() -> {
            try { // Connects to other Peer's server
                System.out.println("Peer " + peerID + " attempting to connect to Peer " + peerConnectionId);
                Socket socket = new Socket(serverPeerHost, serverPeerPort); // 3-way handshake

                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream());

                // (1). send handshake
                HandshakeMessage sendHandshake = new HandshakeMessage(peerID);
                out.write(sendHandshake.serialize());   // transfer byte array representation to output stream
                out.flush();                            // send immediately

                // (4). receive handshake response
                byte[] handshakeBytes = new byte[32];
                in.readFully(handshakeBytes);           // blocks until all 32 bytes received
                HandshakeMessage rcvHandshake = HandshakeMessage.parse(handshakeBytes);

                if (rcvHandshake.getPeerID() != peerConnectionId) {
                    System.err.println("Error: Expected peer " + peerConnectionId + " but got " + rcvHandshake.getPeerID());
                    socket.close();
                    return;
                }

                System.out.println("Peer " + peerID + " established a connection with Peer " + peerConnectionId);

                sockets.put(peerConnectionId, socket);
                peerOutputs.put(peerConnectionId, out);

                // (5). listen for messages
                while (!socket.isClosed()) {
                    int length = in.readInt();

                    if (length > 0) {
                        byte[] msgData = new byte[length];
                        in.readFully(msgData);

                        System.out.println("Received message from Peer " + peerConnectionId);

                        // In the future (for handling messages):
                        // ByteBuffer buffer = ByteBuffer.allocate(length + msgData.length);
                        // buffer.putInt(length);
                        // buffer.put(msgData);
                        // byte[] data = buffer.array();
                        // Message msg = MessageHandler.parseMessage(data);
                    }
                }
//                if (sockets != null && !sockets.containsKey(peerConnectionId)) {
//                    currentSocket = new Socket(serverPeerHost, serverPeerPort);
//                    sockets.put(peerConnectionId, currentSocket);
//                    out = new PrintWriter(currentSocket.getOutputStream(), true);
//                } else {currentSocket = sockets.get(peerConnectionId);
//                    out = new PrintWriter(currentSocket.getOutputStream(), true);
//                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    // Returns a list of the piece indexes that the inputted peer has that the given peer needs
    public ArrayList<Integer> checkPieces(Peer peer) {
        ArrayList<Integer> indices = new ArrayList<>();
        for (int i = 0; i < peer.hasChunks.length; i++) {
            if (hasChunks[i] == false && peer.hasChunks[i] == true) {
                indices.add(i);
            }
        }
        return indices;
    }

    public void sendMessage(int destPeerID, HandshakeMessage message) {
        new Thread(() -> {
            try {
                DataOutputStream destOut = peerOutputs.get(destPeerID);

                if (destOut != null) {
                    byte[] data = message.serialize();

                    // 'synchronized' to ensure only one message written at a given time
                    synchronized (destOut) {
                        destOut.write(data);                // transfer to output stream
                        destOut.flush();                    // send immediately
                    }

                    System.out.println("Peer " + peerID + " sending " + message.getClass().getName() + " to Peer " + destPeerID);
                } else {
                    System.err.println("No connection found between Peer " + peerID + " and Peer " + destPeerID);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
//            if (out != null) {
//                out.println(message);
//                System.out.println("Message: "+ message +" sent ");
//            }
        }).start();
    }
}

//

// Peers have a peerID, hostname, port number and a boolean to check if they have the file
// Peers store Pieces, each with a unique identifier
// Peers manage their socket connections up to a given limit
// Randomly makes connections

// Tracker sends new list of peers
// Tracker contains unchoking interval, list of peers in torrent
// Returns to current peer

// Peer is going to request each of the neighboring peers for a list of chunks that they
// If they have a different chunk, successfully connect
// Else, looks for new neighbors

// Peer can send chunks, peer can receive chunks
//

// ORDER OF SUCCESS
// 1.) Send file to Peer
// 2.) Send chunks to peer
// 3.) Send chunks to multiple peers

// int[] neighbors = tracker.getNeighbors(Peer);
// Peer.setNeighbors(neighbor)
// boolean[][]
