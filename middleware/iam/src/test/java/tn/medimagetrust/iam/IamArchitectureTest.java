package tn.medimagetrust.iam;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;

import java.lang.annotation.Annotation;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

/**
 * Tests d'architecture du module iam : règles 1 (refus par défaut), 6 (rien ne dépend de iam ; iam ne dépend que de
 * core) et 7 (pas de sortie standard) de {@code ArchitectureTest} du module api. Les règles sont recopiées : un WAR ne
 * peut pas dépendre des tests d'un autre WAR.
 */
@AnalyzeClasses(packages = "tn.medimagetrust", importOptions = ImportOption.DoNotIncludeTests.class)
class IamArchitectureTest {

    private static final List<Class<? extends Annotation>> HTTP_METHODS =
            List.of(GET.class, POST.class, PUT.class, DELETE.class, PATCH.class);
    private static final List<Class<? extends Annotation>> SECURITY =
            List.of(RolesAllowed.class, PermitAll.class, DenyAll.class);

    private static final DescribedPredicate<JavaMethod> RESOURCE_METHOD =
            new DescribedPredicate<>("sont des méthodes de ressource REST (publiques d'une classe @Path, ou @GET/@POST/@PUT/@DELETE/@PATCH)") {
                @Override
                public boolean test(JavaMethod m) {
                    boolean http = HTTP_METHODS.stream().anyMatch(m::isAnnotatedWith);
                    boolean publicInPathClass = m.getOwner().isAnnotatedWith(Path.class)
                            && m.getModifiers().contains(JavaModifier.PUBLIC);
                    return http || publicInPathClass;
                }
            };

    private static final ArchCondition<JavaMethod> SECURED =
            new ArchCondition<>("porter @RolesAllowed, @PermitAll ou @DenyAll, sur elles-mêmes ou sur leur classe") {
                @Override
                public void check(JavaMethod m, ConditionEvents events) {
                    boolean ok = SECURITY.stream()
                            .anyMatch(a -> m.isAnnotatedWith(a) || m.getOwner().isAnnotatedWith(a));
                    if (!ok) {
                        events.add(SimpleConditionEvent.violated(m, m.getFullName()
                                + " n'a ni @RolesAllowed, ni @PermitAll, ni @DenyAll (refus par défaut)"));
                    }
                }
            };

    /** Règle 1 — refus par défaut. */
    @ArchTest
    static final ArchRule R1_REFUS_PAR_DEFAUT = methods().that(RESOURCE_METHOD).should(SECURED)
            .because("refus par défaut : une ressource non annotée rend la CI rouge");

    /** Règle 6 — rien ne dépend de iam (en particulier pas le code commun de core). */
    @ArchTest
    static final ArchRule R6_RIEN_NE_DEPEND_DE_IAM = noClasses().that().resideOutsideOfPackage("tn.medimagetrust.iam..")
            .should().dependOnClassesThat().resideInAPackage("tn.medimagetrust.iam..")
            .because("iam est en haut du graphe : rien ne dépend de lui");

    /** Règle 6 — iam ne dépend que de core : jamais du code de api (un WAR ne dépend pas d'un autre WAR). */
    @ArchTest
    static final ArchRule R6_IAM_NE_DEPEND_PAS_DE_API = noClasses().that().resideInAPackage("tn.medimagetrust.iam..")
            .should().dependOnClassesThat().resideInAnyPackage("tn.medimagetrust.crypto..",
                    "tn.medimagetrust.embedding..", "tn.medimagetrust.fingerprint..", "tn.medimagetrust.watermark..",
                    "tn.medimagetrust.audit..", "tn.medimagetrust.api..")
            .because("ce que iam partage avec api va dans core, jamais copié ni importé d'un autre WAR");

    /** Règle 7 — ni System.out, ni System.err, ni printStackTrace() en production. */
    @ArchTest
    static final ArchRule R7_PAS_DE_SORTIE_STANDARD = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
            .because("aucune sortie standard ni trace de pile en production : journalisation contrôlée (SEC07)");
}
