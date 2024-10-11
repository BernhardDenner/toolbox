import java.io.*;
import java.net.*;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.*;
import java.util.HashMap;
import java.util.Map;


public class PingPong {

    public final static int PORT = 8080;
    private static final Logger logger = Logger.getLogger(PingPong.class.getName());

    private double highestDuration = 0;
    private double roundtripCount = 0;
    private double roundtripSum = 0;
    private HashMap<Integer, Double> roundtripTimes = new HashMap<>();

    public static final int OBJECT_SIZE = 1024 * 1024; // 1 MB

    private final List<LargeObject> largeObjects = new LinkedList<>();

    public static final int SEND_PING_INTERVAL = 2; // ms


    private static final String PROMETHEUS_TEXTFILE_PATH = "/var/lib/prometheus/node-exporter";

    static {
        // Set up the logger with a custom format
        try {
            // Create a custom formatter
            Formatter customFormatter = new Formatter() {
                @Override
                public String format(LogRecord record) {
                    String exceptionMessage = "";
                    if (record.getThrown() != null) {
                        StringWriter sw = new StringWriter();
                        PrintWriter pw = new PrintWriter(sw);
                        record.getThrown().printStackTrace(pw);
                        exceptionMessage = sw.toString();
                    }
                    return String.format("[%1$tF %1$tT.%1$tL] [%2$-4s] %3$s %n%4$s",
                            new java.util.Date(record.getMillis()),
                            record.getLevel().getName(),
                            record.getMessage(),
                            exceptionMessage);
                }
            };

            // Create a console handler
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setFormatter(customFormatter);

            // Remove default handlers
            Logger rootLogger = Logger.getLogger("");
            Handler[] handlers = rootLogger.getHandlers();
            for (Handler handler : handlers) {
                rootLogger.removeHandler(handler);
            }

            // Add custom handler
            logger.addHandler(consoleHandler);
            logger.setUseParentHandlers(false);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to set up logger", e);
        }
    }

    private class LargeObject {
        private byte[] data;

        public LargeObject(int size) {
            data = new byte[size];
        }
    }

    protected void server() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            logger.info(String.format("Server is listening on port %d", PORT));
            ExecutorService executor = Executors.newCachedThreadPool();

