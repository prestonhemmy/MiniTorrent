package torrent_client;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * NOTE. Compile with "javac -d bin src/torrent_client/*.java" now
 *       Must run peers in order they are provided in 'PeerConfig.cfg'
 *       If recently tested, run ./reset.sh to clear directory contents
 */
public class Torrent {
    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 1) {
            System.out.println("Usage: java -cp bin torrent_client.Torrent [peerID]");
        }

        int peerID = Integer.parseInt(args[0]); // local peer ID

        // Instead of relying on hardcoded values we utilize the ConfigParser to read the necessary
        // configuration params into a 'CommonConfig' object
        ConfigParser parser = new ConfigParser();
        String commonPath = "src/project_config_file_large/Common.cfg";
        String peerInfoPath = "src/project_config_file_large/PeerInfo.cfg";

        CommonConfig config = parser.parseCommonConfig(commonPath);

        Map<Integer, Peer> peers = PeerProcess.readPeerInfoConfig(peerInfoPath, config);    // LinkedHashMap now
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

        /**
         * Parse the string returned
         */
//        ArrayList<String[]> receivedPeersInTorrent = new ArrayList<>();
//        if  (peersInTorrent.isEmpty()) {
//            System.err.println("No peers found!");
//        } else {
//            String[] trackedPeersAsString =  peersInTorrent.split(";");
//            for (String trackedPeer : trackedPeersAsString) {
//                String[] trackedPeerInfo = trackedPeer.split(",");
//                receivedPeersInTorrent.add(trackedPeerInfo);
//            }
//        }

        peer.start();

        try { // give time to start
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        List<Integer> orderedIDs = PeerProcess.getOrderedPeerIDs(peerInfoPath);

        // attempt to connect to running peers
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

        try { // give time to start
            Thread.sleep(5000); // wait 5 sec
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // if unblocked (then terminating all peers)
        System.out.println("Peer " + peerID + " terminating...");
        peer.shutdown();
        System.exit(0);

//        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
//        String buffer = "";
//        // Per project specs: "The peer that has just started should make TCP connections to all peers that started
//        // before it." ->  no need for a loop to continue taking user inputs
//        //        do {
//        if (receivedPeersInTorrent.size() > 0) {
//            for (String[] peerInTorrent : receivedPeersInTorrent) {
//                p1.establishConnection(Integer.parseInt(peerInTorrent[0]), peerInTorrent[1], Integer.parseInt(peerInTorrent[2]));
//            }
//        }

//            int peerConnectionID = 0;
//            try {
//                System.out.print("Enter the peerID of the peer you want to connect to: ");
//                buffer = reader.readLine();
//                peerConnectionID = Integer.parseInt(buffer);
//            } catch (IOException e) {
//                System.out.println("An error occurred while reading input.");
//                e.printStackTrace();
//            }
//            Peer p2 = peers.get(peerConnectionID);
//
//            p1.establishConnection(peerConnectionID, p2.getHostname(), p2.getPort());
    }
}