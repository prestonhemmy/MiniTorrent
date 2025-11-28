package torrent_client;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * CHANGES MADE:
 *  Added member variables 'config', 'fileManager' and 'messageHandler', 'Logger', 'neighborBitfields'
 *  -> FileManager requires a CommonConfig object to read file name, file size, and piece size
 *     Therefore, Peer() constructor now takes a 'config' parameter
 *  -> 'hasChunks' now initialized using 'getBitfieldArray()' of the FileManager class
 *     A FileManager is associated with each peer (peer directory ex. 1001/, 1002/, ...)
 *     and each instance maintains a BitSet 'available_pieces' which is initialized to all 1s
 *     if the associated peer has the complete file, o.w. each respective bit is set using
 *     during piece exchange (P2P process)
 *     Then 'getBitfieldArray()' converts the BitSet to a boolean[]
 *  -> MessageHandler is also initialized with a CommonConfig object since 'num_pieces' is
 *     required for forming the payload of a BitfieldMessage
 *     MessageHandler provides method 'parseMessage()' which deserializes an incoming byte stream,
 *     converting it to the respective Message type; it is called in the listening loops of
 *     handleIncomingConnection() and establishConnection() as this is where we listen for incoming
 *     messages (byte streams)
 *  -> The member variable 'line_counter' helper methods 'printLog()' are TEMPORARY utilities for
 *     producing more readable command line status updates
 *  -> Logger is a wrapper for FileWriter, handling timestamping and log message formatting
 *  -> 'neighborBitfields' maps 'remotePeerID's to their corresponding bitfield; the bitfield is
 *     managed by the FileManager, where it is updated for each 'piece file' saved to the associated
 *     peer's directory; 'neighborBitfields' is updated in processMessage() whenever a BitfieldMessage
 *     or a HaveMessage is received (processed) and used to determine interest (i.e. send
 *     InterestedMessage/NotInterestedMessage)
 *  -> 'processMessage()' is called in both listening loops; it handles each specific message receipt,
 *     that is, it "processes" each message and performs the corresponding logic; currently handles
 *     BitfieldMessages, RequestMessages and PieceMessages (partial implementation) but this method
 *     still needs implementation of remaining message types
 *  -> Miscellaneous:
 *     - 'msg.getClass().getSimpleName()' returns Message name for print outs
 *     - 'BitfieldMessage' sending added immediately after handshaking logic in both listening loops
 *     - previous 'sendMessage()' for HandshakeMessages generalized to all Message types
 *     - previous 'sendMessage()' logic moved directly to listening loops
 *     - previous 'sendFile()' and 'fileToByte()' logic moved to 'sendPiece()' and FileManager class
 *  CURRENT FUNCTIONALITY:
 *   - connection establishing
 *   - HandshakeMessage passing
 *   - maintaining bitfield for each peer (in FileManager)
 *   - BitfieldMessage exchange (on initialization)
 *   - Neighbor bitfield setup
 *   - RequestMessage passing and processing
 *   - PieceMessage passing
 *   - PieceMessage saving and reassembly (in FileManager)
 *
 *  TODO:
 *   - Neighbor bitfield tracker updates
 *   - complete Logger integration
 *   - Termination logic
 *   - ...
 */
public class Peer {
    int peerID;
    String hostname;
    int port;
    CommonConfig config;
    boolean[] hasChunks;
    FileManager fileManager;
    MessageHandler messageHandler;
    Logger logger;
    String filepath;
    ArrayList<Peer> neighbors;
    ChokingManager chokingManager;
    private ScheduledExecutorService downloadScheduler;

    Map<Integer, BitSet> neighborBitfields = new HashMap<>();       // neighbor bitfield state tracking
    // keys correspond with neighbor's 'peerID'

    int line_counter;   // TEMP (for cleaner command line outputs)

    // 'ConcurrentHashMap' used for thread safety (provides synchronization)
    Map<Integer, DataOutputStream> peerOutputs = new ConcurrentHashMap<>();
    Map<Integer, Socket> sockets = new ConcurrentHashMap<>();

    // New set to locally store which peers are unchoking this peer to request pieces
    private final Set<Integer> unchokedByPeers = Collections.synchronizedSet(new HashSet<>());

