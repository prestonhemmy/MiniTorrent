package torrent_client;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Scanner;

public class CommonConfig {
    private final int num_pref_neighbors;
    private final int unchoking_interval;
    private final int opt_unchoking_interval;
    private final String file_name;
    private final int file_size;
    private final int piece_size;

    CommonConfig() {
        num_pref_neighbors = 2;
        unchoking_interval = 5;
        opt_unchoking_interval = 15;
        file_name = "tree.jpg";
        file_size = 10000232;
        piece_size = 32768;
    }

    CommonConfig(int num_pref_neighbors, int unchoking_interval, int opt_unchoking_interval,
                 String file_name, int file_size, int piece_size) {
        this.num_pref_neighbors = num_pref_neighbors;
        this.unchoking_interval = unchoking_interval;
        this.opt_unchoking_interval = opt_unchoking_interval;
        this.file_name = file_name;
        this.file_size = file_size;
        this.piece_size = piece_size;
    }

    int getNumPrefNeighbors() { return num_pref_neighbors; }
    int getUnchokingInterval() { return unchoking_interval; }
    int getOptUnchokingInterval() { return opt_unchoking_interval; }
    String getFileName() { return file_name; }
    int getFileSize() { return file_size; }
    int getPieceSize() { return piece_size; }
}

// TODO: Move to new file 'ConfigParser.java' and make public
// TODO: Integration?
class ConfigParser {
    CommonConfig parseCommonConfig(String filename) {
        int num_pref_neighbors = 2;
        int unchoking_interval = 5;
        int opt_unchoking_interval = 15;
        String file_name = "tree.jpg";
        int file_size = 10000232;
        int piece_size = 32768;

        try {
            Scanner scanner = new Scanner(new File(filename));
            while (scanner.hasNext()) {
                String property = scanner.next();

                if (!scanner.hasNext()) break;

                if (property.equals("NumberOfPreferredNeighbors")) { num_pref_neighbors = scanner.nextInt(); }
                else if (property.equals("UnchokingInterval")) { unchoking_interval = scanner.nextInt(); }
                else if (property.equals("OptimisticUnchokingInterval")) { opt_unchoking_interval = scanner.nextInt(); }
                else if (property.equals("FileName")) { file_name = scanner.next(); }
                else if (property.equals("FileSize")) { file_size = scanner.nextInt(); }
                else if (property.equals("PieceSize")) { piece_size = scanner.nextInt(); }
                else { scanner.next(); }
            }

            scanner.close();
        } catch (FileNotFoundException e) {
            System.err.println("Error parsing 'Common.cfg': " + e.getMessage());
        }

        return new CommonConfig(
                num_pref_neighbors, unchoking_interval, opt_unchoking_interval, file_name, file_size, piece_size
        );
    }

    // TODO: Consider moving 'PeerProcess.java' PeerInfo.cfg parsing here
    //  for unified config parsing functionality
}
