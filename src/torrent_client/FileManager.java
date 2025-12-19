package torrent_client;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.*;

/**
 * Manages the persistence layer of the P2P client, in particular, handling the fragmentation of local files into
 * transmittable pieces, tracking piece ownership via bitfields, and reassembling received fragments into the final
 * output file. This class ensures strict directory isolation for each peer as required by the protocol specification.
 */
public class FileManager {
    private final int peer_id;
    private final CommonConfig config;
    private final String base_directory;
    private final String file_name;
    private final int file_size;
    private final int piece_size;
    private final int num_pieces;
    private final int last_piece_size;

    private final BitSet owned_pieces;        // bitfield (i.e. current peer owns these pieces)
    private final Set<Integer> requested_pieces;  // track "in-flight" 'piece_index' requests to avoid duplicate requests

    public FileManager(int peer_id, CommonConfig config, boolean has_completed_file) {
        this.peer_id = peer_id;
        this.config = config;

        String path = "config/";
        this.base_directory = path + peer_id + "/";
        this.file_name = config.getFileName();

        this.file_size = config.getFileSize();
        this.piece_size = config.getPieceSize();
        this.num_pieces = (int)Math.ceil((double) file_size / piece_size);
        this.last_piece_size = file_size % piece_size;

        this.owned_pieces = new BitSet(num_pieces);
        this.requested_pieces = new HashSet<>();


        File dir = new File(base_directory);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        if (has_completed_file) {
            initializeWithCompleteFile();

        } else {
            checkExistingPieces();
        }

        if (getNumPiecesOwned() > 0) {
            System.out.println("'FileManager' initialized for Peer " + peer_id + " with " + getNumPiecesOwned() + " pieces owned");
        }
    }

    /**
     * Initializes when peer starts with complete file
     */
    private void initializeWithCompleteFile() {
        File complete_file = new File(base_directory + file_name);

        if (!complete_file.exists()) {
            System.err.println("Error: Complete file not found for Peer " + peer_id);
            return;
        }

        fragment(complete_file);

        owned_pieces.set(0, num_pieces);    // set bits from 0 to 'num_pieces' since 'BitSet' uses
                                            // [inclusive lower, exclusive upper) bounds
    }

    /**
     * Initialize when peer starts with no/incomplete file
     */
    private void checkExistingPieces() {

        // Check for 'piece_0.dat', piece_1.dat', ...
        for (int i = 0; i < num_pieces; i++) {
            File piece_file = new File(base_directory + "piece_" + i + ".dat");
            if (piece_file.exists()) {
                owned_pieces.set(i);    // update bitfield if piece is owned
            }
        }
    }

    /**
     * Fragments a file larger than 'piece_size' into individual 'piece files'
     * @param file to be fragmented
     */
    public void fragment(File file) {
        if (!file.exists()) {
            System.err.println("File " + file.getAbsolutePath() + " does not exist");
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long file_size = raf.length();

            for (int i = 0; i < num_pieces; i++) {
                int curr_size = (i == num_pieces - 1) ? last_piece_size : piece_size;
                byte[] piece = new byte[curr_size];

                // We call 'seek()' to jump the 'file pointer' to offset i * 'curr_size' and
                // 'read()' into a buffer 'piece' of size 'piece size' (or less)
                raf.seek((long) i * curr_size);
                int bytes_read = raf.read(piece);

                if (bytes_read != curr_size) {
                    System.err.println("Error: Expected " + curr_size + " bytes for piece " +
                                        i + " but read " + bytes_read);
                }

                // Per project specs: "The file handling method is up to you except each peer should use the corresponding
                // subdirectory ‘peer_[peerID]’ to contain the complete files or partial files"
                // Therefore, we save each fragment to file with filenames indexed by 'i'
                savePieceToFile(i, piece);
            }

            System.out.println("Successfully fragmented file into " + num_pieces + " pieces");

        } catch (IOException e) {
            System.err.println("Error fragmenting file: " + e.getMessage());
        }
    }

    /**
     * Save some piece recevied from another peer
     * @param piece_index of the piece to be saved
     * @param data from some other peer
     * @return boolean indicating success in saving piece
     */
    public boolean savePiece(int piece_index, byte[] data) {
        if (piece_index < 0 || piece_index >= num_pieces) {
            System.err.println("Invalid piece index: " + piece_index);
            return false;
        }

        int expected_size = (piece_index == num_pieces - 1) ? last_piece_size : piece_size;
        if (data.length != expected_size) {
            System.err.println("Invalid piece size for Piece " + piece_index + ": expected " + expected_size +
                               " bytes but got: " + data.length);

            return false;
        }

        // if successful save then update ownership BitSet and "in-flight" requests Set
        if (savePieceToFile(piece_index, data)) {
            owned_pieces.set(piece_index);
            requested_pieces.remove(piece_index);

            if (hasCompleteFile()) {
                assemble();
            }

            return true;
        }

        return false;
    }