    public Peer(int peerID, String hostname, int port, boolean hasFile, CommonConfig config) {
        this.peerID = peerID;
        this.hostname = hostname;
        this.port = port;
        this.config = config;
        this.fileManager = new FileManager(peerID, config, hasFile);
        this.hasChunks = fileManager.getBitfieldArray();
        this.messageHandler = new MessageHandler(config);
        this.filepath = "src/project_config_file_large/" + peerID + "/";

        line_counter = 0;

        try {
            this.logger = new Logger(peerID);
        } catch (IOException e) {
            System.err.println("Error initializing logger: " + e.getMessage());
        }

        this.chokingManager = new ChokingManager(this, config, logger);

    }

    // TEMP helper methods for clean command line outputs
    private synchronized void printLog(String msg) {
        line_counter++;
        System.out.println("[" + line_counter + "] " + msg);
    }

    private synchronized void printLog(String msg, boolean newline) {
        line_counter++;
        System.out.println("\n[" + line_counter + "] " + msg);
    }

    public String getHostname() {
        return hostname;
    }

    public int getPort() {
        return port;
    }

    public int getPeerID() {
        return peerID;
    }

    public boolean[] getHasChunks() {
        return hasChunks;
    }

    void setNeighbors(ArrayList<Peer> neighbors) {
        this.neighbors = neighbors;
    }

    ArrayList<boolean[]> getChunkListFromNeighbors(ArrayList<Peer> neighbors) {
        ArrayList<boolean[]> chunkList = new ArrayList<>();
        for (Peer neighbor : neighbors) {
            chunkList.add(neighbor.hasChunks);
        }
        return chunkList;
    }
    private void markAsUnchoked(int peerID) {
        unchokedByPeers.add(peerID);
        printLog("Peer " + this.peerID + " marked peer " + peerID + " as UNCHOKED");
    }

    private void markAsChoked(int peerID) {
        unchokedByPeers.remove(peerID);
        printLog("Peer " + this.peerID + " marked peer " + peerID + " as CHOKED");
    }

    private boolean isUnchokedBy(int peerID) {
        return unchokedByPeers.contains(peerID);
    }

    /**
     * Logic for randomly selecting an available piece from available and nonrequested piece map
     */
    private void requestNextPiece(int remotePeerID) {
        if (fileManager.hasCompleteFile()) {
            return;
        }

        if (!isUnchokedBy(remotePeerID)) {
            return;
        }

        BitSet neighborBitfield = neighborBitfields.get(remotePeerID);
        if (neighborBitfield == null) {
            return;
        }
        int pieceToRequest = fileManager.randomSelection(neighborBitfield);
        printLog("Random piece to request is piece #" + pieceToRequest);
        if (pieceToRequest >= 0) {
            if (fileManager.markAsRequested(pieceToRequest)) {
                sendMessage(remotePeerID, new RequestMessage(pieceToRequest));
                printLog("Peer " + peerID + " requesting piece " + pieceToRequest + " from peer " + remotePeerID);
            }
            else{
                printLog("Piece #" + pieceToRequest + " already requested");
            }
        }
    }

    /**
     * Schedule repeated downloading on a new thread for unchoked peers
     */
    private void startContinuousDownload() {
        downloadScheduler = Executors.newScheduledThreadPool(1);

        downloadScheduler.scheduleAtFixedRate(() -> {
            try {
                if (!fileManager.hasCompleteFile()) {
                    // Process peers in parallel
                    unchokedByPeers.parallelStream().forEach(this::requestNextPiece);
                }
            } catch (Exception e) {
                System.err.println("Error in continuous download: " + e.getMessage());
            }
        }, 2, 2, TimeUnit.SECONDS); // Arbitrary delay to alternate peers if they run out of interesting pieces

        printLog("Continuous download scheduler started for Peer " + peerID);
    }

