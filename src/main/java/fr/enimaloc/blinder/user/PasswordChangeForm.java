package fr.enimaloc.blinder.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PasswordChangeForm {

    @NotBlank
    private String currentPassword;

    @Size(min = 12, max = 128, message = "12 à 128 caractères")
    private String newPassword;

    private String confirmPassword;
}
