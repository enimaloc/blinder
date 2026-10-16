package fr.enimaloc.blinder.link;

import fr.enimaloc.blinder.access.LinkDeviceLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JpaLinkDeviceLookup implements LinkDeviceLookup {

    private final LinkRepository links;

    @Override
    public boolean hasDevices(UUID linkId) {
        return links.hasDevices(linkId);
    }

    @Override
    public boolean isAllowed(UUID linkId, UUID deviceId) {
        return links.isDeviceAllowed(linkId, deviceId);
    }
}
