@echo off

DEL  "log_peer_*.log"
find "src\project_config_file_large" "-name" "piece_*.dat" "-delete"
DEL  "src\project_config_file_large\1002\tree.jpg"
DEL  "src\project_config_file_large\1003\tree.jpg"
DEL  "src\project_config_file_large\1004\tree.jpg"
DEL  "src\project_config_file_large\1005\tree.jpg"
DEL  "src\project_config_file_large\1006\tree.jpg"
echo "Cleaning test environment complete."