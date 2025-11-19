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
        String config = "src/project_config_file_large/PeerInfo.cfg";
        Tracker tracker = new Tracker();

        Map<Integer, Peer> peers = PeerProcess.readPeerInfoConfig(config, 10);
        if (peers.isEmpty()) {
            System.err.println("No peers loaded! Check your config path: " + config);
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
        do {
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
            Thread.sleep(500);

            p1.sendMessage(peerConnectionID, new HandshakeMessage(p1.getPeerID()));
            Thread.sleep(500);

//            ArrayList<Integer> pieces = p1.checkPieces(p2);
//            System.out.println(pieces);

            // Send file
            p1.sendFile(peerConnectionID);
        } while (!buffer.isEmpty());
    }
}