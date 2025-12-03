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
 *   - Added termination logic mem vars:
 *      - 'peerCompletionStatus': Hashmap tracking which peers have completed downloading
 *      - 'totalPeers': total num of peers in the P2P network
 *      - 'terminationLock': Object used for thread-safety
 *      - 'doTermination': Global flag
 *   - Added methods:
 *      - 'setTotalPeers()': sets 'totalPeers' for tracking (called in Torrent.java)
 *      - 'registerForTermination()': add peer to 'peerCompletionStatus' map with initial completion status (called in Torrent.java)
 *      - 'markPeerAsDone()': marks a peer as done downloading in 'peerCompletionStatus' map
 *      - 'checkTermination()': checks if ALL peers done
 *      - 'waitForTermination()': blocks and rechecks 'doTermination' condition every 5 sec (called in Torrent.java)
 *      - 'hasFile()': returns whether peer initially has the complete file
 *   - updated 'processMessage()' to detect peer completion upon Bitfield- and HaveMessage receipt
 *   - Removed 'connectToTracker()'
 */
public class Peer {
    int peerID;
    String hostname;
    int port;
    boolean hasFile;                // track whether peer initially has Complete File
    CommonConfig config;
    boolean[] hasChunks;
    FileManager fileManager;
    MessageHandler messageHandler;
    Logger logger;
    String filepath;
    ArrayList<Peer> neighbors;
    ChokingManager chokingManager;
    private ScheduledExecutorService downloadScheduler;

    Map<Integer, BitSet> neighborBitfields = new HashMap<>();

    int line_counter;   // TEMP (for cleaner command line outputs)

    Map<Integer, DataOutputStream> peerOutputs = new ConcurrentHashMap<>();
    Map<Integer, Socket> sockets = new ConcurrentHashMap<>();

    // New set to locally store which peers are unchoking this peer to request pieces
    private final Set<Integer> unchokedByPeers = Collections.synchronizedSet(new HashSet<>());

    /** NEW (Termination Tracking) */
    private int totalPeers = 0;
    Map<Integer, Boolean> peerCompletionStatus = new ConcurrentHashMap<>();     // tracking which peers have Complete File -> ( peerID, hasCompleteDownload ) pairs
    Object terminationLock = new Object();                                      // for thread synchronization
    volatile boolean doTermination = false;                                     // flag to kill all peers (all threads) -> 'volatile' guarantees changing
                                                                                // the value of 'doTermination' is visible to ALL threads immediately

    private volatile boolean allPeersDone = false;
    private Set<Integer> peersWhoAreDone = Collections.synchronizedSet(new HashSet<>());

