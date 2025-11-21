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
        Tracker tracker = new Tracker();

        Map<Integer, Peer> peers = PeerProcess.readPeerInfoConfig(peerInfoPath, config);
        if (peers.isEmpty()) {
            System.err.println("No peers loaded! Check your config path: " + peerInfoPath);
            return;
        }
        if (args.length != 1) {
            System.err.println("No peerID/Incorrect args provided");
            return;
        }

        for (Peer peer : peers.values()) {
            tracker.addPeer(peer);
        }

        Peer p1 = tracker.getPeerByID(Integer.parseInt(args[0]));
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
            int peerConnectionID = 0;
            try {
                System.out.print("Enter the peerID of the peer you want to connect to: ");
                buffer = reader.readLine();
                peerConnectionID = Integer.parseInt(buffer);
            } catch (IOException e) {
                System.out.println("An error occurred while reading input.");
                e.printStackTrace();
            }
            Peer p2 = tracker.getPeerByID(peerConnectionID);
//            Thread.sleep(500);
            p1.establishConnection(peerConnectionID, p2.getHostname(), p2.getPort());
//            Thread.sleep(500);    // Since the handshake logic is now moved inside 'handleIncomingConnection()' and
                                    // 'establishConnection()'; when 'establishConnection()' is called it creates a
                                    // thread immediately performs the handshake synchronously; that is, the main thread
                                    // is no longer responsible for manually sending the first Message
//
//            // -> sendMessage() called in handleIncomingMessage() or establishConnection() listening loops
//            p1.sendMessage(peerConnectionID, new HandshakeMessage(p1.getPeerID()));
//            Thread.sleep(500);

//            ArrayList<Integer> pieces = p1.checkPieces(p2);
//            System.out.println(pieces);

            // Send file
//            p1.sendFile(peerConnectionID);
//        } while (!buffer.isEmpty());
    }
}