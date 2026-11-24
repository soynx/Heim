package dev.soynx.heim.net.dns;

/** A network environment variable holds a value Heim cannot use; fatal at startup. */
public class InvalidNetworkConfigException extends RuntimeException {

    public InvalidNetworkConfigException(String variable, String reason) {
        super(variable + ": " + reason);
    }
}
