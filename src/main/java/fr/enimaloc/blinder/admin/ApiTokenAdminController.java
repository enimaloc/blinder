package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.api.token.ApiToken;
import fr.enimaloc.blinder.api.token.ApiTokenService;
import fr.enimaloc.blinder.api.token.CreatedApiToken;
import fr.enimaloc.blinder.common.BlinderProperties;
import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.user.CurrentUser;
import fr.enimaloc.blinder.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/_/tokens")
@RequiredArgsConstructor
public class ApiTokenAdminController {

    private static final String VIEW = "admin/tokens/list";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final CurrentUser currentUser;
    private final ApiTokenService tokens;
    private final BlinderProperties blinder;

    public record TokenRow(UUID id, String name, String prefix, String createdAt, String lastUsedAt) {
    }

    @GetMapping
    public String list(Model model) {
        populate(model, currentUser.require());
        return VIEW;
    }

    @PostMapping
    public String create(@RequestParam(required = false) String name, Model model, RedirectAttributes redirect) {
        User owner = currentUser.require();
        try {
            CreatedApiToken created = tokens.create(owner, name);
            // Flash : le jeton brut n'est affiché qu'au prochain chargement, jamais stocké.
            redirect.addFlashAttribute("createdToken", created.rawToken());
            redirect.addFlashAttribute("createdName", created.token().getName());
            return "redirect:/_/tokens";
        } catch (FieldErrorException e) {
            model.addAttribute("createError", e.getMessage());
            model.addAttribute("name", name);
            populate(model, owner);
            return VIEW;
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id) {
        tokens.revoke(currentUser.require(), id);
        return "redirect:/_/tokens";
    }

    private void populate(Model model, User owner) {
        List<TokenRow> rows = tokens.list(owner).stream().map(this::row).toList();
        model.addAttribute("tokens", rows);
    }

    private TokenRow row(ApiToken token) {
        return new TokenRow(token.getId(), token.getName(), token.getTokenPrefix(), format(token.getCreatedAt()),
                token.getLastUsedAt() == null ? "jamais" : format(token.getLastUsedAt()));
    }

    private String format(Instant instant) {
        return DATE.format(instant.atZone(blinder.admin().timeZone()));
    }
}
