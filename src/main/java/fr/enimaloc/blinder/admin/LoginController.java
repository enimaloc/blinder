package fr.enimaloc.blinder.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    @GetMapping("/_/login")
    public String login() {
        return "admin/login";
    }
}
