import java.io.*;
import java.math.BigDecimal;
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

    public static int SEND_PING_INTERVAL = 2; // ms

    public static int PI_DIGIT = 10;

    public static enum Workload {
        MEMORY, PI
    }

    public static Workload WORKLOAD = Workload.PI;


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

    private void handleClient(Socket socket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            String message;
            long pingCount = 0;
            while ((message = in.readLine()) != null) {
                if (message.equals("ping")) {

                    if (WORKLOAD == Workload.MEMORY) {
                        workloadMemoryAllocation();
                    } else if (WORKLOAD == Workload.PI) {
                        workloadCalculatePi();
                    }

                    out.println("pong");
                    pingCount++;
                    if (pingCount % 500 == 0) {
                        logger.info("Handled " + pingCount + " pings");
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

            // Client ping loop
            // scheduled to run every SEND_PING_INTERVAL ms
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

                    } else if (duration > 20000) { // 20 ms
                        logger.warning(String.format("High Round-trip time: %.3f ms", duration / 1000));
                    }
                } catch (IOException e) {
                    logger.log(Level.SEVERE, "Ping task exception", e);
                }

            };

            scheduler.scheduleAtFixedRate(pingTask, 0, SEND_PING_INTERVAL, TimeUnit.MILLISECONDS);

            // reset highest round-trip time after 5 minute
            scheduler.scheduleAtFixedRate(() -> {
                if (highestDuration != 0) {
                    logger.info(String.format("Resetting highest round-trip time, was: %.3f ms", highestDuration / 1000));
                    highestDuration = 0;
                }
            }, 0, 5, TimeUnit.MINUTES);

            // Write prometheus metrics every 10 seconds
            if (writePrometheusMetrics) {
                scheduler.scheduleAtFixedRate(() -> writePrometheusMetrics(), 0, 10, TimeUnit.SECONDS);
            }

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

    private static void printUsage() {
        System.out.println("Usage: java PingPong <server|client> [options]");
        System.out.println("Options:");
        System.out.println("  -i, --send-ping-interval <interval>  (client) Interval in milliseconds to send ping messages");
        System.out.println("  -w, --workload <memory|pi>           (server) Workload to simulate on the server");
        System.out.println("  -p, --pi-digit <digit>               (server) Calculate pi to the given digit");
        System.out.println("  -h, --help                           Show this help message");
    }


    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            return;
        }

        PingPong pingPong = new PingPong();

        String module = args[0];
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("-i") || args[i].equals("--send-ping-interval")) {
                i++;
                if (i >= args.length) {
                    System.out.println("Missing value for " + args[i-1]);
                    return;
                }
                SEND_PING_INTERVAL = Integer.parseInt(args[i]);
            } else if (args[i].equals("-p") || args[i].equals("--pi-digit")) {
                i++;
                if (i >= args.length) {
                    System.out.println("Missing value for " + args[i-1]);
                    return;
                }
                PI_DIGIT = Integer.parseInt(args[i]);
            } else if (args[i].equals("-w") || args[i].equals("--workload")) {
                i++;
                if (i >= args.length) {
                    System.out.println("Missing value for " + args[i-1]);
                    return;
                }
                if (args[i].equals("memory")) {
                    WORKLOAD = Workload.MEMORY;
                } else if (args[i].equals("pi")) {
                    WORKLOAD = Workload.PI;
                } else {
                    System.out.println("Unknown workload: " + args[i]);
                    return;
                }
            } else if (args[i].equals("-h") || args[i].equals("--help")) {
                printUsage();
                return;
            } else {
                System.out.println("Unknown argument: " + args[i]);
                printUsage();
                return;
            }
        }


        if (module.equalsIgnoreCase("server")) {
            logger.info(String.format("Starting server with workload: %s", WORKLOAD));
            if (WORKLOAD == Workload.PI) {
                logger.info(String.format("Calculating %d digit of Pi", PI_DIGIT));
            }
            pingPong.server();
        } else if (module.equalsIgnoreCase("client")) {
            pingPong.client();
        } else {
            System.out.println("Unknown argument: " + args[0]);
            printUsage();
        }
    }




    private void workloadMemoryAllocation() {
        LargeObject largeObject = new LargeObject(OBJECT_SIZE);
        removeLargeObjectsIfRequred();
        largeObjects.add(largeObject);
    }

    public static final int OBJECT_SIZE = 1024 * 1024; // 1 MB

    private final List<LargeObject> largeObjects = new LinkedList<>();

    private class LargeObject {
        private byte[] data;

        public LargeObject(int size) {
            data = new byte[size];
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
            logger.info("Heap usage is critical, removing large objects");
            for (int i = 0; i < 1000; i++) {
                if (largeObjects.isEmpty()) {
                    break;
                }
                largeObjects.remove(0);
            }
        }
    }



    private void workloadCalculatePi() {
        getDecimal(PI_DIGIT);
    }

