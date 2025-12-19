#!/bin/bash

# Script for removing log files and dat files in between testing runs

rm -f log_peer_*.log

if [ -d "config" ]; then
  # remove accumulated piece_[pieceID].dat files
  find config/peer_* -name "piece_*.dat" -delete

  # remove assembled (completely downloaded) files
  find config/peer_* -type f ! -name "Common.cfg" ! -name "PeerInfo.cfg" -delete
fi

echo "Cleaning test environment complete."
