package docrag.spring;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Shared templates do not depend on an optional Thymeleaf security dialect.
 */
@ControllerAdvice
public final class ResearchChatViewAdvice {
    private final ResearchProperties properties;

    public ResearchChatViewAdvice(ResearchProperties properties) {
        this.properties = properties;
    }

    @ModelAttribute("researchChatEnabled")
    public boolean enabled(Authentication caller) {
        return properties.isEnabled() && caller != null && caller.isAuthenticated() && !(caller instanceof AnonymousAuthenticationToken);
    }
}

