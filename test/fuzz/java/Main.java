// The `bb fuzz` driver, Java (DEVLOG P2.12, D096). Test-only; the twin of test/fuzz/rust/main.rs.
import java.io.*;
import java.nio.file.*;

public class Main {
    static String run(byte[] b, int chunk) {
        try {
            StringBuilder t = new StringBuilder();
            emon.Fuzzprobe.fzCase(b, chunk, t);
            return t.toString();
        } catch (Throwable e) {
            return ("PANIC " + e).replace('\n', ' ').replace('\t', ' ');
        }
    }

    public static void main(String[] args) throws Exception {
        int chunk = 0;
        for (String a : args) if (a.startsWith("--chunk=")) chunk = Integer.parseInt(a.substring(8));
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, "UTF-8");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        String path;
        while ((path = in.readLine()) != null) {
            byte[] data;
            try { data = Files.readAllBytes(Paths.get(path)); }
            catch (IOException e) { out.println(path + "\tIOERROR"); out.flush(); continue; }
            String line = path + "\t" + run(data, 0);
            if (chunk > 0) line += "\t" + run(data, chunk);
            out.println(line);
            out.flush();
        }
    }
}
