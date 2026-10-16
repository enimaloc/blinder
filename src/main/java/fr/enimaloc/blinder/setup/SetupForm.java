package fr.enimaloc.blinder.setup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SetupForm {

    @NotBlank
    private String token;

    @NotBlank
    @Size(max = 64)
    private String username;

    @Size(min = 12, max = 128, message = "12 à 128 caractères")
    private String password;

    private String passwordConfirm;

    @NotBlank
    private String host;
}
