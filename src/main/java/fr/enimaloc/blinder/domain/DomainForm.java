package fr.enimaloc.blinder.domain;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DomainForm {

    @NotBlank
    private String host;

    private boolean enabled = true;

    private String notFoundUrl;

    private Long version;
}
