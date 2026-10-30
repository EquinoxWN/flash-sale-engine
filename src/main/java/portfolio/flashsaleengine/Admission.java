package portfolio.flashsaleengine;

/** Result of asking for a queue token; {@code token} is empty unless the caller was admitted. */
public record Admission(Status status, String token) {

    /** Why a token was or was not issued. */
    public enum Status {
        ISSUED,
        ALREADY_ISSUED,
        SOLD_OUT,
        QUEUE_FULL,
        NOT_LOADED;

        /** Status for an admit.lua result code. */
        static Status ofCode(long code) {
            return switch ((int) code) {
                case 1 -> ISSUED;
                case 2 -> ALREADY_ISSUED;
                case 0 -> SOLD_OUT;
                case -3 -> QUEUE_FULL;
                case -2 -> NOT_LOADED;
                default -> throw new IllegalStateException("unexpected admit code " + code);
            };
        }
    }

    /** True when the caller now holds a token. */
    public boolean admitted() {
        return status == Status.ISSUED || status == Status.ALREADY_ISSUED;
    }
}
