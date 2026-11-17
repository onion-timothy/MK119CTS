package com.onionsoftware.mk119.commtestsuite.db.migration;

import com.onionsoftware.mk119.commtestsuite.db.schema.DbKind;
import com.onionsoftware.mk119.commtestsuite.db.schema.SchemaCheck;
import com.onionsoftware.mk119.commtestsuite.db.schema.SchemaInspector;
import com.onionsoftware.mk119.commtestsuite.db.schema.SqlServerUnicodeColumns;
import com.onionsoftware.mk119.commtestsuite.db.service.DataTypeMigration;
import com.onionsoftware.mk119.commtestsuite.db.service.SimulationService;
import com.onionsoftware.mk119.commtestsuite.db.service.TestPlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * 기동 시 스키마 확인과 데이터 마이그레이션. 장비가 뜨기 전에 ServiceInitializer 가 한 번 부른다.
 * <ul>
 *   <li>단계마다 한 트랜잭션이다. 단계 안에서 실패하면 그 단계는 하나도 반영되지 않는다.
 *       (MariaDB 는 ALTER 에서 커밋되므로 DDL 이 있는 단계는 다시 해도 같은 결과가 나오게 짠다)</li>
 *   <li>단계마다 필요한 테이블·열을 적는다. 없으면 SQL 오류를 내지 않고 SKIPPED 로 남긴다</li>
 *   <li>실패해도 기동은 계속한다 — 기존 장비 시뮬레이션은 돌아야 한다. 대신 {@link StartupReport} 에 남겨
 *       화면 상단 바와 GET /api/system/startup 으로 알린다</li>
 *   <li>모든 단계는 이미 된 것을 다시 하지 않는다. 매 기동마다 불러도 된다</li>
 * </ul>
 */
@Component
public class BootMigrations {
    private static final Logger logger = LoggerFactory.getLogger("agent");

    /** 단계 본문의 결과 */
    public static final class Outcome {
        final int changed;
        final String message;

        private Outcome(int changed, String message) {
            this.changed = changed;
            this.message = message;
        }

        public static Outcome of(int changed, String message) { return new Outcome(changed, message); }

        public static Outcome nothing() { return new Outcome(0, "Nothing to migrate."); }

        /** n 건 바꿨으면 "Migrated n item(s).", 0 이면 nothing */
        public static Outcome count(int n, String what) {
            return n > 0 ? new Outcome(n, "Migrated " + n + " " + what + ".") : nothing();
        }
    }

    @FunctionalInterface
    public interface Body {
        Outcome run() throws Exception;
    }

    private final StartupReport report;
    private final SchemaInspector schemaInspector;
    private final SchemaCheck schemaCheck;
    private final SqlServerUnicodeColumns unicodeColumns;
    private final DataTypeMigration dataTypeMigration;
    private final TestPlanService testPlanService;
    private final SimulationService simulationService;
    private final TransactionTemplate tx;

