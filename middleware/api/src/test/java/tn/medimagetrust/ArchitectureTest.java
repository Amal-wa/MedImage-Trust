package tn.medimagetrust;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
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
import java.security.SecureRandom;
import java.util.List;
import java.util.Random;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Tests d'architecture du module api (exigence : « test d'architecture sur les annotations de sécurité »).
 *
 * <p>Depuis la fusion des six anciens modules Maven en un seul module api, ce sont ces règles qui gardent les
 * frontières que Maven imposait. Seul le code de production est analysé ({@link ImportOption.DoNotIncludeTests}) ;
 * la classe {@code tn.medimagetrust.common.web} de core est incluse (jar sur le classpath).</p>
 */
@AnalyzeClasses(packages = "tn.medimagetrust", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final String ROOT = "tn.medimagetrust";
    static final String CRYPTO = ROOT + ".crypto..";
    static final String EMBEDDING = ROOT + ".embedding..";
    static final String FINGERPRINT = ROOT + ".fingerprint..";
    static final String WATERMARK = ROOT + ".watermark..";
    static final String AUDIT = ROOT + ".audit..";
    static final String API_REST = ROOT + ".api.rest..";
    static final String IAM = ROOT + ".iam..";
    static final String COMMON = ROOT + ".common..";

    private static final List<Class<? extends Annotation>> HTTP_METHODS =
            List.of(GET.class, POST.class, PUT.class, DELETE.class, PATCH.class);
    private static final List<Class<? extends Annotation>> SECURITY =
            List.of(RolesAllowed.class, PermitAll.class, DenyAll.class);

    /** Méthode publique d'une classe {@code @Path}, ou méthode annotée par un verbe HTTP. */
    static final DescribedPredicate<JavaMethod> RESOURCE_METHOD =
            new DescribedPredicate<>("sont des méthodes de ressource REST (publiques d'une classe @Path, ou @GET/@POST/@PUT/@DELETE/@PATCH)") {
                @Override
                public boolean test(JavaMethod m) {
                    boolean http = HTTP_METHODS.stream().anyMatch(m::isAnnotatedWith);
                    boolean publicInPathClass = m.getOwner().isAnnotatedWith(Path.class)
                            && m.getModifiers().contains(JavaModifier.PUBLIC);
                    return http || publicInPathClass;
                }
            };

    /** {@code @RolesAllowed}, {@code @PermitAll} ou {@code @DenyAll}, sur la méthode ou sur sa classe. */
    static final ArchCondition<JavaMethod> SECURED =
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

    /** Règle 1 — refus par défaut : aucune méthode de ressource sans annotation de sécurité. */
    @ArchTest
    static final ArchRule R1_REFUS_PAR_DEFAUT = methods().that(RESOURCE_METHOD).should(SECURED)
            .because("refus par défaut : une ressource non annotée rend la CI rouge");

    /** Aucune dépendance vers un package du projet hors de {@code allowed}. */
    static ArchRule onlyDependsOn(String pkg, String reason, String... allowed) {
        DescribedPredicate<JavaClass> forbidden = resideInAPackage(ROOT + "..").and(not(resideInAnyPackage(allowed)));
        return noClasses().that().resideInAPackage(pkg).should().dependOnClassesThat(forbidden).because(reason);
    }

    /** Règle 2 — embedding ne connaît aucune clé : aucune dépendance vers un autre package du projet. */
    @ArchTest
    static final ArchRule R2_EMBEDDING_ISOLE = onlyDependsOn(EMBEDDING,
            "embedding ne connaît aucune clé (dossier v2.4) : ni crypto ni aucun autre package du projet", EMBEDDING);

    /** Règle 3 — crypto ne dépend d'aucun autre package du projet. */
    @ArchTest
    static final ArchRule R3_CRYPTO_ISOLE = onlyDependsOn(CRYPTO,
            "crypto est la base : il ne dépend d'aucun autre package du projet", CRYPTO);

    /** Règle 4 — audit ne dépend que de crypto. */
    @ArchTest
    static final ArchRule R4_AUDIT = onlyDependsOn(AUDIT,
            "audit ne dépend que de crypto (cahier des charges §7.3)", AUDIT, CRYPTO);

    /** Règle 5 — fingerprint ne dépend que de crypto et d'embedding (donc pas de watermark). */
    @ArchTest
    static final ArchRule R5_FINGERPRINT = onlyDependsOn(FINGERPRINT,
            "fingerprint ne dépend que de crypto et d'embedding, jamais de watermark", FINGERPRINT, CRYPTO, EMBEDDING);

    /** Règle 5 — watermark ne dépend que de crypto et d'embedding (donc pas de fingerprint). */
    @ArchTest
    static final ArchRule R5_WATERMARK = onlyDependsOn(WATERMARK,
            "watermark ne dépend que de crypto et d'embedding, jamais de fingerprint", WATERMARK, CRYPTO, EMBEDDING);

    /** Règle 6 — rien ne dépend de api.rest ni de iam : seul api.rest est en haut du graphe. */
    @ArchTest
    static final ArchRule R6_RIEN_NE_DEPEND_DE_API_REST = noClasses().that().resideOutsideOfPackage(API_REST)
            .should().dependOnClassesThat().resideInAnyPackage(API_REST, IAM)
            .because("seul api.rest peut dépendre de tous les packages ; rien ne dépend de api.rest ni de iam");

    /** Règle 6 — le code commun (core) ne dépend d'aucun package métier. */
    @ArchTest
    static final ArchRule R6_COMMUN_ISOLE = onlyDependsOn(COMMON,
            "core est partagé par api et iam : il ne dépend d'aucun package métier", COMMON);

    /** Règle 7 — ni System.out, ni System.err, ni printStackTrace() en production. */
    @ArchTest
    static final ArchRule R7_PAS_DE_SORTIE_STANDARD = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
            .because("aucune sortie standard ni trace de pile en production : journalisation contrôlée (SEC07)");

    /** Règle 8 — java.util.Random interdit dans crypto, fingerprint et watermark : seul SecureRandom est accepté. */
    @ArchTest
    static final ArchRule R8_PAS_DE_RANDOM = noClasses().that().resideInAnyPackage(CRYPTO, FINGERPRINT, WATERMARK)
            .should().dependOnClassesThat(assignableTo(Random.class).and(not(assignableTo(SecureRandom.class))))
            .because("seul SecureRandom est accepté pour l'aléa des clés, nonces et marques");

    /** Règle 9 — aucun cycle entre les packages tn.medimagetrust.*. */
    @ArchTest
    static final ArchRule R9_SANS_CYCLE = slices().matching(ROOT + ".(*)..").should().beFreeOfCycles();
}
