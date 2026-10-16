package fr.enimaloc.blinder.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
@ToString(exclude = {"currentPassword", "newPassword", "confirmPassword"})
public class PasswordChangeForm {

    @NotBlank
    private String currentPassword;

    @NotBlank
    @Size(min = 12, max = 72, message = "12 à 72 caractères")
    private String newPassword;

    private String confirmPassword;
}
