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
     * Parses 'PeerInfo.cfg' file and returns a LinkedHashMap of ( 'PeerID', 'Peer' ) mappings
     */
    public static Map<Integer, Peer> readPeerInfoConfig(String filePath, CommonConfig config) {
        Map<Integer, Peer> peers = new LinkedHashMap<>();   // CHANGED to LinkedHashMap (ordered) per project specs

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

    /**
     * (NEW!) Returns a list of Peer IDs corresponding to the order listed in 'PeerInfo.cfg'
     * NOTE. Per project specifications: "You need to start the peer processes in the order specified in the file
     *       PeerInfo.cfg... The peer that has just started should make TCP connections to all peers that started
     *       before it."
     * This guarantees peer 1001 is started first so that when peer 1002 starts, peer 1001 is already listening
     *                                                and when peer 1003 starts, peer 1002 is already listening
     *                                                    ...
     * Also, since new peers connect to already started peers, we never have to worry about duplicate connections
     * between two given peers (ex. peer 1003 connects to 1001 -> peer 1001 simply accepts and never attempts to
     * initiate a connection with peer 1003)
     */
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
