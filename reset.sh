#!/bin/bash

# Script for removing log files and dat files in between testing runs

rm -f log_peer_*.log
find src/project_config_file_large -name "piece_*.dat" -delete
rm -f src/project_config_file_large/1002/tree.jpg

echo "Cleaning test environment complete."