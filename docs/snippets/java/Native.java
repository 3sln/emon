import emon.api.*;
import emon.ffi.Pdfium;
import emon.ffi.Tess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Native {
    public static void main(String[] args) throws Exception {
        Options.Config config = Options.Config.defaults();
        config.renderer = Pdfium.open(null); // the library: $EMON_PDFIUM_LIB, then the system's search
        config.ocr = Tess.open(null);        // the models: $TESSDATA_PREFIX and the usual places; the libraries: $EMON_TESS_LIB
        Engine engine = new Engine(config);
        Model model = engine.convert(List.of(Input.bytes(Files.readAllBytes(Path.of("scan.pdf")))), Options.defaults());
        Files.writeString(Path.of("scan.html"), model.toHtml(new Options.Html()));
    }
}
