package fr.enimaloc.blinder.tag;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TagForm {

    /** Longueur contrôlée par {@code TagNames.validate} (message « 40 caractères maximum »). */
    private String name;

    @Size(max = 20)
    private String color;

    /** Case « sans couleur » : prioritaire sur {@link #color}. */
    private boolean noColor;

    public String effectiveColor() {
        return noColor ? null : color;
    }
}
