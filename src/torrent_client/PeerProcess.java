package torrent_client;
import java.util.*;
import java.io.*;
import java.nio.file.*;

public class PeerProcess {
    int peerID;
    String hostname;
    int port;
    boolean hasFile;

    public PeerProcess(int peerID){
        this.peerID = peerID;
    }
    public static Map<Integer, Peer> readPeerInfoConfig(String filePath, CommonConfig config) {
        Map<Integer, Peer> peers = new HashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(Paths.get(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length != 4) continue;

                int peerId = Integer.parseInt(parts[0]);
                String hostName = parts[1];
                int port = Integer.parseInt(parts[2]);
                boolean hasFile = parts[3].equals("1");

//                boolean[] hasChunks = new boolean[chunkCount];
                // previous logic, was having some errors and wasnt setting right
                // I didnt want to overwrite so its still here just in case
                // for(boolean b : hasChunks) b = b || hasFile;
//                if (hasFile) {
//                    Arrays.fill(hasChunks, true);
//                } else {
//                    Arrays.fill(hasChunks, false);
//                }

                // updated to follow new Peer() constructor
                peers.put(peerId, new Peer(peerId, hostName, port, hasFile, config));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return peers;
    }
}
