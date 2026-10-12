// The Engine's one error type (D040): `Name()` is the stable name a crawler routes on. The twin of Go's `Error`
// (`host/go/engine.go`) and of `host/java/emon/api/EmonError.java`, flattened as Go's is: a kind and the payloads of the
// several Rust variants.

namespace emon.Api;

public sealed class EmonError : System.Exception {
    /// <summary>Which of the Engine's errors this is (Rust's `Error` variants).</summary>
    public enum ErrorKind {
        /// <summary>No input was given.</summary>
        NoInput,
        /// <summary>A named bound (SPEC 7.4): `input-bytes`, `pages`, `objects`, ... or the loop's `max-rounds`, `fetch-rounds`.</summary>
        Limit,
        /// <summary>D040's three encryption errors.</summary>
        PasswordRequired, PasswordIncorrect, EncryptionUnsupported,
        /// <summary>Not a PDF (no `%PDF-` header) and not a PNG, JPEG or TIFF either.</summary>
        UnsupportedFormat,
        /// <summary>A PDF kin could not open: its named status (`no-startxref`, ...); or an image that is `undecodable` / `unsupported-image`.</summary>
        Damaged,
        /// <summary>A source that answered fewer bytes than its size said.</summary>
        SourceChanged,
        /// <summary>An error from a source's read.</summary>
        Io,
        /// <summary>Specified, not implemented in this slice.</summary>
        Unsupported,
        /// <summary>The plan/fulfil loop's own errors (a transcript miss, a bad record, ...).</summary>
        Plan,
        /// <summary>The Model kin built failed its own check, or kin panicked: a bug, never the document's fault.</summary>
        Internal
    }

    public readonly ErrorKind Kind;
    public readonly int Input;
    /// <summary>Limit.</summary>
    public readonly long Limit, Actual;
    /// <summary>Damaged, and Limit's name.</summary>
    public readonly string Status;
    /// <summary>Io, Unsupported, Internal. (Java's `message`; `Message` is the exception's own.)</summary>
    public readonly string Msg;
    public readonly emon.Planrun.PlanError Plan;

    internal EmonError(ErrorKind kind, int input, long limit, long actual, string status, string message, emon.Planrun.PlanError plan)
        : base(null, plan) {
        this.Kind = kind;
        this.Input = input;
        this.Limit = limit;
        this.Actual = actual;
        this.Status = status;
        this.Msg = message;
        this.Plan = plan;
    }

    internal static EmonError Of(ErrorKind k) {
        return new EmonError(k, 0, 0, 0, "", "", null);
    }

    internal static EmonError Of(ErrorKind k, int input) {
        return new EmonError(k, input, 0, 0, "", "", null);
    }

    internal static EmonError Damaged(int input, string status) {
        return new EmonError(ErrorKind.Damaged, input, 0, 0, status, "", null);
    }

    /// <summary>Java's `limit(name, limit, actual)`.</summary>
    internal static EmonError Limited(string name, long limit, long actual) {
        return new EmonError(ErrorKind.Limit, 0, limit, actual, name, "", null);
    }

    internal static EmonError Io(int input, string msg) {
        return new EmonError(ErrorKind.Io, input, 0, 0, "", msg, null);
    }

    internal static EmonError Unsupported(string msg) {
        return new EmonError(ErrorKind.Unsupported, 0, 0, 0, "", msg, null);
    }

    internal static EmonError Internal(string msg) {
        return new EmonError(ErrorKind.Internal, 0, 0, 0, "", msg, null);
    }

    /// <summary>
    /// What a panic of kin is as the Engine reports it: a limit breach unwinds with a <c>Port.Breach</c> (kind, limit, actual), caught by its TYPE; its name
    /// is the table's in kin (<c>emon.limits</c>). Anything else is `internal`, with its message (P10.10, D435).
    /// </summary>
    internal static EmonError Panic(System.Exception p) {
        if (p is emon.Port.Breach b) {
            return Limited(b.Name, b.Limit, b.Actual);
        }
        string m = p.Message;
        return Internal(string.IsNullOrEmpty(m) ? p.ToString() : m);
    }

    /// <summary>The plan/fulfil loop's own error as an Engine error (a limit breach is a `Limit`). Java's static `plan(p)`.</summary>
    public static EmonError FromPlan(emon.Planrun.PlanError p) {
        if (p.Limited) {
            return Limited(p.Name, p.Limit, p.Actual);
        }
        return new EmonError(ErrorKind.Plan, 0, 0, 0, "", "", p);
    }

    /// <summary>The stable name.</summary>
    public string Name() {
        switch (Kind) {
            case ErrorKind.NoInput: return "no-input";
            case ErrorKind.Limit: return Status;
            case ErrorKind.PasswordRequired: return "password-required";
            case ErrorKind.PasswordIncorrect: return "password-incorrect";
            case ErrorKind.EncryptionUnsupported: return "encryption-unsupported";
            case ErrorKind.UnsupportedFormat: return "unsupported-format";
            case ErrorKind.Damaged: return Status;
            case ErrorKind.SourceChanged: return "source-changed";
            case ErrorKind.Io: return "io";
            case ErrorKind.Unsupported: return "unsupported";
            case ErrorKind.Plan: return Plan.Name;
            default: return "internal";
        }
    }

    public override string Message {
        get {
            switch (Kind) {
                case ErrorKind.NoInput:
                    return "no-input: nothing to convert";
                case ErrorKind.Limit:
                    return Status + ": the limit is " + Limit + " and this input reached " + Actual;
                case ErrorKind.PasswordRequired:
                    return "password-required: input " + Input + " is encrypted and needs a password";
                case ErrorKind.PasswordIncorrect:
                    return "password-incorrect: the password does not open input " + Input;
                case ErrorKind.EncryptionUnsupported:
                    return "encryption-unsupported: input " + Input + " uses a security handler emon does not read";
                case ErrorKind.UnsupportedFormat:
                    return "unsupported-format: input " + Input + " is not a PDF, PNG, JPEG or TIFF";
                case ErrorKind.Damaged:
                    return Status + ": input " + Input + " could not be opened";
                case ErrorKind.SourceChanged:
                    return "source-changed: input " + Input + " gave fewer bytes than its length";
                case ErrorKind.Io:
                    return "io: input " + Input + ": " + Msg;
                case ErrorKind.Unsupported:
                    return "unsupported: " + Msg;
                case ErrorKind.Plan:
                    return Plan.Message;
                default:
                    return "internal: " + Msg;
            }
        }
    }
}
