package torrent_client;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * CHOKING MANAGER
 * Responsible for the choking and unchoking logic
 * Creates optimistically unchoked neighbor
 * Tracks download rated to limit leeching
 */
public class ChokingManager {
    private final int localPeerID;
    private final Peer localPeer;
    private final CommonConfig config;
    private final Logger logger;
    private final Set<Integer> preferredNeighbors = Collections.synchronizedSet(new HashSet<>());
    private Integer optimisticallyUnchokedNeighbor = null;
    private final Set<Integer> interestedNeighbors = Collections.synchronizedSet(new HashSet<>());
    private final Set<Integer> allConnectedNeighbors = Collections.synchronizedSet(new HashSet<>());
    private final Map<Integer, Long> downloadRates = new ConcurrentHashMap<>();
    private final Map<Integer, Long> bytesDownloadedThisInterval = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler; //Creates timings 'p' and 'm' for choking and optimistic choking intervals
    private final Random random = new Random();
    private boolean hasCompleteFile = false;

    public ChokingManager(Peer localPeer, CommonConfig config, Logger logger) {
        this.localPeer = localPeer;
        this.localPeerID = localPeer.getPeerID();
        this.config = config;
        this.logger = logger;
    }

    /**
     * Start the periodic unchoking tasks
     */
    public void start() {
        scheduler = Executors.newScheduledThreadPool(2);
        // Initializes with immediate call of choke selection
        scheduler.execute(this::selectPreferredNeighbors);
        scheduler.execute(this::selectOptimisticallyUnchokedNeighbor);
        // Schedule preferred neighbor selection every p seconds
        scheduler.scheduleAtFixedRate(
                this::selectPreferredNeighbors,
                config.getUnchokingInterval(),
                config.getUnchokingInterval(),
                TimeUnit.SECONDS
        );

        // Schedule optimistic unchoking every m seconds
        scheduler.scheduleAtFixedRate(
                this::selectOptimisticallyUnchokedNeighbor,
                config.getOptUnchokingInterval(),
                config.getOptUnchokingInterval(),
                TimeUnit.SECONDS
        );
    }

