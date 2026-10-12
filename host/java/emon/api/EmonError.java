// The Engine's one error type (D040): `name()` is the stable name a crawler routes on. The twin of Go's `Error`
// (`host/go/engine.go`), flattened as Go's is: a kind and the payloads of the several Rust variants.

package emon.api;

import emon.Planrun;

public final class EmonError extends Exception {
    private static final long serialVersionUID = 1L;

    /** Which of the Engine's errors this is (Rust's `Error` variants). */
    public enum Kind {
        /** No input was given. */
        NO_INPUT,
        /** A named bound (SPEC 7.4): `input-bytes`, `pages`, `objects`, ... or the loop's `max-rounds`, `fetch-rounds`. */
        LIMIT,
        /** D040's three encryption errors. */
        PASSWORD_REQUIRED, PASSWORD_INCORRECT, ENCRYPTION_UNSUPPORTED,
        /** Not a PDF (no `%PDF-` header) and not a PNG, JPEG or TIFF either. */
        UNSUPPORTED_FORMAT,
        /** A PDF kin could not open: its named status (`no-startxref`, `bad-xref`, `no-root`, `no-pages`, ...); or an image that is `undecodable` / `unsupported-image`. */
        DAMAGED,
        /** A source that answered fewer bytes than its size said. */
        SOURCE_CHANGED,
        /** An error from a source's read. */
        IO,
        /** Specified, not implemented in this slice. */
        UNSUPPORTED,
        /** The plan/fulfil loop's own errors (a transcript miss, a bad record, ...). */
        PLAN,
        /** The Model kin built failed its own check, or kin panicked: a bug, never the document's fault. */
        INTERNAL
    }

    public final Kind kind;
    public final int input;
    /** LIMIT. */
    public final long limit, actual;
    /** DAMAGED, and LIMIT's name. */
    public final String status;
    /** IO, UNSUPPORTED, INTERNAL. */
    public final String message;
    public final Planrun.PlanError plan;

    EmonError(Kind kind, int input, long limit, long actual, String status, String message, Planrun.PlanError plan) {
        super(null, plan, false, false);
        this.kind = kind;
        this.input = input;
        this.limit = limit;
        this.actual = actual;
        this.status = status;
        this.message = message;
        this.plan = plan;
    }

    static EmonError of(Kind k) {
        return new EmonError(k, 0, 0, 0, "", "", null);
    }

    static EmonError of(Kind k, int input) {
        return new EmonError(k, input, 0, 0, "", "", null);
    }

    static EmonError damaged(int input, String status) {
        return new EmonError(Kind.DAMAGED, input, 0, 0, status, "", null);
    }

    static EmonError limit(String name, long limit, long actual) {
        return new EmonError(Kind.LIMIT, 0, limit, actual, name, "", null);
    }

    static EmonError io(int input, String msg) {
        return new EmonError(Kind.IO, input, 0, 0, "", msg, null);
    }

    static EmonError unsupported(String msg) {
        return new EmonError(Kind.UNSUPPORTED, 0, 0, 0, "", msg, null);
    }

    static EmonError internal(String msg) {
        return new EmonError(Kind.INTERNAL, 0, 0, 0, "", msg, null);
    }

    /**
     * What a panic of kin is as the Engine reports it: a limit breach unwinds with a {@code Port.Breach} (kind, limit, actual), caught by its TYPE; its name
     * is the table's in kin (`emon.limits`). Anything else is `internal`, with its message (P10.10, D435).
     */
    static EmonError panic(Throwable p) {
        if (p instanceof emon.Port.Breach) {
            emon.Port.Breach b = (emon.Port.Breach) p;
            return limit(b.name(), b.limit, b.actual);
        }
        String m = p.getMessage();
        return internal(m != null ? m : p.toString());
    }

    /** The plan/fulfil loop's own error as an Engine error (a limit breach is a `LIMIT`). */
    public static EmonError plan(Planrun.PlanError p) {
        if (p.limited) {
            return limit(p.name, p.limit, p.actual);
        }
        return new EmonError(Kind.PLAN, 0, 0, 0, "", "", p);
    }

    /** The stable name. */
    public String name() {
        switch (kind) {
            case NO_INPUT: return "no-input";
            case LIMIT: return status;
            case PASSWORD_REQUIRED: return "password-required";
            case PASSWORD_INCORRECT: return "password-incorrect";
            case ENCRYPTION_UNSUPPORTED: return "encryption-unsupported";
            case UNSUPPORTED_FORMAT: return "unsupported-format";
            case DAMAGED: return status;
            case SOURCE_CHANGED: return "source-changed";
            case IO: return "io";
            case UNSUPPORTED: return "unsupported";
            case PLAN: return plan.name;
            default: return "internal";
        }
    }

    @Override
    public String getMessage() {
        switch (kind) {
            case NO_INPUT:
                return "no-input: nothing to convert";
            case LIMIT:
                return status + ": the limit is " + limit + " and this input reached " + actual;
            case PASSWORD_REQUIRED:
                return "password-required: input " + input + " is encrypted and needs a password";
            case PASSWORD_INCORRECT:
                return "password-incorrect: the password does not open input " + input;
            case ENCRYPTION_UNSUPPORTED:
                return "encryption-unsupported: input " + input + " uses a security handler emon does not read";
            case UNSUPPORTED_FORMAT:
                return "unsupported-format: input " + input + " is not a PDF, PNG, JPEG or TIFF";
            case DAMAGED:
                return status + ": input " + input + " could not be opened";
            case SOURCE_CHANGED:
                return "source-changed: input " + input + " gave fewer bytes than its length";
            case IO:
                return "io: input " + input + ": " + message;
            case UNSUPPORTED:
                return "unsupported: " + message;
            case PLAN:
                return plan.getMessage();
            default:
                return "internal: " + message;
        }
    }
}
