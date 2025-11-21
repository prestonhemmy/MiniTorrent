package torrent_client;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.Random;

/**
 * Handles file fragmentation logic,
 *         piece storage,
 *         bitfield management,
 *         file assembly logic,
 *         piece validation,
 *         directory management
 */
public class FileManager {
    private int peer_id;
    private CommonConfig config;
    private String base_directory;
    private String file_name;
    private int file_size;
    private int piece_size;
    private int num_pieces;
    private int last_piece_size;

    private BitSet owned_pieces;        // bitfield (i.e. current peer owns these pieces)
    private Set<Integer> requested_pieces;  // track "in-flight" 'piece_index' requests to avoid duplicate requests

    public FileManager(int peer_id, CommonConfig config, boolean has_completed_file) {
        this.peer_id = peer_id;
        this.config = config;

        String path = "src/project_config_file_large/";
        this.base_directory = path + peer_id + "/";
        this.file_name = config.getFileName();

        this.file_size = config.getFileSize();
        this.piece_size = config.getPieceSize();
        this.num_pieces = (int)Math.ceil((double) file_size / piece_size);
        this.last_piece_size = file_size % piece_size;

        this.owned_pieces = new BitSet(num_pieces);

        // create peer directory (ex. 1001/) if it does not already exist
        File dir = new File(base_directory);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // initialize differently if peer has complete file (i.e. fragment)
        if (has_completed_file) {
            // ensures file fragmented into "piece_[piece_index].dat" files, written and ready
            // to be sent to neighbors; 'owned_pieces' BitSet set to all 1s

            initializeWithCompleteFile();

        } else {
            // o.w. scan peer's directory for "piece_[piece_index].dat" files (typ. none)
            // and update 'owned_pieces' accordingly; if a peer leaves the Torrent during the
            // exchange (i.e. crash or disconnect) after having already downloaded some piece
            // files, then these piece files would still be stored in the corresponding peer
            // directory, and thus they would be read and correctly reinitialize the FileManager;

            // Also, per project specs: "The fourth column specifies whether it has the file or
            // not. We only have two options here. ‘1’ means that the peer has the complete file and ‘0’
            // means that the peer does not have the file. We do not consider the case where a peer
            // has only some pieces of the file." -> then we do not have to worry about peers
            // initialized with some piece files only all or none, this method is primarily for the
            // case that some peer becomes disconnected during the P2P exchange

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

        // Since we must read 'piece size' bytes (or less for last piece) from the 'file'
        // we use 'RandomAccessFile' which maintains a kind of cursor (file pointer) allowing
        // us to iteratively advance the pointer 'piece size' (or less) bytes with 'seek()'
        // and 'read()' 'piece size' bytes (or less) until EOF reached
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
            requested_pieces.add(piece_index);

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

        // This is the parallel method to 'fragment()'. Again we use 'RandomAccessFile' this time with rw privileges
        // to allow 'raf' to create (read op) the 'complete_file' if not already created and to write each 'piece'
        // to the 'complete_file'
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

            System.out.println("File " + file_name + " assembled from " + getNumPiecesOwned() + " pieces");
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
            if (owned_pieces.get(i) && !requestable_pieces.get(i) && !requested_pieces.contains(i)) {
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
        requested_pieces.add(selected);
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







    // testing (TEMP)

    public static void main(String[] args) throws IOException {
//        int peer_id = 1001;
//        System.out.println("src/project_config_file_large/" + peer_id);

//        int peer_id = 777;
//        String path = "src\\project_config_file_large\\";
//        File base_directory = new File(path + peer_id);
//
//        System.out.println(base_directory.getPath());

//        int file_size = 24301474;
//        int piece_size = 16384;
//        int num_pieces = (int)Math.ceil((double) file_size / piece_size);
//        int last_piece_size = file_size % piece_size;
//
//        System.out.println("For a file of size " + file_size + " bytes, we have\n"
//                + num_pieces + " pieces of size " + piece_size + " bytes and\n" +
//                "a final piece of size " + last_piece_size + " bytes.");

//        int peer_id = 1001;
//        CommonConfig config = new CommonConfig();
//        FileManager fm = new FileManager(peer_id, config, false);
//        File test_file = new File(fm.base_directory.toString() + "\\tree.jpg");
//        fm.fragment(test_file);
    }
}