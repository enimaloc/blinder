package fr.enimaloc.blinder.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class EnrollmentForm {

    @NotBlank
    @Size(max = 64)
    private String name;

    @NotNull(message = "Choisissez un domaine")
    private UUID startDomainId;
}
