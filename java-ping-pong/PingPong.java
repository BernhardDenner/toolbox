import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.logging.*;

public class PingPong {

    public final static int PORT = 8080;
    private static final Logger logger = Logger.getLogger(PingPong.class.getName());

    private double highestDuration = 0;

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

    protected void server() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            logger.info(String.format("Server is listening on port %d", PORT));
            ExecutorService executor = Executors.newCachedThreadPool();

            while (true) {
                Socket socket = serverSocket.accept();
                executor.submit(() -> handleClient(socket));
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Server exception", e);
        }
    }

    private static void handleClient(Socket socket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            String message;
            while ((message = in.readLine()) != null) {
                if (message.equals("ping")) {
                    out.println("pong");

                    // Here we are generating some random objects on the heap to put the server
                    // under stress. We want to trigger memory swap in and swap out of the OS
                    // so see the delayed effect of this on the round-trip time.
                    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                    for (int i = 0; i < 100000; i++) {
                        String randomString = new java.math.BigInteger(130, new java.security.SecureRandom()).toString(32);
                        digest.update(randomString.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    String hashString = java.util.Base64.getEncoder().encodeToString(digest.digest());
                    // include the hashString in the log message to avoid the compiler optimizing
                    logger.info("Got ping, sent pong " + hashString);
                } else {
                    logger.warning("Got unknown message: " + message);
                }
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Client handler exception", e);
        } catch (java.security.NoSuchAlgorithmException e) {
            logger.log(Level.SEVERE, "Failed to generate random string", e);
        }
    }

    protected void client() {
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

                    if (duration > highestDuration) {
                        highestDuration = duration;
                        logger.info(String.format("New highest round-trip time: %.3f ms", highestDuration / 1000));
                    } else if (duration > 1000) {
                        logger.warning(String.format("High Round-trip time: %.3f ms", duration / 1000));
                    }
                    // logger.info(String.format("Server response: %s (Round-trip time: %.3f ms)",
                    // response, duration / 1000));
                } catch (IOException e) {
                    logger.log(Level.SEVERE, "Ping task exception", e);
                }
            };

            scheduler.scheduleAtFixedRate(pingTask, 0, 500, TimeUnit.MILLISECONDS);

            // Keep the client running
            Thread.currentThread().join();
        } catch (IOException | InterruptedException e) {
            logger.log(Level.SEVERE, "Client exception", e);
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
