import emon.api.*;
import emon.ffi.Pdfium;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Native {
    public static void main(String[] args) throws Exception {
        Options.Config config = Options.Config.defaults();
        config.renderer = Pdfium.open(null); // the library: $EMON_PDFIUM_LIB, then the system's search
        OcrEngines engines = new OcrEngines(); // OCR engines are registered adapters, never wired in
        engines.defaults(Options.defaults());  // lib/emon/adapters beside the application, or $EMON_ADAPTER_DIR
        config.ocr = engines;
        Engine engine = new Engine(config);
        Model model = engine.convert(List.of(Input.bytes(Files.readAllBytes(Path.of("scan.pdf")))), Options.defaults());
        Files.writeString(Path.of("scan.html"), model.toHtml(new Options.Html()));
        engines.close();
    }
}
