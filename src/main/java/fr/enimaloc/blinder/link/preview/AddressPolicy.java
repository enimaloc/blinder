package fr.enimaloc.blinder.link.preview;

import java.net.InetAddress;

@FunctionalInterface
public interface AddressPolicy {

    boolean allowed(InetAddress address);
}
