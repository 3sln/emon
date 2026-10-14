import emon.api.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Quickstart {
    public static void main(String[] args) throws Exception {
        Engine engine = new Engine(Options.Config.defaults());
        Model model = engine.convert(List.of(Input.bytes(Files.readAllBytes(Path.of("report.pdf")))), Options.defaults());
        Files.writeString(Path.of("report.html"), model.toHtml(new Options.Html()));
    }
}