    public BootMigrations(StartupReport report, SchemaInspector schemaInspector, SchemaCheck schemaCheck,
                          SqlServerUnicodeColumns unicodeColumns, DataTypeMigration dataTypeMigration,
                          TestPlanService testPlanService, SimulationService simulationService,
                          PlatformTransactionManager transactionManager) {
        this.report = report;
        this.schemaInspector = schemaInspector;
        this.schemaCheck = schemaCheck;
        this.unicodeColumns = unicodeColumns;
        this.dataTypeMigration = dataTypeMigration;
        this.testPlanService = testPlanService;
        this.simulationService = simulationService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 모든 단계를 순서대로. 예외를 밖으로 내지 않는다 */
    public StartupReport runAll() {
        DbKind kind;
        try {
            kind = schemaInspector.kind();
        } catch (RuntimeException e) {
            logger.error("Cannot read database metadata at boot.", e);
            kind = DbKind.OTHER;
        }
        report.reset(kind.name());

        // 1) SQL Server 의 예전 varchar·text 열을 nvarchar 로. 엔티티가 읽기 전에 해야 한다
        if (kind == DbKind.SQLSERVER) {
            run("Unicode text columns", Collections.emptyList(), () -> {
                List<String> done = unicodeColumns.convert(schemaCheck.expected().keySet());
                return done.isEmpty() ? Outcome.of(0, "All text columns are already Unicode.")
                        : Outcome.of(done.size(), "Converted " + done.size() + " column(s) to Unicode.");
            });
        }

        // 2) 엔티티가 쓰는 테이블·열이 다 있는지
        try {
            List<String> problems = schemaCheck.problems();
            report.setSchemaProblems(problems);
            for (String p : problems) logger.error("Schema check: {} The screens that use it will fail.", p);
        } catch (RuntimeException e) {
            logger.error("Schema check failed.", e);
            report.setSchemaProblems(Collections.singletonList("Schema check could not run."));
        }

        // 3) 데이터. data_type 은 Test Plan 스케줄과 장비가 읽기 전에 맞춘다
        run("Data types of simulations",
                Arrays.asList("devices.protocol", "simulations.device_id", "simulations.target", "simulations.policies"),
                () -> Outcome.count(dataTypeMigration.migrateSimulations(), "simulation(s)"));
        run("Data types of test plan actions",
                Arrays.asList("devices.protocol", "test_plan_actions.device_id", "test_plan_actions.target",
                        "test_plan_actions.value", "test_plan_actions.data_type"),
                () -> Outcome.count(dataTypeMigration.migrateTestPlanActions(), "test plan action(s)"));
        // interval 로 저장된 예전 Test Plan 은 지운다(실행 시각으로 바뀜). 스케줄을 등록하기 전에 해야 한다
        run("Interval-based test plans",
                Arrays.asList("test_plans", "test_plan_actions", "test_plan_runs", "test_plan_run_actions"),
                () -> {
                    int n = testPlanService.dropIntervalPlans();
                    return n < 0 ? Outcome.nothing() : Outcome.of(n, "Removed " + n + " test plan(s) saved with intervals.");
                });
        // skip_on_failure(실패해도 계속) → stop_on_failure(실패하면 멈춤)
        run("Stop on failure of test plan actions",
                Collections.singletonList("test_plan_actions.stop_on_failure"),
                () -> {
                    int n = testPlanService.migrateSkipToStop();
                    return n < 0 ? Outcome.nothing() : Outcome.count(n, "test plan action(s)");
                });
        // 예전 형식(REAL@TAG1)의 EtherNet/IP target 을 태그 이름 + data_type 으로. 장비가 뜨기 전에 해야 한다
        run("EtherNet/IP simulation targets",
                Arrays.asList("devices.protocol", "simulations.target", "simulations.policies"),
                () -> Outcome.count(simulationService.migrateEthernetIpTargets(), "simulation target(s)"));

        report.finish();
        long failed = report.getSteps().stream().filter(s -> s.getStatus() == StartupReport.Status.FAILED).count();
        long skipped = report.getSteps().stream().filter(s -> s.getStatus() == StartupReport.Status.SKIPPED).count();
        int problems = report.getSchemaProblems().size();
        String summary = String.format("Startup checks on %s: %d step(s), %d failed, %d skipped, %d schema problem(s).",
                kind, report.getSteps().size(), failed, skipped, problems);
        if (failed + skipped + problems > 0) logger.error(summary);
        else logger.info(summary);
        return report;
    }

    /**
     * 단계 하나를 한 트랜잭션으로. 필요한 테이블·열이 없으면 SKIPPED, 예외가 나면 되돌리고 FAILED.
     *
     * @param requires "table" 또는 "table.column"
     */
    public StartupReport.Step run(String name, List<String> requires, Body body) {
        long t0 = System.currentTimeMillis();
        StartupReport.Step step;
        try {
            String missing = firstMissing(requires);
            if (missing != null) {
                step = new StartupReport.Step(name, StartupReport.Status.SKIPPED, 0, "Needs " + missing + ".",
                        System.currentTimeMillis() - t0);
                logger.warn("Startup step \"{}\" skipped: needs {}.", name, missing);
            } else {
                Outcome o = tx.execute(status -> {
                    try {
                        return body.run();
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                step = new StartupReport.Step(name, StartupReport.Status.OK, o.changed, o.message,
                        System.currentTimeMillis() - t0);
                if (o.changed > 0) logger.info("Startup step \"{}\": {}", name, o.message);
            }
        } catch (RuntimeException e) {
            step = new StartupReport.Step(name, StartupReport.Status.FAILED, 0, rootMessage(e) + " Nothing was changed.",
                    System.currentTimeMillis() - t0);
            logger.error("Startup step \"{}\" failed and was rolled back.", name, e);
        }
        report.add(step);
        return step;
    }

    private String firstMissing(List<String> requires) {
        if (requires.isEmpty()) return null;
        Map<String, Map<String, SchemaInspector.Column>> cols = schemaInspector.columns();
        for (String r : requires) {
            String[] tc = r.toLowerCase(Locale.ROOT).split("\\.", 2);
            Map<String, SchemaInspector.Column> table = cols.get(tc[0]);
            if (table == null) return "table " + tc[0];
            if (tc.length == 2 && !table.containsKey(tc[1])) return "column " + r;
        }
        return null;
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
