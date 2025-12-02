#!/bin/bash

# Script for removing log files and dat files in between testing runs

rm -f log_peer_*.log
find src/project_config_file_large -name "piece_*.dat" -delete
rm -f src/project_config_file_large/1002/tree.jpg
rm -f src/project_config_file_large/1003/tree.jpg
rm -f src/project_config_file_large/1004/tree.jpg
rm -f src/project_config_file_large/1005/tree.jpg
rm -f src/project_config_file_large/1006/tree.jpg


echo "Cleaning test environment complete."