            while (true) {
                Socket socket = serverSocket.accept();
                executor.submit(() -> this.handleClient(socket));
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Server exception", e);
        }
    }

    private boolean heapUsageCritical() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long availableMemory = maxMemory - usedMemory;

        double usedMemoryPercentage = (double) usedMemory / maxMemory * 100;
        double availableMemoryPercentage = (double) availableMemory / maxMemory * 100;

        if (usedMemoryPercentage > 95) {
            logger.fine(String.format("Used memory: %.2f%%, Available memory: %.2f%%", usedMemoryPercentage,
                    availableMemoryPercentage));
            return true;
        }

        return false;
    }

    synchronized private void removeLargeObjectsIfRequred() {
        if (heapUsageCritical()) {
            logger.fine("Heap usage is critical, removing large objects");
            for (int i = 0; i < 1000; i++) {
                if (largeObjects.isEmpty()) {
                    break;
                }
                largeObjects.remove(0);
            }
        }
    }

    private void handleClient(Socket socket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            String message;
            while ((message = in.readLine()) != null) {
                if (message.equals("ping")) {

                    LargeObject largeObject = new LargeObject(OBJECT_SIZE);
                    removeLargeObjectsIfRequred();
                    largeObjects.add(largeObject);

                    out.println("pong");

                    if (largeObjects.size() % 500 == 0) {
                        logger.info("Got ping, sent pong, largeobjects count: " + largeObjects.size());
                    }
                } else {
                    logger.warning("Got unknown message: " + message);
                }
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Client handler exception", e);
        }
    }

    protected void client() {
        logger.info("starting client on port " + PORT + " and sending ping every " + SEND_PING_INTERVAL + " ms");
        final boolean writePrometheusMetrics = isPrometheusTextFilePathisWriteable();

        if (writePrometheusMetrics) {
            logger.info("Prometheus metrics will be written to: " + PROMETHEUS_TEXTFILE_PATH);
        } else {
            logger.warning("Prometheus metrics will not be written, as the path is not writeable: " + PROMETHEUS_TEXTFILE_PATH);
        }

        try (Socket socket = new Socket("localhost", PORT);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {



            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
            Runnable pingTask = () -> {
                try {
                    long startTime = System.nanoTime();
                    out.println("ping");
                    String response = in.readLine();
                    long endTime = System.nanoTime();
                    double duration = TimeUnit.NANOSECONDS.toMicros(endTime - startTime);
                    roundtripCount++;
                    roundtripSum += duration;

                    int durationMs = (int) (duration / 1000);
                    int bucket = durationMs / 10 * 10;
                    if (bucket > 1000) {
                        bucket = 1000;
                    } else if (bucket > 100) {
                        bucket = bucket / 100 * 100;
                    }
                    roundtripTimes.put(bucket, roundtripTimes.getOrDefault(bucket, 0.0) + 1);


                    if (duration > highestDuration) {
                        highestDuration = duration;
                        logger.info(String.format("New highest round-trip time: %.3f ms", highestDuration / 1000));

                        // reset highest round-trip time after 5 minute
                        scheduler.schedule(() -> {
                            logger.info(String.format("Resetting highest round-trip time: %.3f ms", highestDuration / 1000));
                            highestDuration = 0;
                        }, 5, TimeUnit.MINUTES);

                    } else if (duration > 20000) { // 20 ms
                        logger.warning(String.format("High Round-trip time: %.3f ms", duration / 1000));
                    }
                } catch (IOException e) {
                    logger.log(Level.SEVERE, "Ping task exception", e);
                }

                if (writePrometheusMetrics) {
                    writePrometheusMetrics();
                }
            };

            scheduler.scheduleAtFixedRate(pingTask, 0, SEND_PING_INTERVAL, TimeUnit.MILLISECONDS);

            // Keep the client running
            Thread.currentThread().join();
        } catch (IOException | InterruptedException e) {
            logger.log(Level.SEVERE, "Client exception", e);
        }
    }
    


    private boolean isPrometheusTextFilePathisWriteable() {
        File prometheusTextFile = new File(PROMETHEUS_TEXTFILE_PATH + "/.test");
        if (prometheusTextFile.exists()) {
            if (!prometheusTextFile.canWrite()) {
                return false;
            }
        } else {
            try {
                prometheusTextFile.createNewFile();
                prometheusTextFile.delete();
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    private void writePrometheusMetrics() {
        if (!isPrometheusTextFilePathisWriteable()) {
            logger.warning("Prometheus text file path is not writeable: " + PROMETHEUS_TEXTFILE_PATH);
            return;
        }

        File prometheusTextFile = new File(PROMETHEUS_TEXTFILE_PATH + "/ping_pong.prom.tmp");
        try (PrintWriter writer = new PrintWriter(prometheusTextFile)) {
            writer.println("# HELP ping_pong_round_trip_time Round trip time of ping pong in microseconds within the last 5 minutes");
            writer.println("# TYPE ping_pong_round_trip_time gauge");
            writer.println("ping_pong_round_trip_peak_time " + highestDuration);
            writer.println();
            writer.println("# HELP ping_pong_round_trip_count Number of round trips");
            writer.println("# TYPE ping_pong_round_trip_count counter");
            writer.println("ping_pong_round_trip_count " + roundtripCount);
            for (Map.Entry<Integer, Double> entry : roundtripTimes.entrySet()) {
                writer.println("ping_pong_round_trip_time_bucket{le=\"" + entry.getKey() + "\"} " + entry.getValue());
            }
            writer.println();
            writer.println("# HELP ping_pong_round_trip_sum Sum of round trip times in microseconds");
            writer.println("# TYPE ping_pong_round_trip_sum counter");
            writer.println("ping_pong_round_trip_time_sum_us " + roundtripSum);

            writer.flush();
            writer.close();
            // Rename the file to make it available for prometheus
            File finalFile = new File(PROMETHEUS_TEXTFILE_PATH + "/ping_pong.prom");
            prometheusTextFile.renameTo(finalFile);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to write prometheus metrics", e);
        }
    }


    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java PingPong <server|client>");
            return;
        }

        PingPong pingPong = new PingPong();

        if (args[0].equalsIgnoreCase("server")) {
            pingPong.server();
        } else if (args[0].equalsIgnoreCase("client")) {
            pingPong.client();
        } else {
            System.out.println("Unknown argument: " + args[0]);
            System.out.println("Usage: java PingPong <server|client>");
        }
    }
}
