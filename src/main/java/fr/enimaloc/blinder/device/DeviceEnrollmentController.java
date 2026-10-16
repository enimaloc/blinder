package fr.enimaloc.blinder.device;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Optional;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class DeviceEnrollmentController {

    private final DeviceEnrollmentService enrollments;
    private final DeviceSyncService sync;
    private final DeviceCookieService cookies;

    @GetMapping("/_/enroll/{token}")
    public String confirm(@PathVariable String token, HttpServletRequest request, HttpServletResponse response,
                          Model model) {
        protect(response);
        Optional<DeviceEnrollment> pending = enrollments.pending(token, request.getServerName());
        if (pending.isEmpty()) {
            return notFound(response);
        }
        model.addAttribute("name", pending.get().getName());
        model.addAttribute("token", token);
        return "device/enroll";
    }

    @PostMapping("/_/enroll/{token}")
    public String enroll(@PathVariable String token, HttpServletRequest request, HttpServletResponse response,
                         Model model) {
        protect(response);
        Optional<DeviceEnrollmentService.Enrolled> enrolled = enrollments.complete(token, request.getServerName());
        if (enrolled.isEmpty()) {
            return notFound(response);
        }
        Device device = enrolled.get().device();
        cookies.issue(device, enrolled.get().domain(), request, response);
        return next(sync, sync.begin(device.getId(), enrolled.get().domain().getId(), request.getScheme(),
                request.getServerPort()), device.getId(), model);
    }

    /** Saut suivant de la chaîne, ou page de fin avec le nombre de domaines synchronisés. */
    static String next(DeviceSyncService sync, Optional<String> nextUrl, UUID deviceId, Model model) {
        if (nextUrl.isPresent()) {
            return "redirect:" + nextUrl.get();
        }
        model.addAttribute("count", sync.syncedDomains(deviceId));
        return "device/done";
    }

    static void protect(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    static String notFound(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return "public/not-found";
    }
}