    /**
     * Stop continuous download scheduler
     */
    private void stopContinuousDownload() {
        if (downloadScheduler != null && !downloadScheduler.isShutdown()) {
            downloadScheduler.shutdown();
            try {
                if (!downloadScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    downloadScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                downloadScheduler.shutdownNow();
            }
        }
    }
    // Each peer must have its own server, so we create a it here and call it when needed
    // Use threads so it doesnt take forever
    public void start() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) { // Starts listening on port
                printLog("Peer " + peerID + " is listening on port " + port);

                // Starts the choking manager and downloader thread
                chokingManager.start();
                startContinuousDownload();

                while (true) {
                    Socket socket = serverSocket.accept(); // Accept connection if found

                    new Thread(() -> handleIncomingConnection(socket)).start();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * NOTE. Only the handshake logic is asymmetric for the methods 'handleIncomingConnection()'
     * and 'EstablishConnection()'. After the handshake logic, all other logic (BitfieldMessage
     * passing, listening loops) is symmetric in both methods. In fact, the listening loop
     * can be abstracted, but I left it here for consistency and clarity.
     */

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
            printLog("Peer " + peerID + " received handshake from peer " + remotePeerID, true);

            // (3). send handshake response
            HandshakeMessage sendHandshake = new HandshakeMessage(peerID);
            out.write(sendHandshake.serialize());   // transfer byte array representation to output stream
            out.flush();                            // send immediately

            sockets.put(remotePeerID, socket);
            peerOutputs.put(remotePeerID, out);

            // Starts the choking manager
            chokingManager.registerNeighbor(remotePeerID);

            // TODO: Log successful handshake temp fix
            logger.logTCPConnection(peerID, remotePeerID);

            // exchange bitfields
            if (fileManager.getNumPiecesOwned() > 0) {
                BitfieldMessage bitfieldMsg = new BitfieldMessage(
                        fileManager.getBitfield(),
                        fileManager.getNumPieces()
                );

                sendMessage(remotePeerID, bitfieldMsg);
            }

            // (6). listen for messages
            while (!socket.isClosed()) {
                // Why readInt appeared broken: If the buffer.length (file size) sent is larger than the available
                // buffer on the receiving TCP stack, or if the receiver logic desynchronizes from the stream (expecting
                // a 1-byte Type field but getting image data), the readInt on the next loop iteration will try to
                // interpret image data as an integer length, resulting in garbage values (e.g., negative numbers or
                // massive integers). --chat
                int length = in.readInt();
                if (length > 0) {
                    byte[] msgData = new byte[length + 4];  // payload ('length' bytes) + length (4 bytes)

                    ByteBuffer.wrap(msgData).putInt(length);

                    in.readFully(msgData, 4, length);   // read (blocking other reads) from offset of 4 bytes
                    // for offset + 'length' bytes

                    Message msg = messageHandler.parseMessage(msgData);

                    printLog("Peer " + peerID + " received " + msg.getClass().getSimpleName() + " from peer " + remotePeerID);

                    processMessage(remotePeerID, msg);

                    printLog(msg.getClass().getSimpleName() + " processed successfully.");

                }
            }
        } catch (IOException e) {
            System.err.println("Connection error: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid handshake message: " + e.getMessage());
        }
    }

