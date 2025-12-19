package torrent_client;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

public class Logger implements AutoCloseable {
    private final PrintWriter writer;   // wrapper
    private final int peer_ID;          // sending peer

    public Logger(int peer_ID) throws IOException {
        this.peer_ID = peer_ID;
        writer = new PrintWriter(
                    new BufferedWriter(
                        new FileWriter("log_peer_" + peer_ID + ".log", true)
                    ), true);
    }

    private void log(String msg) {
        LocalTime now = LocalTime.now();
        writer.printf("%s: %s.%n", now.format(DateTimeFormatter.ofPattern("HH:mm:ss")), msg);
    }

    public void logTCPConnection(int peer_ID_1, int peer_ID_2) {
        log(String.format("Peer %d makes a connection to Peer %d", peer_ID_1, peer_ID_2));
    }

    public void logChangeOfPrefNeighbors(int peer_ID, List<Integer> neighbors_list) {
        String neighbors_csv = neighbors_list.stream().map(String::valueOf).collect(Collectors.joining(","));
        log(String.format("Peer %d has the preferred neighbors %s", peer_ID, neighbors_csv));
    }

    public void logChangeOfOptUnchokedNeighbor(int peer_ID_1, int peer_ID_2) {
        log(String.format("Peer %d has the optimistically unchoked neighbor %d", peer_ID_1, peer_ID_2));
    }

    public void logUnchoking(int peer_ID_1, int peer_ID_2) {
        log(String.format("Peer %d is unchoked by %d", peer_ID_1, peer_ID_2));
    }

    public void logChoking(int peer_ID_1, int peer_ID_2) {
        log(String.format("Peer %d is choked by %d", peer_ID_1, peer_ID_2));
    }

    public void logReceivingHave(int peer_ID_1, int peer_ID_2, int piece_index) {
        log(String.format("Peer %d received the 'have' message from %d for the piece %d",
                peer_ID_1, peer_ID_2, piece_index));

    }

    public void logReceivingInterested(int peer_ID_1, int peer_ID_2) {
        log(String.format("Peer %d received the 'interested' message from %d", peer_ID_1, peer_ID_2));
    }

    public void logReceivingNotInterested(int peer_ID_1, int peer_ID_2) {
        log(String.format("Peer %d received the 'not interested' message from %d ", peer_ID_1, peer_ID_2));
    }

    public void logDownloadingPiece(int peer_ID_1, int peer_ID_2, int piece_index, int num_pieces_owned) {
        log(String.format("Peer %d has downloaded the piece %d from %d. Now the number of pieces it has is %d",
                peer_ID_1, piece_index, peer_ID_2, num_pieces_owned));
    }

    public void logCompletionOfDownload(int peer_ID) {
        log(String.format("Peer %d completed the download", peer_ID));
    }

    @Override
    public void close() {
        writer.close();
    }
}
