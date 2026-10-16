package fr.enimaloc.blinder.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DomainForm {

    @NotBlank
    private String host;

    private boolean enabled = true;

    @Size(max = 2048)
    private String notFoundUrl;

    private Long version;
}
