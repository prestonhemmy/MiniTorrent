# ByteTorrent

This repository contains a complete implementation of a Peer-to-Peer (P2P) file distribution protocol, modeled after *BitTorrent*. The system facilitates efficient file sharing by breaking files into smaller pieces and utilizing a "tit-for-tat" choking/unchoking mechanism to manage data exchange between peers. 

The project implements a custom handshake protocol, bitfield management for tracking file pieces, and a reliable transport layer built on TCP. 


## Demo

https://github.com/user-attachments/assets/1e5c95cf-3e3e-4b15-9ce0-65c9cf85265b


## Key Components

### Protocol & Messaging

* **Handshake Mechanism**: A 32-byte handshake protocol that validates Peer IDs and headers before establishing a data stream. 

* **Message Framing**: Implements a length-prefixed message system with eight distinct types: `CHOKE`, `UNCHOKE`, `INTERESTED`, `NOT_INTERESTED`, `HAVE`, `BITFIELD`, `REQUEST` and `PIECE`. 

* **Piece Management**: Handles file fragmentation into fixed-size pieces, tracking availability via bitfields and requesting missing pieces from neighbors. 

### Peer Selection Strategy (Choking/Unchoking)

* **Preferred Neighbors**: Periodically reselects  neighbors to unchoke based on the highest data download rates during the previous interval. 

* **Optimistic Unchoking**: Randomly selects one choked neighbor every  seconds to unchoke, allowing new peers to join the exchange and potentially become preferred neighbors. 

* **Random Piece Selection**: Unlike the "rarest-first" approach traditionally used in *BitTorrent*, this implementation uses a random selection strategy for requesting pieces from unchoked neighbors. 

### File Handling & Persistence

* **Fragmentation & Reassembly**: Automatically breaks the source file into pieces for distribution and reassembles them upon completion at each peer. 

* **Environment Management**: Each peer operates within a dedicated subdirectory (`peer_[peerID]`) to maintain its local copy of the file pieces. 

### Logging & Monitoring

* **Event Tracking**: Comprehensive logging of TCP connections, neighbor changes, piece downloads, and protocol state transitions into peer-specific `.log` files. 


## Implementation Details

### Configuration Management

The system is driven by two primary configuration files:

* **`Common.cfg`**: Defines global parameters like `NumberOfPreferredNeighbors`, `UnchokingInterval`, `OptimisticUnchokingInterval`, and `PieceSize`. 

* **`PeerInfo.cfg`**: Acts as a tracker, listing Peer IDs, hostnames, listening ports, and the initial file status of each peer. 

### Multi-Peer Connectivity

* **Full Mesh Initialization**: Upon starting, each peer establishes TCP connections with all peers that were started before it, eventually forming a fully connected network. 

* **Termination Logic**: The peer processes are designed to terminate gracefully only after confirming that every peer in the network has downloaded the complete file. 


## My Contributions

I was responsible for the core data protocol, file persistence layer, and the system's event-driven logging framework.

### Messaging Protocol & Serialization

* **Handshake Implementation**: Developed the 32-byte handshake protocol, including header validation and Peer ID exchange.

* **Message Factory**: Implemented an abstract `Message` class, defining a uniform serialization contact and a Factory pattern in `MessageHandler` to dynamically instantiate specific subclasses based on message type IDs.

* **Bitfield Logic**: Built the big-endian bit-level mapping to track piece availability across peers.

### File Management & Fragmentation

* **FileManager**: Developed the logic for breaking large files into discrete `piece_n.dat` fragments using `RandomAccessFile` for efficient seeking/reading.

* **State Recovery**: Implemented directory scanning to allow peers to resume downloads by verifying existing pieces on startup.

* **Piece Selection**: Wrote the `randomSelection` algorithm to identify and request missing pieces from unchoked neighbors while avoiding duplicate "in-flight" requests.

### TCP Networking & Observability

* **Socket Management**: Co-authored `Peer.java` to handle multi-threaded TCP connections and stream synchronization.

* **Event Logging**: Built a thread-safe `Logger` that captures protocol transitions (e.g., choking, piece completion) into peer-specific `.log` files to verify system behavior.


## Usage

### Prerequisites

Compile the source code using the appropriate compiler for your language (Java, Python, or C++).

```bash
# Example using standard javac compiler
javac -d bin src/torrent_client/*.java

# If recently tested, run ./reset.sh before compilation to clear directory contents

```

Ensure `Common.cfg` and `PeerInfo.cfg` are present in the working directory. 

### Running the System

Start each peer process individually by providing its Peer ID as a parameter. Peer processes should be started in the order they appear in `PeerInfo.cfg`. 

```bash
# Example for starting peer 1001 in Java
java -cp bin torrent_client.Torrent 1001

```
