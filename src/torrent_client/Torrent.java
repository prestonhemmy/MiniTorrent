package torrent_client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.util.Map;
import java.util.ArrayList;
import torrent_tracker.Tracker;

public class Torrent {
    public static void main(String[] args) throws IOException, InterruptedException {
        // Instead of relying on hardcoded values we utilize the ConfigParser to read the necessary
        // configuration params into a 'CommonConfig' object
        ConfigParser parser = new ConfigParser();
        String commonPath = "src/project_config_file_large/Common.cfg";
        CommonConfig config = parser.parseCommonConfig(commonPath);

        String peerInfoPath = "src/project_config_file_large/PeerInfo.cfg";

        Map<Integer, Peer> peers = PeerProcess.readPeerInfoConfig(peerInfoPath, config);
        if (peers.isEmpty()) {
            System.err.println("No peers loaded! Check your config path: " + peerInfoPath);
            return;
        }
        if (args.length != 1) {
            System.err.println("No peerID/Incorrect args provided");
            return;
        }

        Peer p1 = peers.get(Integer.parseInt(args[0]));

        /**
         * Must retrieve the peers in the torrent, and then add the new peer to the tracker
         * Requires connecting to Tracker socket and retrieving information
         */
        String peersInTorrent = p1.connectToTracker();

        /**
         * Parse the string returned
         */
        ArrayList<String[]> receivedPeersInTorrent = new ArrayList<>();
        if  (peersInTorrent.isEmpty()) {
            System.err.println("No peers found!");
        } else {
            String[] trackedPeersAsString =  peersInTorrent.split(";");
            for (String trackedPeer : trackedPeersAsString) {
                String[] trackedPeerInfo = trackedPeer.split(",");
                receivedPeersInTorrent.add(trackedPeerInfo);
            }
        }

        p1.start();

        try { // give time to start
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String buffer = "";
        // Per project specs: "The peer that has just started should make TCP connections to all peers that started
        // before it." ->  no need for a loop to continue taking user inputs
//        do {
        if (receivedPeersInTorrent.size() > 0) {
            for (String[] peerInTorrent : receivedPeersInTorrent) {
                p1.establishConnection(Integer.parseInt(peerInTorrent[0]), peerInTorrent[1], Integer.parseInt(peerInTorrent[2]));
            }
        }

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