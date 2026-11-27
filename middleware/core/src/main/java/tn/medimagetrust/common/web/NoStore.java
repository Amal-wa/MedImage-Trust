package tn.medimagetrust.common.web;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marque une ressource ou une méthode dont la réponse ne doit être conservée par aucun cache (navigateur ou
 * intermédiaire) : {@link NoStoreFilter} y ajoute {@code Cache-Control: no-store}.
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface NoStore {
}