    public Peer(int peerID, String hostname, int port, boolean hasFile, CommonConfig config) {
        this.peerID = peerID;
        this.hostname = hostname;
        this.port = port;
        this.hasFile = hasFile;
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

    // helper methods for clean command line outputs
    private synchronized void printLog(String msg) {
        line_counter++;
        System.out.println("[" + line_counter + "] " + msg);
    }

    private synchronized void printLog(String msg, boolean newline) {
        line_counter++;
        System.out.println("\n[" + line_counter + "] " + msg);
    }

    public String getHostname() { return hostname; }
    public int getPort() { return port; }
    public int getPeerID() { return peerID; }
    public boolean[] getHasChunks() { return hasChunks; }
    public boolean hasFile() { return hasFile; }

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

    /** NEW (Termination Tracking) */

    /**
     * Sets the total number of peers in the P2P network
     * @param total number of peers
     */
    public void setTotalPeers(int total) {
        this.totalPeers = total;
    }

    /**
     * Adds a peer to "ready for termination" map
     * @param remotePeerID of the peer to register
     * @param initiallyHasFile indicating whether 'remotePeerID' starts with the complete file
     */
    public void registerForTermination(int remotePeerID, boolean initiallyHasFile) {
        peerCompletionStatus.put(remotePeerID, initiallyHasFile);
    }

    /**
     * Marks a peer as having a complete file download
     * @param remotePeerID of peer done downloading
     */

    public void markPeerAsDone(int remotePeerID) {
        Boolean prevStatus = peerCompletionStatus.put(remotePeerID, true);
        if (prevStatus == null || !prevStatus) {
            printLog("Peer " + remotePeerID + " marked as DONE DOWNLOADING");
            checkTermination();
        }
    }

    /**
     * Check if all peers in the P2P network are done downloading
     */

    private void checkTermination() {
        // if not all peers "discovered" then clearly we have not met termination condition
        if (peerCompletionStatus.size() != totalPeers) {
            return;
        }

        // check if all peers done downloading
        boolean allPeersDone = true;
        for (Boolean done : peerCompletionStatus.values()) {
            if (!done) {
                allPeersDone = false;
                break;
            }
        }

        if (allPeersDone) {
            printLog("ALL PEERS DONE DOWNLOADING - Terminating...");
            synchronized (terminationLock) {        // -> only one thread may execute at a given time
                doTermination = true;               // notifies all peers in network
                terminationLock.notifyAll();
            }
        }
    }

    /**
     * Blocks until all peers have completed downloading
     */

    public void waitForTermination() {
        synchronized (terminationLock) {
            while (!doTermination) {
                try {
                    terminationLock.wait(5000); // check every 5 sec

                    // if no notification after 5 sec then wake thread up
                    // in case notification missed
                    if (!doTermination) {
                        checkTermination();
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    /**
     * Determines whether current peer has all pieces (i.e. done downloading)
     */
    private boolean hasAllPieces(BitSet bitfield, int numPieces) {
        return bitfield.cardinality() == numPieces;
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

    /**
     * Returns the available peers in the tracker while also adding the new peer to the tracker
     */
//    public String connectToTracker() {
//        printLog(peerID + " is attempting to connect to tracker. . .");
//        String availablePeers = "";
//        try {
//            Socket client = new Socket("localhost", 8080);
//
//            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
//            PrintWriter out = new PrintWriter(client.getOutputStream());
//
//            availablePeers = in.readLine();
//
//            out.println("ANNOUNCE " + peerID + " " + hostname + " " + port);
//            out.flush();
//
//            client.close();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//
//        return availablePeers;
//    }

    // Each peer must have its own server, so we create a it here and call it when needed
    // Use threads so it doesnt take forever
    public void start() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) { // Starts listening on port
                printLog("Peer " + peerID + " is listening on port " + port);

                // Starts the choking manager and downloader thread
                chokingManager.start();
                startContinuousDownload();

                while (!doTermination) {
                    try {
                        serverSocket.setSoTimeout(1000);        // check termination condition every second
                        Socket socket = serverSocket.accept();  // accept connection if found
                        new Thread(() -> handleIncomingConnection(socket)).start();
                    } catch (SocketTimeoutException e) {
                        // timeout -> (!doTermination) check again
                    }
                }
            } catch (IOException e) {
                if (!doTermination) {
                    e.printStackTrace();
                }
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
            printLog("Peer " + peerID + " received handshake from peer " + remotePeerID, true);

            // (3). send handshake response
            HandshakeMessage sendHandshake = new HandshakeMessage(peerID);
            out.write(sendHandshake.serialize());   // transfer byte array representation to output stream
            out.flush();                            // send immediately

            sockets.put(remotePeerID, socket);
            peerOutputs.put(remotePeerID, out);

            // Starts the choking manager
            chokingManager.registerNeighbor(remotePeerID);

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
            while (!socket.isClosed() && !doTermination) {
                try {
                    int length = in.readInt();  // if connection closes after entering the loop 'readInt()' throws an EOFException
                    if (length > 0) {
                        byte[] msgData = new byte[length + 4];  // payload ('length' bytes) + length (4 bytes)
                        ByteBuffer.wrap(msgData).putInt(length);
                        in.readFully(msgData, 4, length);

                        Message msg = messageHandler.parseMessage(msgData);
                        printLog("Peer " + peerID + " received " + msg.getClass().getSimpleName() + " from peer " + remotePeerID);

                        processMessage(remotePeerID, msg);
                        printLog(msg.getClass().getSimpleName() + " processed successfully.");
                    }
                } catch (EOFException e) {
                    break;
                }
            }
        } catch (IOException e) {
            if (!doTermination) {
                System.err.println("Connection error: " + e.getMessage());
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid handshake message: " + e.getMessage());
        }
    }

    //Each peer must also act as a client to connect other peer servers
    public void establishConnection(int peerConnectionId, String serverPeerHost, int serverPeerPort) {
        new Thread(() -> {
            try { // Connects to other Peer's server
                printLog("Peer " + peerID + " attempting to connect to Peer " + peerConnectionId);
                System.out.println(serverPeerHost);
                System.out.println(serverPeerPort);
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
                while (!socket.isClosed() && !doTermination) {
                    try {
                        int length = in.readInt();  // if connection closes after entering the loop 'readInt()' throws an EOFException
                        if (length > 0) {
                            byte[] msgData = new byte[length + 4];
                            ByteBuffer.wrap(msgData).putInt(length);
                            in.readFully(msgData, 4, length);

                            Message msg = messageHandler.parseMessage(msgData);
                            printLog("Peer " + peerID + " received " + msg.getClass().getSimpleName() + " from peer " + peerConnectionId);

                            processMessage(peerConnectionId, msg);
                            printLog(msg.getClass().getSimpleName() + " processed successfully.");
                        }
                    } catch (EOFException e) {
                        break;
                    }
                }
            } catch (IOException e) {
                if (!doTermination) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    /**
     * Returns a list of the piece indexes that the inputted peer has that the given peer needs
     */
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
     * @param remotePeerID of peer from which 'msg' originates
     * @param msg          to be processed
     */
    private void processMessage(int remotePeerID, Message msg) {
        if (msg instanceof BitfieldMessage bitfieldMsg) {
            BitSet currBitfield = fileManager.getBitfield();
            BitSet remoteBitfield = bitfieldMsg.getBitfield();
            neighborBitfields.put(remotePeerID, remoteBitfield);

            /** NEW (Termination Logic) */
            // check if remote peer has all pieces ( before we connected to it )
            if (hasAllPieces(remoteBitfield, fileManager.getNumPieces())) {
                markPeerAsDone(remotePeerID);
            }

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

                // Sends HaveMessage to all neighbors
                HaveMessage haveMsg = new HaveMessage(pieceIndex);
                for (int neighborID : sockets.keySet()) {
//                    if (neighborID != remotePeerID) {
//                        sendMessage(neighborID, haveMsg);
//                    }
                    sendMessage(neighborID, haveMsg);
                }

                // Check if download is complete
                if (fileManager.hasCompleteFile()) {
                    logger.logCompletionOfDownload(peerID);
                    printLog("Peer " + peerID + " COMPLETED DOWNLOAD!", true);

                    /** NEW (Termination Logic) */
                    markPeerAsDone(peerID);

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

                    // MOVED!
//                    // Sends HaveMessage to all neighbors
//                    HaveMessage haveMsg = new HaveMessage(pieceIndex);
//                    for (int neighborID : sockets.keySet()) {
//                        if (neighborID != remotePeerID) {
//                            sendMessage(neighborID, haveMsg);
//                        }
//                    }
                }
            } else {
                printLog("Peer " + peerID + " failed to save piece " + pieceIndex);
            }

        } else if (msg instanceof HaveMessage haveMsg) {
            int pieceIndex = haveMsg.getPieceIndex();
            logger.logReceivingHave(peerID, remotePeerID, pieceIndex);

            // Update neighbor's bitfield
            // if peer didn't send a BitfieldMessage then create one
            BitSet neighborBitfield = neighborBitfields.get(remotePeerID);
            if (neighborBitfield == null) {
                neighborBitfield = new BitSet(fileManager.getNumPieces());
                neighborBitfields.put(remotePeerID, neighborBitfield);
            }

            // o.w. peer has already sent BitfieldMessage
            neighborBitfield.set(pieceIndex);

            /** NEW (Termination Logic) */
            // check if remote peer has all pieces ( after some recent acquisition  )
            if (hasAllPieces(neighborBitfield, fileManager.getNumPieces())) {
                markPeerAsDone(remotePeerID);
            }

            // Check if piece is needed
            BitSet currBitfield = fileManager.getBitfield();
            if (!currBitfield.get(pieceIndex) && !fileManager.hasCompleteFile()) {
                // Send interested if we weren't before
                sendMessage(remotePeerID, new InterestedMessage());

                // If already unchoked, piece is requested via. the continuous downloader
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

            requestNextPiece(remotePeerID); // ADDED
        }
        else if (msg instanceof TerminateMessage) {
            markPeerAsDone(remotePeerID);
        }
    }

    public void shutdown() {
        /** NEW (Termination Logic) */
        doTermination = true;

        synchronized (terminationLock) {
            terminationLock.notifyAll();
        }

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

    /*
    // 2. When a peer finishes downloading
    public void onDownloadComplete() {
        printLog("Download complete - notifying all peers");
        markPeerAsDone(peerID);
        broadcastTerminateMessage();
    }

    private void broadcastTerminateMessage() {
        for (Integer remotePeerID : sockets.keySet()) {
            sendMessage(remotePeerID, new TerminateMessage());
        }
    }

    // 3. When receiving TerminateMessage from remote peer
    public void handleTerminateMessage(int remotePeerID) {
        boolean isNewInfo = markPeerAsDone(remotePeerID);

        // Only propagate if this is NEW information
        if (isNewInfo) {
            broadcastTerminateMessage();
        }
    }

    // 4. Updated markPeerAsDone - return whether this was new info
    public boolean markPeerAsDone(int remotePeerID) {
        boolean isNewInfo = peersWhoAreDone.add(remotePeerID);

        if (isNewInfo) {
            printLog("Peer " + remotePeerID + " marked as DONE DOWNLOADING ("
                    + peersWhoAreDone.size() + "/" + totalPeers + ")");
            checkTermination();
        }

        return isNewInfo;  // Return whether this was actually new
    }

    // 5. Updated checkTermination - actually trigger shutdown
    private void checkTermination() {
        if (peersWhoAreDone.size() == totalPeers) {
            printLog("ALL PEERS DONE DOWNLOADING - Terminating...");
            synchronized (terminationLock) {
                allPeersDone = true;
                terminationLock.notifyAll();
            }

            // Actually shut down after a brief delay
            new Thread(() -> {
                try {
                    Thread.sleep(2000);  // Give time for final messages
                    shutdown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }
    // 6. Updated waitForTermination
    public void waitForTermination() {
        synchronized (terminationLock) {
            while (!allPeersDone) {
                try {
                    terminationLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
    */

}
