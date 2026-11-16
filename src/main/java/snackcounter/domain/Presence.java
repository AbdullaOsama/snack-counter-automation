package snackcounter.domain;

/**
 * Where a person is on a given day. Visiting the other office is just
 * {@code atOffice(otherOffice)}; no special "visitor" concept is needed.
 */
public record Presence(Kind kind, OfficeId office) {

    public enum Kind { AT_OFFICE, REMOTE, HOLIDAY }

    public static Presence atOffice(OfficeId office) {
        return new Presence(Kind.AT_OFFICE, office);
    }

    public static Presence remote() {
        return new Presence(Kind.REMOTE, null);
    }

    public static Presence holiday() {
        return new Presence(Kind.HOLIDAY, null);
    }

    public boolean isAt(OfficeId candidate) {
        return kind == Kind.AT_OFFICE && office == candidate;
    }
}
