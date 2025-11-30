package torrent_tracker;
import torrent_client.Peer;
import java.net.*;
import java.util.*;
import java.io.*;
import java.util.ArrayList;

public class Tracker {
    static ArrayList<String[]> peers = new ArrayList<>();

    public static void main(String[] args) {
        try {
            ServerSocket tracker = new ServerSocket(8080);
            System.out.println("Tracker started");

            while (true) {
                System.out.println("\nWaiting for connection...");
                Socket client = tracker.accept();
                System.out.println("Connected!");

                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                PrintWriter out = new PrintWriter(client.getOutputStream(), true);

                // Convert ArrayList to StringBuilder to send over Connection
                StringBuilder sendPeers = new StringBuilder();
                for (String[] p : peers) {
                    sendPeers.append(p[1]).append(",").append(p[2]).append(",").append(p[3]).append(";");
                }

                System.out.println("Available peers: " + sendPeers.toString());
                out.println(sendPeers);
                out.flush();

                String[] peerAttributes = in.readLine().split(" ");
                peers.add(peerAttributes);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

//    ArrayList<Peer> getAvailablePeers() {
//        return tracker;
//    }
//
//    public void addPeer(Peer peer) {
//        tracker.add(peer);
//    }
//
//    public Peer getPeerByID(int id) {
//        for (Peer p : tracker) {
//            if (p.getPeerID() == id) {
//                return p;
//            }
//        }
//        return null;
//    }
//
//    ArrayList<Peer> getNeighboringPeers(Peer peer) {
//        ArrayList<Peer> neighbors = new ArrayList<>();
//        for (Peer p : tracker) { // filters out given peer from tracker
//            if (!p.equals(peer)) {
//                neighbors.add(p);
//            }
//        }
//        return neighbors;
//    }
}

// Tracker tracker = array
// When Peer Process runs, creates Peer objects and shoves into tracker
// Methods that link Peer objects here
// Request neighbors: return all peers in torrent that except input peer
//