    /**
     * Stop the choking manager
     */
    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
        }
    }

    /**
     * Register a new neighbor connection
     * Creates instance in download rate maps to initialize rate tracking
     */
    public synchronized void registerNeighbor(int neighborPeerID) {
        allConnectedNeighbors.add(neighborPeerID);
        downloadRates.put(neighborPeerID, 0L);
        bytesDownloadedThisInterval.put(neighborPeerID, 0L);
    }

    /**
     * Record that a neighbor is interested in our data
     */
    public synchronized void markInterested(int neighborPeerID) {
        interestedNeighbors.add(neighborPeerID);
    }

    /**
     * Record that a neighbor is not interested in our data
     */
    public synchronized void markNotInterested(int neighborPeerID) {
        interestedNeighbors.remove(neighborPeerID);
    }

    /**
     * Record bytes downloaded from a neighbor and add to map
     */
    public void recordBytesDownloaded(int neighborPeerID, int bytes) {
        bytesDownloadedThisInterval.merge(neighborPeerID, (long) bytes, Long::sum);
    }

    /**
     * Update complete file status
     */
    public void setHasCompleteFile(boolean hasCompleteFile) {
        this.hasCompleteFile = hasCompleteFile;

    }

    /**
     * Check if a neighbor is unchoked
     */
    public synchronized boolean isUnchoked(int neighborPeerID) {
        boolean isPreferred = preferredNeighbors.contains(neighborPeerID);
        boolean isOptUnchoked = (optimisticallyUnchokedNeighbor != null &&
                optimisticallyUnchokedNeighbor == neighborPeerID);
        return isPreferred || isOptUnchoked;
    }

    /**
     * Select k preferred neighbors based on download rates
     */
    private synchronized void selectPreferredNeighbors() {
        try {

            // Redoes the download rates list
            updateDownloadRates();
            Set<Integer> previousPreferred = new HashSet<>(preferredNeighbors);
            preferredNeighbors.clear();
            int k = config.getNumPrefNeighbors();
            List<Integer> candidates = new ArrayList<>(interestedNeighbors);

            if (candidates.isEmpty()) {
                return;
            }

            // If peer has complete file, select randomly
            if (hasCompleteFile) {
                Collections.shuffle(candidates, random);
                List<Integer> selected = candidates.stream()
                        .limit(k)
                        .collect(Collectors.toList());
                preferredNeighbors.addAll(selected);
            } else {

                // Select top k by download rate
                List<Integer> topNeighbors = candidates.stream()
                        .sorted((a, b) -> {
                            long rateA = downloadRates.getOrDefault(a, 0L);
                            long rateB = downloadRates.getOrDefault(b, 0L);
                            if (rateA == rateB) {
                                // If the same, pick randomly
                                return random.nextBoolean() ? 1 : -1;
                            }
                            return Long.compare(rateB, rateA); // Descending order
                        })
                        .limit(k)
                        .collect(Collectors.toList());

                preferredNeighbors.addAll(topNeighbors);
            }

            // Send unchoke messages to newly preferred neighbors
            for (int peerID : preferredNeighbors) {
                if (!previousPreferred.contains(peerID)) {
                    sendUnchokeMessage(peerID);
                }
            }

            // Send choke messages to previously preferred neighbors that are no longer preferred and are not
            // optimistically unchoked
            for (int peerID : previousPreferred) {
                if (!preferredNeighbors.contains(peerID) &&
                        (optimisticallyUnchokedNeighbor == null || optimisticallyUnchokedNeighbor != peerID)) {
                    sendChokeMessage(peerID);
                }
            }

            // Log the change
            if (!preferredNeighbors.isEmpty()) {
                List<Integer> neighborsList = new ArrayList<>(preferredNeighbors);
                Collections.sort(neighborsList);
                logger.logChangeOfPrefNeighbors(localPeerID, neighborsList);
            }

        } catch (Exception e) {
            System.err.println("Error in selectPreferredNeighbors: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Select one random optimistically unchoked neighbor
     */
    private synchronized void selectOptimisticallyUnchokedNeighbor() {
        try {

            Integer previousOptUnchokedNeighbor = optimisticallyUnchokedNeighbor;

            // Find all choked neighbors that are interested
            List<Integer> chokedInterestedNeighbors = interestedNeighbors.stream()
                    .filter(peerID -> !preferredNeighbors.contains(peerID))
                    .collect(Collectors.toList());

            // If no possible neighbor, don't select any
            if (chokedInterestedNeighbors.isEmpty()) {
                optimisticallyUnchokedNeighbor = null;
                return;
            }

            // Randomly select one and send unchoke message
            int selectedIndex = random.nextInt(chokedInterestedNeighbors.size());
            optimisticallyUnchokedNeighbor = chokedInterestedNeighbors.get(selectedIndex);
            sendUnchokeMessage(optimisticallyUnchokedNeighbor);

            // Choke the previous optimistically unchoked neighbor if it's not a preferred neighbor
            if (previousOptUnchokedNeighbor != null &&
                    !previousOptUnchokedNeighbor.equals(optimisticallyUnchokedNeighbor) &&
                    !preferredNeighbors.contains(previousOptUnchokedNeighbor)) {
                sendChokeMessage(previousOptUnchokedNeighbor);
            }
            logger.logChangeOfOptUnchokedNeighbor(localPeerID, optimisticallyUnchokedNeighbor);

        } catch (Exception e) {
            System.err.println("Error in selectOptimisticallyUnchokedNeighbor: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Update download rates based on bytes downloaded this interval
     */
    private void updateDownloadRates() {
        for (Map.Entry<Integer, Long> entry : bytesDownloadedThisInterval.entrySet()) {
            downloadRates.put(entry.getKey(), entry.getValue());
        }
        // Reset for next interval
        bytesDownloadedThisInterval.clear();
    }

    private void sendUnchokeMessage(int neighborPeerID) {
        try {
            Message unchokeMsg = new UnchokeMessage();
            localPeer.sendMessage(neighborPeerID, unchokeMsg);
        } catch (Exception e) {
            System.err.println("Error sending unchoke message to peer " + neighborPeerID + ": " + e.getMessage());
        }
    }

    private void sendChokeMessage(int neighborPeerID) {
        try {
            Message chokeMsg = new ChokeMessage();
            localPeer.sendMessage(neighborPeerID, chokeMsg);
        } catch (Exception e) {
            System.err.println("Error sending choke message to peer " + neighborPeerID + ": " + e.getMessage());
        }
    }

    /**
     * Previously used for testing, may not be needed anymore
     */

    public synchronized Set<Integer> getPreferredNeighbors() {
        return new HashSet<>(preferredNeighbors);
    }
    public synchronized Integer getOptimisticallyUnchokedNeighbor() {
        return optimisticallyUnchokedNeighbor;
    }
    public synchronized Set<Integer> getInterestedNeighbors() {
        return new HashSet<>(interestedNeighbors);
    }
}