    //Each peer must also act as a client to connect other peer servers
    // TODO: consider renaming to handleOutgoingConnection() or connectToPeer()
    public void establishConnection(int peerConnectionId, String serverPeerHost, int serverPeerPort) {
        new Thread(() -> {
            try { // Connects to other Peer's server
                printLog("Peer " + peerID + " attempting to connect to Peer " + peerConnectionId);
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

                printLog("Peer " + peerID + " established a connection with Peer " + peerConnectionId);

                sockets.put(peerConnectionId, socket);
                peerOutputs.put(peerConnectionId, out);

                chokingManager.registerNeighbor(peerConnectionId);

                // TODO: Log successful handshake temp fix
                logger.logTCPConnection(peerID, peerConnectionId);

                // exchange bitfields
                if (fileManager.getNumPiecesOwned() > 0) {
                    BitfieldMessage bitfieldMsg = new BitfieldMessage(
                            fileManager.getBitfield(),
                            fileManager.getNumPieces()
                    );

                    sendMessage(peerConnectionId, bitfieldMsg);
                } else {
                    printLog("Peer " + peerID + " did not send a bitfield message since it has no pieces");
                }

                // (5). listen for messages
                while (!socket.isClosed()) {
                    int length = in.readInt();

                    if (length > 0) {
                        byte[] msgData = new byte[length + 4];
                        ByteBuffer.wrap(msgData).putInt(length);
                        in.readFully(msgData, 4, length);
                        Message msg = messageHandler.parseMessage(msgData);
                        printLog("Peer " + peerID + " received " + msg.getClass().getSimpleName() + " from peer " + peerConnectionId);
                        processMessage(peerConnectionId, msg);
                        printLog(msg.getClass().getSimpleName() + " processed successfully.");
                    }
                }
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

    public boolean hasData() {
        for (boolean b : hasChunks) if (b) return true;
        return false;
    }

    /**
     * Sends generic 'Message' (ex. 'PieceMessage', 'HaveMessage', ...)
     *
     * @param destPeerID destination peer sent to
     * @param message    to be sent
     */
    public void sendMessage(int destPeerID, Message message) {
        new Thread(() -> {
            try {
                DataOutputStream destOut = peerOutputs.get(destPeerID);

                if (destOut != null) {
                    byte[] data = message.serialize();

                    // 'synchronized' to ensure only one message written at a given time
                    synchronized (destOut) {
                        destOut.write(data);
                        destOut.flush();
                    }
                }

                printLog("Peer " + peerID + " sending " + message.getClass().getSimpleName() + " to Peer " + destPeerID);
            } catch (IOException e) {
                System.err.println("Error sending message: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Reads piece content into 'PieceMessage', which is sent to 'destPeerID'
     *
     * @param destPeerID destination peer which piece is sent to
     * @param pieceIndex used to index corresponding 'pieceData' from 'fileManager' and
     *                   to define a 'PieceMessage' message type
     */
    public void sendPiece(int destPeerID, int pieceIndex) {
        new Thread(() -> {
            DataOutputStream destOut = peerOutputs.get(destPeerID);

            if (destOut == null) {
                System.err.println("No connection found between Peer " + peerID + " and " + destPeerID);
            }

            byte[] pieceData = fileManager.getPiece(pieceIndex);
            if (pieceData == null) {
                System.err.println("Piece " + pieceIndex + " not found in Peer " + peerID);
                return;
            }

            // 'PieceMessage' defined by a 'pieceIndex' and a 'pieceData' (see 'Message.java')
            PieceMessage pieceMsg = new PieceMessage(pieceIndex, pieceData);
            sendMessage(destPeerID, pieceMsg);

            printLog("Peer " + peerID + " sent piece " + pieceIndex + " to Peer " + destPeerID);
        }).start();
    }


    /**
     * Performs the necessary logic upon receipt of each Message type
     * NEW ADDITIONS: Processing of each message type
     *
     * @param remotePeerID of peer from which 'msg' originates
     * @param msg          to be processed
     */
    private void processMessage(int remotePeerID, Message msg) {
        if (msg instanceof BitfieldMessage bitfieldMsg) {
            BitSet currBitfield = fileManager.getBitfield();
            BitSet remoteBitfield = bitfieldMsg.getBitfield();
            neighborBitfields.put(remotePeerID, remoteBitfield);

            BitSet interestingPieces = (BitSet) remoteBitfield.clone();
            interestingPieces.andNot(currBitfield);

            if (!interestingPieces.isEmpty()) {
                sendMessage(remotePeerID, new InterestedMessage());
                printLog("Peer " + peerID + " is interested in peer " + remotePeerID);
            } else {
                sendMessage(remotePeerID, new NotInterestedMessage());
                printLog("Peer " + peerID + " is NOT interested in peer " + remotePeerID);
            }

        } else if (msg instanceof RequestMessage reqMsg) {
            if (chokingManager != null) {
                boolean isUnchoked = chokingManager.isUnchoked(remotePeerID);

                // Ensures that the neighbor has unchoked this peer
                if (isUnchoked) {
                    sendPiece(remotePeerID, reqMsg.getPieceIndex());
                } else {
                    printLog("Peer " + peerID + " ignoring request from choked peer " + remotePeerID);
                }
            }

        } else if (msg instanceof PieceMessage pieceMsg) {
            byte[] payload = pieceMsg.getPayload();
            int pieceIndex = pieceMsg.getPieceIndex();

            printLog("Peer " + peerID + " received piece " + pieceIndex + " from peer " + remotePeerID);

            // Record download statistics
            if (chokingManager != null) {
                chokingManager.recordBytesDownloaded(remotePeerID, payload.length);
            }

            // Save piece (skip first 4 bytes which are the piece index)
            if (fileManager.savePiece(pieceIndex, Arrays.copyOfRange(payload, 4, payload.length))) {
                logger.logDownloadingPiece(peerID, remotePeerID, pieceIndex, fileManager.getNumPiecesOwned());
                printLog("Peer " + peerID + " now has " + fileManager.getNumPiecesOwned() + "/" +
                        fileManager.getNumPieces() + " pieces");

                // Check if download is complete
                if (fileManager.hasCompleteFile()) {
                    logger.logCompletionOfDownload(peerID);
                    printLog("Peer " + peerID + " COMPLETED DOWNLOAD!", true);

                    // Update choking manager
                    if (chokingManager != null) {
                        chokingManager.setHasCompleteFile(true);
                    }

                    // Send not interested to all neighbors if this peer has the complete file
                    for (int neighborID : sockets.keySet()) {
                        sendMessage(neighborID, new NotInterestedMessage());
                    }
                } else {
                    // Request new piece immediately
                    requestNextPiece(remotePeerID);
                    // Sends HaveMessage to all neighbors
                    HaveMessage haveMsg = new HaveMessage(pieceIndex);
                    for (int neighborID : sockets.keySet()) {
                        if (neighborID != remotePeerID) {
                            sendMessage(neighborID, haveMsg);
                        }
                    }
                }
            } else {
                printLog("Peer " + peerID + " failed to save piece " + pieceIndex);
            }

        } else if (msg instanceof HaveMessage haveMsg) {
            int pieceIndex = haveMsg.getPieceIndex();
            logger.logReceivingHave(peerID, remotePeerID, pieceIndex);

            // Update neighbor's bitfield
            BitSet neighborBitfield = neighborBitfields.get(remotePeerID);
            if (neighborBitfield != null) {
                neighborBitfield.set(pieceIndex);

                // Check if piece is needed
                BitSet currBitfield = fileManager.getBitfield();
                if (!currBitfield.get(pieceIndex) && !fileManager.hasCompleteFile()) {
                    // Send interested if we weren't before
                    sendMessage(remotePeerID, new InterestedMessage());

                    // If already unchoked, piece is requested via. the continuous downloader
                }
            }

        } else if (msg instanceof InterestedMessage) {
            logger.logReceivingInterested(peerID, remotePeerID);
            printLog("Peer " + peerID + " received INTERESTED from peer " + remotePeerID);

            // Mark as interested in choking manager
            if (chokingManager != null) {
                chokingManager.markInterested(remotePeerID);
            }

        } else if (msg instanceof NotInterestedMessage) {
            logger.logReceivingNotInterested(peerID, remotePeerID);
            printLog("Peer " + peerID + " received NOT INTERESTED from peer " + remotePeerID);

            // Mark as not interested in choking manager
            if (chokingManager != null) {
                chokingManager.markNotInterested(remotePeerID);
            }

        } else if (msg instanceof ChokeMessage) {
            logger.logChoking(peerID, remotePeerID);
            printLog("Peer " + peerID + " was CHOKED by peer " + remotePeerID, true);
            markAsChoked(remotePeerID);

        } else if (msg instanceof UnchokeMessage) {
            logger.logUnchoking(peerID, remotePeerID);
            printLog("Peer " + peerID + " was UNCHOKED by peer " + remotePeerID, true);
            markAsUnchoked(remotePeerID);

        }
    }

    public void shutdown() {
        if (chokingManager != null) {
            chokingManager.stop();
        }
        stopContinuousDownload();
        for (Socket socket : sockets.values()) {
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("Error closing socket: " + e.getMessage());
            }
        }
        if (logger != null) {
            logger.close();
        }
    }
}

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

