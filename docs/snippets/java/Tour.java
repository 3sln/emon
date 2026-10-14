import emon.api.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Tour {
    public static void main(String[] args) throws Exception {
        // One text says what to do, limits included (docs/options.md).
        Options.Parsed parsed = Options.parse("ocr=never\nlimit.pages=500");
        Engine engine = new Engine(parsed.config);
        List<Input> inputs = List.of(Input.bytes(Files.readAllBytes(Path.of("report.pdf"))));

        // An input is a byte source. A file channel is read lazily, a chunk at a time, only where the engine looks.
        // Stage 0 alone is cheap: format, version, pages, encryption.
        Input lazy = Input.seekable(Files.newByteChannel(Path.of("report.pdf")));
        for (Intake in : engine.intake(List.of(lazy))) {
            System.out.println(in.line());
        }

        try {
            Model model = engine.convert(inputs, parsed.options);
            // Walk the Model: every heading, with its page.
            for (Node h : model.find(Model.Kind.named("heading"))) {
                int page = h.regions().isEmpty() ? 0 : h.regions().get(0).page;
                System.out.println("page " + page + ": " + h.text());
            }
            System.out.println(model.pages().size() + " pages, " + model.nodeCount() + " nodes, " + model.dropped().size() + " dropped");
        } catch (EmonError e) {
            // e.name() is the stable name to route on: "password-required", "pages", "bad-xref", ...
            System.err.println(e.name() + ": " + e.getMessage());
            System.exit(1);
        }
    }
}