/**
 * The next part is taken from https://github.com/feltocraig/BBP-Bellard/tree/master
 */

/**
 * Prints the nth number of pi followed by the next 8 numbers in base 10.
 * This program is based on Bellard's work.
 * @author feltocraig
 */

	
	/**
	 * Returns the nth digit of pi followed by the next 8 numbers
	 * @param n - nth number of pi to return
	 * @return returns an integer value containing 8 digits after n
	 */
	public int getDecimal(long n) {
		long av, a, vmax, N, num, den, k, kq, kq2, t, v, s, i;
		double sum;

		N = (long) ((n + 20) * Math.log(10) / Math.log(2));

		sum = 0;

		for (a = 3; a <= (2 * N); a = nextPrime(a)) {

			vmax = (long) (Math.log(2 * N) / Math.log(a));
			av = 1;
			for (i = 0; i < vmax; i++)
				av = av * a;

			s = 0;
			num = 1;
			den = 1;
			v = 0;
			kq = 1;
			kq2 = 1;

			for (k = 1; k <= N; k++) {

				t = k;
				if (kq >= a) {
					do {
						t = t / a;
						v--;
					} while ((t % a) == 0);
					kq = 0;
				}
				kq++;
				num = mulMod(num, t, av);

				t = (2 * k - 1);
				if (kq2 >= a) {
					if (kq2 == a) {
						do {
							t = t / a;
							v++;
						} while ((t % a) == 0);
					}
					kq2 -= a;
				}
				den = mulMod(den, t, av);
				kq2 += 2;

				if (v > 0) {
					t = modInverse(den, av);
					t = mulMod(t, num, av);
					t = mulMod(t, k, av);
					for (i = v; i < vmax; i++)
						t = mulMod(t, a, av);
					s += t;
					if (s >= av)
						s -= av;
				}

			}

			t = powMod(10, n - 1, av);
			s = mulMod(s, t, av);
			sum = (sum + (double) s / (double) av) % 1;
		}
		return (int) (sum * 1e9); // 1e9 is 9 decimal places
	}

	private long mulMod(long a, long b, long m) {
		return (long) (a * b) % m;
	}

	private long modInverse(long a, long n) {
		long i = n, v = 0, d = 1;
		while (a > 0) {
			long t = i / a, x = a;
			a = i % x;
			i = x;
			x = d;
			d = v - t * x;
			v = x;
		}
		v %= n;
		if (v < 0)
			v = (v + n) % n;
		return v;
	}

	private long powMod(long a, long b, long m) {
		long tempo;
		if (b == 0)
			tempo = 1;
		else if (b == 1)
			tempo = a;

		else {
			long temp = powMod(a, b / 2, m);
			if (b % 2 == 0)
				tempo = (temp * temp) % m;
			else
				tempo = ((temp * temp) % m) * a % m;
		}
		return tempo;
	}

	private boolean isPrime(long n) {
		if (n == 2 || n == 3)
			return true;
		if (n % 2 == 0 || n % 3 == 0 || n < 2)
			return false;

		long sqrt = (long) Math.sqrt(n) + 1;

		for (long i = 6; i <= sqrt; i += 6) {
			if (n % (i - 1) == 0)
				return false;
			else if (n % (i + 1) == 0)
				return false;
		}
		return true;
	}

	private long nextPrime(long n) {
		if (n < 2)
			return 2;
		if (n == 9223372036854775783L) {
			System.err.println("Next prime number exceeds Long.MAX_VALUE: " + Long.MAX_VALUE);
			return -1;
		}
		for (long i = n + 1;; i++)
			if (isPrime(i))
				return i;
	}



}
