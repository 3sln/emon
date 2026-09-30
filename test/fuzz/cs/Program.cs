// The `bb fuzz` driver, C# (DEVLOG P2.12, D106). Test-only; the twin of test/fuzz/rust/main.rs.
using System;
using System.IO;
using System.Text;

static string Run(byte[] b, int chunk) {
    try {
        var t = new StringBuilder();
        emon.Fuzzprobe.FzCase(b, chunk, t);
        return t.ToString();
    } catch (Exception e) {
        return ("PANIC " + e.GetType().Name + ": " + e.Message).Replace('\n', ' ').Replace('\t', ' ');
    }
}

int chunk = 0;
foreach (var a in args) if (a.StartsWith("--chunk=")) chunk = int.Parse(a.Substring(8));
Console.OutputEncoding = new UTF8Encoding(false);
var stdout = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false));
string path;
while ((path = Console.In.ReadLine()) != null) {
    byte[] data;
    try { data = File.ReadAllBytes(path); }
    catch (IOException) { stdout.WriteLine(path + "\tIOERROR"); stdout.Flush(); continue; }
    var line = path + "\t" + Run(data, 0);
    if (chunk > 0) line += "\t" + Run(data, chunk);
    stdout.WriteLine(line);
    stdout.Flush();
}
