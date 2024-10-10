import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class PingPong {

    public final static int PORT = 8080;

    protected void server() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.printf("Server is listening on port %d%n", PORT);
            ExecutorService executor = Executors.newCachedThreadPool();

            while (true) {
                Socket socket = serverSocket.accept();
                executor.submit(() -> handleClient(socket));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleClient(Socket socket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            String message;
            while ((message = in.readLine()) != null) {
                if (message.equals("ping")) {
                    out.println("pong");
                    System.out.println("Got ping, sent pong");
                } else {
                    System.out.println("Got: unknown message: " + message);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
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
                    //System.out.printf("Server response: %s (Round-trip time: %0.3f ms)%n", response, duration);
                    System.out.println("Server response: " + response + " (Round-trip time: " + duration / 1000 + " ms)");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            };

            scheduler.scheduleAtFixedRate(pingTask, 0, 500, TimeUnit.MILLISECONDS);

            // Keep the client running
            Thread.currentThread().join();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
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
