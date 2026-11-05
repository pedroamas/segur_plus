package com.segurplus.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.time.Clock;
import java.util.Set;

/** Reglas de ADR 0001 y ADR 0006: paquetes sin ciclos, capas, sin lecturas del reloj fuera de shared, repositorios no públicos. */
@AnalyzeClasses(packages = "com.segurplus", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String SHARED = "com.segurplus.shared..";
    private static final String AUDIT = "com.segurplus.audit..";
    private static final String AUTH = "com.segurplus.auth..";
    private static final String DOCUMENTS = "com.segurplus.documents..";
    private static final String DATA = "com.segurplus.data..";
    private static final String AI = "com.segurplus.ai..";

    // --- dependencias ---

    @ArchTest
    static final ArchRule packagesHaveNoCycles = slices().matching("com.segurplus.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule sharedDependsOnNothingElse = noClasses().that().resideInAPackage(SHARED)
            .should().dependOnClassesThat().resideInAnyPackage(AUTH, AUDIT, DOCUMENTS, DATA, AI);

    @ArchTest
    static final ArchRule auditOnlyUsesShared = noClasses().that().resideInAPackage(AUDIT)
            .should().dependOnClassesThat().resideInAnyPackage(AUTH, DOCUMENTS, DATA, AI);

    @ArchTest
    static final ArchRule authOnlyUsesAuditAndShared = noClasses().that().resideInAPackage(AUTH)
            .should().dependOnClassesThat().resideInAnyPackage(DOCUMENTS, DATA, AI);

    @ArchTest
    static final ArchRule documentsAndDataDoNotUseAi = noClasses().that().resideInAnyPackage(DOCUMENTS, DATA)
            .should().dependOnClassesThat().resideInAPackage(AI);

    @ArchTest
    static final ArchRule documentsAndDataAreIndependent = noClasses().that().resideInAPackage(DOCUMENTS)
            .should().dependOnClassesThat().resideInAPackage(DATA);

    // --- reloj (ADR 0006) ---

    private static final Set<String> TIME_TYPES = Set.of("java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime",
            "java.time.LocalTime", "java.time.ZonedDateTime", "java.time.OffsetDateTime", "java.time.OffsetTime", "java.time.Year",
            "java.time.YearMonth");

    private static boolean readsSystemClock(JavaCall<?> call) {
        String owner = call.getTargetOwner().getFullName();
        String name = call.getName();
        if (TIME_TYPES.contains(owner) && name.equals("now")) {
            // now(Clock) es correcto: el reloj se inyecta. now() y now(ZoneId) leen el reloj del sistema.
            return call.getTarget().getRawParameterTypes().stream().noneMatch(t -> t.isEquivalentTo(Clock.class));
        }
        if (owner.equals("java.lang.System") && (name.equals("currentTimeMillis"))) {
            return true;
        }
        if (owner.equals(Clock.class.getName()) && (name.startsWith("system"))) {
            return true;
        }
        return owner.equals("java.util.Date") && name.equals("<init>") && call.getTarget().getRawParameterTypes().isEmpty();
    }

    private static final ArchCondition<JavaClass> notReadTheSystemClock = new ArchCondition<>("not read the system clock") {
        @Override
        public void check(JavaClass item, ConditionEvents events) {
            item.getCodeUnitCallsFromSelf().stream().filter(ArchitectureTest::readsSystemClock).forEach(call -> events.add(
                    SimpleConditionEvent.violated(call, call.getDescription())));
        }
    };

    /**
     * Excepciones documentadas: {@code shared} (el Clock de negocio) y {@code auth.TokenTime} (expiración del JWT y del
     * refresh con tiempo real, ADR 0006 §2).
     */
    @ArchTest
    static final ArchRule noSystemClockOutsideSharedAndTokenTime = classes()
            .that(DescribedPredicate.describe("are outside shared and are not auth.TokenTime",
                    c -> !c.getPackageName().startsWith("com.segurplus.shared") && !c.getName().equals("com.segurplus.auth.TokenTime")))
            .should(notReadTheSystemClock);

    // --- capas ---

    @ArchTest
    static final ArchRule repositoriesAreNotPublic = classes().that().areAssignableTo(org.springframework.data.repository.Repository.class)
            .should().notBePublic();

    @ArchTest
    static final ArchRule entitiesAreNotPublic = classes().that().areAnnotatedWith(jakarta.persistence.Entity.class).should().notBePublic();

    @ArchTest
    static final ArchRule apiLayerNeverTouchesEntitiesOrRepositories = noClasses().that().resideInAPackage("..api..")
            .should().dependOnClassesThat().areAnnotatedWith(jakarta.persistence.Entity.class)
            .orShould().dependOnClassesThat().areAssignableTo(org.springframework.data.repository.Repository.class);

    @ArchTest
    static final ArchRule controllersLiveInApiPackages = classes().that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should().resideInAPackage("..api..");

    @ArchTest
    static final ArchRule controllersDoNotUseTheSecurityContextOrRequestIdentityDirectly = noClasses()
            .that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.security.core.context.SecurityContextHolder");

    // --- datos sensibles ---

    @ArchTest
    static final ArchRule noSalaryInDtosOrEntities = fields().that().areDeclaredInClassesThat().resideInAPackage("..api..")
            .or().areDeclaredInClassesThat().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().haveNameNotMatching("(?i).*salary.*")
            .because("users.monthly_salary no sale en ningún DTO, endpoint ni herramienta (solo la semilla de desarrollo la escribe por SQL)");
}
