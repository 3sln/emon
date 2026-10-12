// Small helpers shared by the two bindings (Pdfium.cs, Tess.cs): the ONE DllImport resolver that maps their logical library names
// ("pdfium", "tesseract", "lept") to the run-time paths, and a native scratch arena (the .NET twin of Java's `Arena.ofConfined()`).
// Names no filesystem type: a name is handed to the dynamic loader.

namespace emon.Ffi;

internal static unsafe class Native {
    private static readonly System.Collections.Generic.Dictionary<string, nint> handles = new System.Collections.Generic.Dictionary<string, nint>();
    private static bool registered = false;
    private static readonly object gate = new object();

    // Loads the first candidate the dynamic loader accepts for the logical name and remembers it for the resolver; 0 when none.
    // The resolver is registered here, before any [DllImport] of this assembly runs. A relative path with a separator is made
    // absolute against the working directory (the repository root), as dlopen would read it.
    internal static nint Pin(string logical, System.Collections.Generic.List<string> candidates) {
        lock (gate) {
            if (!registered) {
                System.Runtime.InteropServices.NativeLibrary.SetDllImportResolver(typeof(Native).Assembly, Resolve);
                registered = true;
            }
            if (handles.TryGetValue(logical, out nint known)) {
                return known;
            }
            foreach (string n in candidates) {
                string p = n;
                if (p.Contains('/') && !p.StartsWith("/")) {
                    p = System.Environment.CurrentDirectory + "/" + p;
                }
                if (System.Runtime.InteropServices.NativeLibrary.TryLoad(p, out nint h)) {
                    handles[logical] = h;
                    return h;
                }
            }
            return 0;
        }
    }

    private static nint Resolve(string name, System.Reflection.Assembly asm, System.Runtime.InteropServices.DllImportSearchPath? path) {
        lock (gate) {
            return handles.TryGetValue(name, out nint h) ? h : 0;
        }
    }

    // A NUL-terminated string at p ("" for NULL).
    internal static string CStr(nint p) {
        if (p == 0) {
            return "";
        }
        return System.Runtime.InteropServices.Marshal.PtrToStringUTF8(p) ?? "";
    }

    // Zeroed native memory, freed together.
    internal sealed class Arena : System.IDisposable {
        private readonly System.Collections.Generic.List<nint> ptrs = new System.Collections.Generic.List<nint>();

        internal nint Alloc(long bytes, int align = 16) {
            if (bytes < 1) {
                bytes = 1;
            }
            void* p = System.Runtime.InteropServices.NativeMemory.AlignedAlloc((nuint) bytes, (nuint) align);
            System.Runtime.InteropServices.NativeMemory.Clear(p, (nuint) bytes);
            ptrs.Add((nint) p);
            return (nint) p;
        }

        internal nint Str(string s) {
            byte[] b = System.Text.Encoding.UTF8.GetBytes(s);
            nint p = Alloc(b.Length + 1);
            System.Runtime.InteropServices.Marshal.Copy(b, 0, p, b.Length);
            return p;
        }

        public void Dispose() {
            foreach (nint p in ptrs) {
                System.Runtime.InteropServices.NativeMemory.AlignedFree((void*) p);
            }
            ptrs.Clear();
        }
    }
}
