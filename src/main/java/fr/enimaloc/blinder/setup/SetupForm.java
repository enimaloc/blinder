package fr.enimaloc.blinder.setup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
@ToString(exclude = {"token", "password", "passwordConfirm"})
public class SetupForm {

    @NotBlank
    private String token;

    @NotBlank
    @Size(max = 64)
    private String username;

    @NotBlank
    @Size(min = 12, max = 72, message = "12 à 72 caractères")
    private String password;

    private String passwordConfirm;

    @NotBlank
    private String host;
}
