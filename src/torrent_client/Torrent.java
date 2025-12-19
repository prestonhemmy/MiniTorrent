package torrent_client;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * TODO: Torrent class comment
 */
public class Torrent {
    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 1) {
            System.out.println("Usage: java -cp bin torrent_client.Torrent [peerID]");
        }

        int peerID = Integer.parseInt(args[0]); // local peer ID

        ConfigParser parser = new ConfigParser();
        String commonPath = "src/project_config_file_large/Common.cfg";
        String peerInfoPath = "src/project_config_file_large/PeerInfo.cfg";

        CommonConfig config = parser.parseCommonConfig(commonPath);

        Map<Integer, Peer> peers = PeerProcess.readPeerInfoConfig(peerInfoPath, config);
        if (peers.isEmpty()) {
            System.err.println("No peers loaded! Check your config path: " + peerInfoPath);
            return;
        }
        if (args.length != 1) {
            System.err.println("No peerID/Incorrect args provided");
            return;
        }

        Peer peer = peers.get(peerID); // local peer
        System.out.println("Initializing Peer " + peerID + "...");

        peer.setTotalPeers(peers.size());

        // initialize 'peerCompletionStatus' tracker
        for (Map.Entry<Integer, Peer> entry : peers.entrySet()) {
            int id = entry.getKey();
            boolean hasFile = entry.getValue().hasFile();
            peer.registerForTermination(id, hasFile);
        }

        peer.start();

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        List<Integer> orderedIDs = PeerProcess.getOrderedPeerIDs(peerInfoPath);

        for (int id : orderedIDs) {
            // stop condition: we only attempt to connect to preceding peers in the ordered list
            // no need to connect to self or subsequent peers as they have yet to be initialized
            if (id == peerID) {
                break;
            }

            int remoteID = id;

            Peer remotePeer = peers.get(remoteID);
            peer.establishConnection(remoteID, remotePeer.getHostname(), remotePeer.getPort());
        }

        // wait for termination
        peer.waitForTermination();  // blocks until all peers done downloading

        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // if unblocked (then terminating all peers)
        System.out.println("Peer " + peerID + " terminating...");
        peer.shutdown();
        System.exit(0);
    }
}