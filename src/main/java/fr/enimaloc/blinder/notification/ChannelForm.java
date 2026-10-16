package fr.enimaloc.blinder.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChannelForm {

    @NotBlank
    @Size(max = 64)
    private String name;

    @NotNull
    private ChannelType type = ChannelType.WEBHOOK;

    @NotBlank
    @Size(max = 2048)
    private String target;

    private boolean allowPrivateNetwork;

    private boolean preselected;

    private boolean enabled = true;
}
