import os
import sys
from threading import Timer
import time
import logging

# most of this was generated by ChatGPT3.5, Co-Pilot, and me
# Bdenner: 2024-10-10

# Define constants
CHUNK_SIZE = 102400
CHUNKS_TO_WRITE = 1

logging.basicConfig(level=logging.INFO,
                    format='%(asctime)s - %(levelname)s - %(message)s')


def main():
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <path to file to write>")
        sys.exit(1)

    filePath = sys.argv[1]

    logging.info(f"writing {CHUNK_SIZE * CHUNKS_TO_WRITE / 1024:.2f}KB random data to file '{filePath}'")

    highestDelta = 0
    window_size = 100  # Define the window size for the rolling average
    recent_deltas = []  # List to store recent delta values
    rolling_avg = 0  # Initialize the rolling average

    t = None # Timer object

    def log_rolling_avg():
        logging.info(f"rolling average: {rolling_avg * 1000:.2f}ms")
        t = Timer(10, log_rolling_avg)
        t.start()

    t = Timer(10, log_rolling_avg)
    t.start()

    with open(filePath, 'w') as fd:
        while True:
            try:
                os.lseek(fd.fileno(), 0, os.SEEK_SET)

                startTime = time.time()

                for _ in range(CHUNKS_TO_WRITE):
                    fd.write(os.urandom(CHUNK_SIZE).hex())
                fd.flush()
                os.fsync(fd.fileno())

                endTime = time.time()
                delta = endTime - startTime

                # Update the list of recent deltas
                recent_deltas.append(delta)
                if len(recent_deltas) > window_size:
                    recent_deltas.pop(0)

                # Calculate the rolling average
                rolling_avg = sum(recent_deltas) / len(recent_deltas)

                if delta > highestDelta:
                    logging.info("new high: %.2fms, was %.2fms" % (delta * 1000, highestDelta * 1000))
                    highestDelta = delta

                time.sleep(0.01)
            except KeyboardInterrupt:
                logging.info("Exiting.")
                break
            except Exception as e:
                logging.error("Exception:", e)
                break
    
    t.cancel()
    logging.info(f"highest measured latency: {highestDelta * 1000:.2f}ms")
    logging.info(f"deleting file '{filePath}'")
    os.unlink(filePath)

if __name__ == "__main__":
    main()

