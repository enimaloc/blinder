package fr.enimaloc.blinder.device;

import fr.enimaloc.blinder.common.HostNormalizer;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.domain.DomainRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class DeviceSyncController {

    private final DeviceSyncService sync;
    private final DeviceCookieService cookies;
    private final DomainRepository domains;

    @GetMapping("/_/device/sync")
    public String sync(@RequestParam("d") UUID deviceId, @RequestParam("o") UUID originDomainId,
                       @RequestParam("t") String ticket, HttpServletRequest request, HttpServletResponse response,
                       Model model) {
        DeviceEnrollmentController.protect(response);
        Optional<DeviceSyncService.Hop> hop = sync.hop(deviceId, originDomainId, ticket, request.getServerName(),
                request.getScheme(), request.getServerPort());
        if (hop.isEmpty()) {
            return DeviceEnrollmentController.notFound(response);
        }
        cookies.issue(hop.get().device(), hop.get().domain(), request, response);
        return DeviceEnrollmentController.next(sync, hop.get().nextUrl(), deviceId, model);
    }

    @GetMapping("/_/device/resync")
    public String resync(HttpServletRequest request, HttpServletResponse response, Model model) {
        DeviceEnrollmentController.protect(response);
        Optional<UUID> device = cookies.authenticate(request);
        Optional<Domain> current = domains.findByHost(HostNormalizer.normalize(request.getServerName()));
        if (device.isEmpty() || current.isEmpty()) {
            return DeviceEnrollmentController.notFound(response);
        }
        return DeviceEnrollmentController.next(sync, sync.begin(device.get(), current.get().getId(),
                request.getScheme(), request.getServerPort()), device.get(), model);
    }
}
