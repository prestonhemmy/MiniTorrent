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

    /**
     * Parses 'PeerInfo.cfg' file and returns a LinkedHashMap of ['PeerID' -> 'Peer'] mappings
     */
    public static Map<Integer, Peer> readPeerInfoConfig(String filePath, CommonConfig config) {
        Map<Integer, Peer> peers = new LinkedHashMap<>();

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

                peers.put(peerId, new Peer(peerId, hostName, port, hasFile, config));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return peers;
    }

    public static List<Integer> getOrderedPeerIDs(String path) {
        List<Integer> ids = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(Paths.get(path))) {
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 1) {
                    ids.add(Integer.parseInt(parts[0]));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return ids;
    }
}
