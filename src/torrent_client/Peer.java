package torrent_client;

import java.nio.ByteBuffer;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Represents a Peer in the P2P network. This class is responsible for managing connections, piece requests, and the
 * message protocol loop.
 */
public class Peer {

    // identity and config
    private final int peerID;
    private final String hostname;
    private final int port;
    private final boolean hasFile;
    private final CommonConfig config;
    private final String filepath;

    // components and logic
    private final FileManager fileManager;
    private final MessageHandler messageHandler;
    private final ChokingManager chokingManager;
    private final Logger logger;

    // networking and state
    private final Map<Integer, Socket> sockets = new ConcurrentHashMap<>();
    private final Map<Integer, DataOutputStream> peerOutputs = new ConcurrentHashMap<>();
    private final Map<Integer, BitSet> neighborBitfields = new ConcurrentHashMap<>();
    private final Set<Integer> unchokedByPeers = Collections.synchronizedSet(new HashSet<>());

    // synchronization and termination
    private final Map<Integer, Boolean> peerCompletionStatus = new ConcurrentHashMap<>();
    private final Object terminationLock = new Object();
    private volatile boolean doTermination = false;
    private int totalPeers = 0;
    private int line_counter = 0;

    // background tasks
    private ScheduledExecutorService downloadScheduler;

    public Peer(int peerID, String hostname, int port, boolean hasFile, CommonConfig config) {
        this.peerID = peerID;
        this.hostname = hostname;
        this.port = port;
        this.hasFile = hasFile;
        this.config = config;
        this.fileManager = new FileManager(peerID, config, hasFile);
        this.messageHandler = new MessageHandler(config);
        this.filepath = "src/project_config_file_large/" + peerID + "/";

        try {
            this.logger = new Logger(peerID);
        } catch (IOException e) {
            System.err.println("Error initializing logger: " + e.getMessage());
            throw new RuntimeException(e);
        }

        this.chokingManager = new ChokingManager(this, config, logger);
    }

    /**
     * Initializes the server socket to listen for incoming peers and starts the choking and download management threads.
     */
    public void start() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                printLog("Peer " + peerID + " is listening on port " + port);

                chokingManager.start();
                startContinuousDownload();