    /**
     * Writes piece 'data' to a file with filename indexed by 'piece_index'
     * @param piece_index used in file naming
     * @param data of the piece to be saved
     * @return boolean indicating success in saving piece file
     */
    public boolean savePieceToFile(int piece_index, byte[] data) {
        File piece_file = new File(base_directory + "piece_" + piece_index + ".dat");

        // We use 'Files.write()' since it internally handles creation/deletion of underlying 'FileOutputStream'
        try {
            Files.write(piece_file.toPath(), data);
            return true;

        } catch (IOException e) {
            System.err.println("Error saving file: " + e.getMessage());
            return false;
        }
    }

    /**
     * Assemble fragmented 'piece files'
     */
    public void assemble() {
        if (!hasCompleteFile()) {
            System.err.println("Error assembling file: Expected " + num_pieces + " pieces but found " + getNumPiecesOwned());
            return;
        }

        File complete_file = new File(base_directory + file_name);

        try (RandomAccessFile raf = new RandomAccessFile(complete_file, "rw")) {
            raf.setLength(file_size);           // preallocate exactly 'file_size' bytes (write op)

            for (int i = 0; i < num_pieces; i++) {
                byte[] piece = getPiece(i);     // byte data from "piece_[i].dat" fragment

                if (piece != null) {
                    raf.seek((long) i * piece_size);
                    raf.write(piece);
                } else {
                    System.err.println("Error assembling file: Missing piece " + i);
                    return;
                }
            }

            System.out.println("\nFile " + file_name + " assembled from " + getNumPiecesOwned() + " pieces");
        } catch (IOException e) {
            System.err.println("Error assembling file: " + e.getMessage());
        }
    }

    /**
     * Reads piece data from specific 'piece file' of the form "piece_[piece_index].dat"
     * @param piece_index used to index 'piece file'
     * @return bytes read from corresponding 'piece file'
     */
    public byte[] getPiece(int piece_index) {
        if (piece_index < 0 || piece_index >= num_pieces) {
            System.err.println("Invalid piece index: " + piece_index);
            return null;
        }

        // check bitfield to see if peer has piece_index
        if (!owned_pieces.get(piece_index)) {
            return null;
        }

        File piece_file = new File(base_directory + "piece_" + piece_index + ".dat");
        try {
            return Files.readAllBytes(piece_file.toPath());
        } catch (IOException e) {
            System.err.println("Error reading piece file: " + e.getMessage());
            return null;
        }
    }

    /**
     * Randomly select a piece from a BitSet of 'requestable_pieces' owned by some other peer such that
     * this peer does not already own it and has not yet requested it
     * @param requestable_pieces of some other peer
     * @return 'piece_index' to be requested if interesting piece available, o.w. -1
     */
    public int randomSelection(BitSet requestable_pieces) {
        List<Integer> candidates = new ArrayList<>(); // List since Random (below) expects an iterable

        // a piece is a 'candidate' for request if each of the following is true:
        // (i).   this peer does not own it (that is, we have an unset bit at the corresponding index of 'available_pieces')
        // (ii).  other peer does own it (that is, we have a set bit at the corresponding index of 'requestable_pieces')
        // (iii). previous request has yet to be made (that is, corresponding index is not in 'requested_pieces')
        for (int i = 0; i < num_pieces; i++) {
            if (!owned_pieces.get(i) && requestable_pieces.get(i) && !requested_pieces.contains(i)) {
                candidates.add(i);
            }
        }

        if (candidates.isEmpty()) {
            return -1;
        }

        // Per project specs: "From Suppose that peer A receives an ‘unchoke’ message from peer B. Peer A selects a
        // piece randomly among the pieces that peer B has, and peer A does not have, and peer A has not requested yet.
        // Note that we use a random selection strategy." -> randomly select index
        Random rand = new Random();
        int selected = candidates.get(rand.nextInt(candidates.size()));
        return selected;
    }

    // Getters

    public boolean hasCompleteFile() {
        return owned_pieces.cardinality() == num_pieces;
    }

    public boolean[] getBitfieldArray() {
        boolean[] bitfield = new boolean[num_pieces];
        for (int i = 0; i < num_pieces; i++) {
            bitfield[i] = (owned_pieces.get(i));
        }

        return bitfield;
    }

    public BitSet getBitfield() {
        return (BitSet) owned_pieces.clone();   // since 'BitSet' is mutable, we 'clone()' it

    }

    public int getNumPiecesOwned() {
        return owned_pieces.cardinality();
    }

    public int getNumPieces() {
        return num_pieces;
    }


    /**
     * Logic for ensuring the same piece isn't sent more than once during the repeated sending process
     */

    public boolean markAsRequested(int piece_index) {

        if (piece_index < 0 || piece_index >= num_pieces) {
            return false;
        }

        if (owned_pieces.get(piece_index)) {
            return false;  // Already have it
        }

        if (requested_pieces.contains(piece_index)) {
            return false;  // Already requested
        }

        requested_pieces.add(piece_index);
        return true;
    }
}