                while (!doTermination) {
                    try {
                        serverSocket.setSoTimeout(1000);
                        Socket socket = serverSocket.accept();
                        new Thread(() -> handleIncomingConnection(socket)).start();
                    } catch (SocketTimeoutException e) {
                        // check (!doTermination) loop condition
                    }
                }
            } catch (IOException e) {
                if (!doTermination) e.printStackTrace();
            }
        }).start();
    }

    /**
     * Actively connects to a remote peer and initiates the handshake.
     * @param peerConnectionId Target peer ID.
     * @param serverPeerHost Target hostname.
     * @param serverPeerPort Target port.
     */
    public void establishConnection(int peerConnectionId, String serverPeerHost, int serverPeerPort) {
        new Thread(() -> {
            try {
                printLog("Peer " + peerID + " attempting to connect to Peer " + peerConnectionId);
                Socket socket = new Socket(serverPeerHost, serverPeerPort);

                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream());

                // 1. send handshake
                out.write(new HandshakeMessage(peerID).serialize());
                out.flush();

                // 4. receive handshake response, init ChokingManager, send bitfield
                byte[] handshakeBytes = new byte[32];
                in.readFully(handshakeBytes);
                HandshakeMessage rcvHandshake = HandshakeMessage.parse(handshakeBytes);

                if (rcvHandshake.getPeerID() != peerConnectionId) {
                    socket.close();
                    return;
                }

                printLog("Peer " + peerID + " established a connection with Peer " + peerConnectionId);
                setupPeerSession(peerConnectionId, socket, out);

                // 5. listening loop
                runMessageLoop(peerConnectionId, in, socket);

            } catch (IOException e) {
                if (!doTermination) e.printStackTrace();
            }
        }).start();
    }

    /**
     * Handles the handshake and protocol initialization for a peer connecting to us.
     * @param socket The incoming connection socket.
     */
    public void handleIncomingConnection(Socket socket) {
        try {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 2. receive handshake
            byte[] handshakeBytes = new byte[32];
            in.readFully(handshakeBytes);
            HandshakeMessage rcvHandshake = HandshakeMessage.parse(handshakeBytes);
            int remotePeerID = rcvHandshake.getPeerID();

            printLog("Peer " + peerID + " is now connected with Peer " + remotePeerID);
            out.write(new HandshakeMessage(peerID).serialize());
            out.flush();

            // 3. send handshake response, init ChokingManager, send bitfield
            setupPeerSession(remotePeerID, socket, out);

            // 6. listening loop
            runMessageLoop(remotePeerID, in, socket);

        } catch (IOException e) {
            if (!doTermination) System.err.println("Connection error: " + e.getMessage());
        }
    }

    /**
     * Helper to register peer state and send the initial bitfield.
     */
    private void setupPeerSession(int remoteID, Socket socket, DataOutputStream out) throws IOException {
        sockets.put(remoteID, socket);
        peerOutputs.put(remoteID, out);
        chokingManager.registerNeighbor(remoteID);
        logger.logTCPConnection(peerID, remoteID);

        if (fileManager.getNumPiecesOwned() > 0) {
            sendMessage(remoteID, new BitfieldMessage(fileManager.getBitfield(), fileManager.getNumPieces()));
        }
    }

    /**
     * The core message processing loop for an established connection.
     */
    private void runMessageLoop(int remoteID, DataInputStream in, Socket socket) {
        while (!socket.isClosed() && !doTermination) {
            try {
                int length = in.readInt();
                if (length > 0) {
                    byte[] msgData = new byte[length + 4];
                    ByteBuffer.wrap(msgData).putInt(length);
                    in.readFully(msgData, 4, length);

                    Message msg = messageHandler.parseMessage(msgData);
                    processMessage(remoteID, msg);
                }
            } catch (EOFException e) {
                break;
            } catch (IOException e) {
                if (!doTermination) break;
            }
        }
    }

    /**
     * Dispatcher logic that reacts to specific message types.
     * @param remotePeerID Originating peer.
     * @param msg The received message.
     */
    private void processMessage(int remotePeerID, Message msg) {
        if (msg instanceof BitfieldMessage bitfieldMsg) {
            handleBitfield(remotePeerID, bitfieldMsg);
        } else if (msg instanceof RequestMessage reqMsg) {
            handleRequest(remotePeerID, reqMsg);
        } else if (msg instanceof PieceMessage pieceMsg) {
            handlePiece(remotePeerID, pieceMsg);
        } else if (msg instanceof HaveMessage haveMsg) {
            handleHave(remotePeerID, haveMsg);
        } else if (msg instanceof InterestedMessage) {
            printLog("Peer " + peerID + " received an INTERESTED message from " + remotePeerID);
            logger.logReceivingInterested(peerID, remotePeerID);
            chokingManager.markInterested(remotePeerID);
        } else if (msg instanceof NotInterestedMessage) {
            printLog("Peer " + peerID + "received a NOT_INTERESTED message from " + remotePeerID);
            logger.logReceivingNotInterested(peerID, remotePeerID);
            chokingManager.markNotInterested(remotePeerID);
        } else if (msg instanceof ChokeMessage) {
            printLog("Peer" + peerID + " is CHOKED by Peer " + remotePeerID);
            logger.logChoking(peerID, remotePeerID);
            markAsChoked(remotePeerID);
        } else if (msg instanceof UnchokeMessage) {
            printLog("Peer " + peerID + " is UNCHOKED by Peer " + remotePeerID);
            logger.logUnchoking(peerID, remotePeerID);
            markAsUnchoked(remotePeerID);
            requestNextPiece(remotePeerID);
        }
    }

    /** Message Processing Helper Methods */

    private void handleBitfield(int remoteID, BitfieldMessage msg) {
        BitSet remoteBitfield = msg.getBitfield();
        neighborBitfields.put(remoteID, remoteBitfield);

        if (hasAllPieces(remoteBitfield, fileManager.getNumPieces())) {
            markPeerAsDone(remoteID);
        }

        BitSet interestingPieces = (BitSet) remoteBitfield.clone();
        interestingPieces.andNot(fileManager.getBitfield());

        if (!interestingPieces.isEmpty()) {
            sendMessage(remoteID, new InterestedMessage());
        } else {
            sendMessage(remoteID, new NotInterestedMessage());
        }
    }

    private void handleRequest(int remoteID, RequestMessage msg) {
        if (chokingManager != null && chokingManager.isUnchoked(remoteID)) {
            sendPiece(remoteID, msg.getPieceIndex());
        }
    }

    private void handlePiece(int remoteID, PieceMessage msg) {
        byte[] payload = msg.getPayload();
        int pieceIndex = msg.getPieceIndex();

        printLog("Peer " + peerID + " received piece " + pieceIndex + " from Peer " + remoteID + ". (" +
                fileManager.getNumPiecesOwned() + " / " + fileManager.getNumPieces() + " pieces)");

        if (chokingManager != null) {
            chokingManager.recordBytesDownloaded(remoteID, payload.length);
        }

        if (fileManager.savePiece(pieceIndex, Arrays.copyOfRange(payload, 4, payload.length))) {
            logger.logDownloadingPiece(peerID, remoteID, pieceIndex, fileManager.getNumPiecesOwned());

            // Broadcast 'Have' to all
            HaveMessage have = new HaveMessage(pieceIndex);
            sockets.keySet().forEach(id -> sendMessage(id, have));

            if (fileManager.hasCompleteFile()) {
                finalizeDownload();
            } else {
                requestNextPiece(remoteID);
            }
        }
    }

    private void handleHave(int remoteID, HaveMessage msg) {
        int idx = msg.getPieceIndex();

        printLog("Peer " + peerID + " received a HAVE message for piece " + idx + " from Peer " + remoteID);
        logger.logReceivingHave(peerID, remoteID, idx);

        BitSet bitfield = neighborBitfields.computeIfAbsent(remoteID, k -> new BitSet(fileManager.getNumPieces()));
        bitfield.set(idx);

        if (hasAllPieces(bitfield, fileManager.getNumPieces())) {
            markPeerAsDone(remoteID);
        }

        if (!fileManager.getBitfield().get(idx) && !fileManager.hasCompleteFile()) {
            sendMessage(remoteID, new InterestedMessage());
        }
    }

    private void finalizeDownload() {
        logger.logCompletionOfDownload(peerID);
        printLog("Peer " + peerID + " has downloaded the complete file!", true);
        markPeerAsDone(peerID);

        if (chokingManager != null) chokingManager.setHasCompleteFile(true);

        sockets.keySet().forEach(id -> sendMessage(id, new NotInterestedMessage()));
    }

    private void markAsUnchoked(int peerID) {
        unchokedByPeers.add(peerID);
    }

    private void markAsChoked(int peerID) {
        unchokedByPeers.remove(peerID);
    }

    private boolean isUnchokedBy(int peerID) {
        return unchokedByPeers.contains(peerID);
    }

    /** Termination Helper Methods */

    public void setTotalPeers(int total) {
        this.totalPeers = total;
    }

    public void registerForTermination(int remotePeerID, boolean initiallyHasFile) {
        peerCompletionStatus.put(remotePeerID, initiallyHasFile);
    }

    public void markPeerAsDone(int remotePeerID) {
        if (peerCompletionStatus.replace(remotePeerID, false, true) || !peerCompletionStatus.containsKey(remotePeerID)) {
            peerCompletionStatus.put(remotePeerID, true);
            checkTermination();
        }
    }

    private void checkTermination() {
        if (peerCompletionStatus.size() == totalPeers && !peerCompletionStatus.containsValue(false)) {
            printLog("ALL PEERS DONE DOWNLOADING - Terminating...");
            synchronized (terminationLock) {
                doTermination = true;
                terminationLock.notifyAll();
            }
        }
    }

    public void waitForTermination() {
        synchronized (terminationLock) {
            while (!doTermination) {
                try {
                    terminationLock.wait(5000);
                    checkTermination();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /** Lifecycle Helper Methods */

    private boolean hasAllPieces(BitSet bitfield, int numPieces) {
        return bitfield.cardinality() == numPieces;
    }

    private void requestNextPiece(int remotePeerID) {
        if (fileManager.hasCompleteFile() || !isUnchokedBy(remotePeerID)) return;

        BitSet neighborBitfield = neighborBitfields.get(remotePeerID);
        if (neighborBitfield == null) return;

        int pieceToRequest = fileManager.randomSelection(neighborBitfield);
        if (pieceToRequest >= 0 && fileManager.markAsRequested(pieceToRequest)) {
            sendMessage(remotePeerID, new RequestMessage(pieceToRequest));
        }
    }

    private void startContinuousDownload() {
        downloadScheduler = Executors.newScheduledThreadPool(1);
        downloadScheduler.scheduleAtFixedRate(() -> {
            try {
                if (!fileManager.hasCompleteFile()) {
                    unchokedByPeers.parallelStream().forEach(this::requestNextPiece);
                }
            } catch (Exception e) {
                System.err.println("Download scheduler error: " + e.getMessage());
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    private void stopContinuousDownload() {
        if (downloadScheduler != null) {
            downloadScheduler.shutdownNow();
        }
    }

    public void sendMessage(int destPeerID, Message message) {
        CompletableFuture.runAsync(() -> {
            try {
                DataOutputStream destOut = peerOutputs.get(destPeerID);

                if (destOut != null) {
                    byte[] data = message.serialize();

                    synchronized (destOut) {
                        destOut.write(data);
                        destOut.flush();
                    }
                }
            } catch (IOException e) {
                System.err.println("Error sending message to " + destPeerID + ": " + e.getMessage());
            }
        });
    }

    public void sendPiece(int destPeerID, int pieceIndex) {
        byte[] pieceData = fileManager.getPiece(pieceIndex);
        if (pieceData != null) {
            sendMessage(destPeerID, new PieceMessage(pieceIndex, pieceData));
        }
    }

    public void shutdown() {
        doTermination = true;
        synchronized (terminationLock) {
            terminationLock.notifyAll();
        }

        if (chokingManager != null) chokingManager.stop();

        stopContinuousDownload();
        sockets.values().forEach(s -> {
            try { s.close(); } catch (IOException ignored) {}
        });

        if (logger != null) logger.close();
    }

    private synchronized void printLog(String msg) {
        System.out.println("[" + (++line_counter) + "] " + msg);
    }

    private synchronized void printLog(String msg, boolean newline) {
        if (newline) System.out.println();
        printLog(msg);
    }

    public String getHostname() { return hostname; }
    public int getPort() { return port; }
    public int getPeerID() { return peerID; }
    public boolean hasFile() { return hasFile; }
}