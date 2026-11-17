package com.onionsoftware.mk119.commtestsuite.db;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.onionsoftware.mk119.commtestsuite.Mk119CommunicationTestSuiteApplication;
import com.onionsoftware.mk119.commtestsuite.db.bean.*;
import com.onionsoftware.mk119.commtestsuite.db.migration.BootMigrations;
import com.onionsoftware.mk119.commtestsuite.db.migration.StartupReport;
import com.onionsoftware.mk119.commtestsuite.db.repository.*;
import com.onionsoftware.mk119.commtestsuite.db.schema.SchemaInspector;
import com.onionsoftware.mk119.commtestsuite.enums.Protocol;
import com.onionsoftware.mk119.commtestsuite.enums.Transport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 두 DB(MariaDB·SQL Server)에서 같은 동작을 하는지 앱을 실제로 띄워 본다. ./gradlew dbTest 로 돌린다(기본 test 에는 없다).
 * <p>
 * DB 마다 임시 DB 를 만들고 1.x 스키마(src/test/resources/db-it)와 대표 데이터를 넣은 뒤 앱을 띄운다.
 * 기본 대상은 로컬 Docker 의 MariaDB(localhost:3306, root)와 SQL Server(localhost:1433, sa)다.
 * 바꾸려면 -Dcts.it.mariadb.url=… -Dcts.it.sqlserver.url=… -Dcts.it.user.mariadb=… -Dcts.it.password=… 를 준다.
 * -Dcts.it.keep=true 면 끝나고 임시 DB 를 지우지 않는다.
 * <p>
 * 결과는 케이스마다 한 줄씩 표준 출력과 build/db-it/report-{db}.md 에 남긴다.
 */
@Tag("db")
class DbPortabilityIT {

    enum Db {
        MARIADB("jdbc:mariadb://localhost:3306/", "root", "org.mariadb.jdbc.Driver"),
        SQLSERVER("jdbc:sqlserver://localhost:1433;encrypt=false", "sa", "com.microsoft.sqlserver.jdbc.SQLServerDriver");

        final String defaultUrl, defaultUser, driver;

        Db(String defaultUrl, String defaultUser, String driver) {
            this.defaultUrl = defaultUrl;
            this.defaultUser = defaultUser;
            this.driver = driver;
        }

        String key() { return name().toLowerCase(Locale.ROOT); }
        String adminUrl() { return System.getProperty("cts.it." + key() + ".url", defaultUrl); }
        String user() { return System.getProperty("cts.it.user." + key(), defaultUser); }
        String password() { return System.getProperty("cts.it.password", "midknight1!"); }

        String dbUrl(String db) {
            return this == MARIADB ? adminUrl() + db : adminUrl() + ";databaseName=" + db;
        }

        String q(String id) { return this == MARIADB ? "`" + id + "`" : "[" + id + "]"; }
    }

    /** 1.x 데이터의 기대 결과: 시뮬레이션 id → {target, data_type} */
    private static final Map<Integer, String[]> EXPECTED = new LinkedHashMap<>();
    static {
        EXPECTED.put(100, new String[]{"3_1", "FP32AD"});      // Modbus 레지스터는 그대로
        EXPECTED.put(101, new String[]{"1_1", "BINARY"});      // Modbus 코일 → BINARY
        EXPECTED.put(102, new String[]{".1.3.6.1.4.1.9999.1", "FLOAT"});
        EXPECTED.put(103, new String[]{".1.3.6.1.4.1.9999.2", "INTEGER"});
        EXPECTED.put(104, new String[]{".1.3.6.1.4.1.9999.3", "FLOAT"});   // 정수 타입이지만 값에 소수
        EXPECTED.put(105, new String[]{".1.3.6.1.4.1.9999.4", "BINARY"});  // 없어진 BOOL
        EXPECTED.put(106, new String[]{"binary_value 2", "BINARY"});
        EXPECTED.put(107, new String[]{"analog_input 13", "FLOAT"});
        EXPECTED.put(108, new String[]{"TAG1", "FP32DA"});     // EtherNet/IP 예전 REAL@TAG1
        EXPECTED.put(109, new String[]{"TAG2", "SI32DA"});     // EtherNet/IP 예전 DINT@TAG2
    }

    /** MariaDB 가 저장하는 글자. CP949 밖의 글자(베트남어 성조, 이모지)가 들어 있다 */
    private static final String UNICODE = "Nhóm thử 😀 한글 ℃";

    private final List<String> lines = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private Db db;

    @Test
    void mariadb() throws Exception {
        scenario(Db.MARIADB);
    }

    @Test
    void sqlserver() throws Exception {
        scenario(Db.SQLSERVER);
    }

    // ── 시나리오 ─────────────────────────────────────────────

    private void scenario(Db db) throws Exception {
        this.db = db;
        String name = "cts_it_" + db.key() + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        JdbcTemplate admin = new JdbcTemplate(ds(db.adminUrl()));
        createDb(admin, name);
        ConfigurableApplicationContext ctx = null;
        try {
            JdbcTemplate j = new JdbcTemplate(ds(db.dbUrl(name)));
            loadFixture(name);
            Map<String, Map<String, SchemaInspector.Column>> colsBefore = new SchemaInspector(j).columns();
            Map<String, Map<Object, Map<String, Object>>> before = snapshot(j);

            // 1. 첫 기동
            section("1. 1.x DB 에 처음 기동", "devices 4(Modbus·SNMP·BACnet·EtherNet/IP, 모두 꺼짐), simulations 10, groups 2, errors 1");
            ctx = boot(name);
            StartupReport r = ctx.getBean(StartupReport.class);
            check(r.getProblems().isEmpty(), "기동 확인에 문제가 없다", "[]", r.getProblems());
            for (StartupReport.Step s : r.getSteps()) {
                check(s.getStatus() == StartupReport.Status.OK, "단계 \"" + s.getName() + "\" 가 OK", "OK",
                        s.getStatus() + " " + s.getChanged() + " " + s.getMessage());
            }
            checkChanged(r, "Data types of simulations", 9);
            checkChanged(r, "EtherNet/IP simulation targets", 2);
            if (db == Db.SQLSERVER) {
                long narrow = narrowColumns(colsBefore).size();
                checkChanged(r, "Unicode text columns", (int) narrow);
            } else {
                check(r.getSteps().stream().noneMatch(s -> s.getName().equals("Unicode text columns")),
                        "MariaDB 에서는 열 전환 단계를 돌리지 않는다", "없음", names(r));
            }

            // 2. 스키마
            section("2. 스키마", "엔티티의 테이블·열과 문자열 열 타입");
            Map<String, Map<String, SchemaInspector.Column>> colsAfter = new SchemaInspector(j).columns();
            check(colsAfter.containsKey("protocol_definitions"), "protocol_definitions 테이블이 만들어진다", "있음",
                    colsAfter.containsKey("protocol_definitions") ? "있음" : "없음");
            SchemaInspector.Column content = colsAfter.getOrDefault("protocol_definitions", Collections.emptyMap()).get("content");
            if (db == Db.SQLSERVER) {
                check(narrowColumns(colsAfter).isEmpty(), "앱 테이블에 varchar·char·text 열이 남지 않는다", "[]", narrowColumns(colsAfter));
                check(content != null && "nvarchar".equalsIgnoreCase(content.type), "protocol_definitions.content 는 nvarchar(MAX)",
                        "nvarchar", content);
            } else {
                List<String> changedTypes = new ArrayList<>();
                for (Map.Entry<String, Map<String, SchemaInspector.Column>> t : colsBefore.entrySet()) {
                    for (SchemaInspector.Column c : t.getValue().values()) {
                        SchemaInspector.Column a = colsAfter.get(t.getKey()).get(c.name.toLowerCase(Locale.ROOT));
                        if (a == null || !a.type.equalsIgnoreCase(c.type) || a.size != c.size) changedTypes.add(c + " -> " + a);
                    }
                }
                check(changedTypes.isEmpty(), "MariaDB 의 기존 열 타입은 그대로다", "[]", changedTypes);
                check(content != null && "longtext".equalsIgnoreCase(content.type), "protocol_definitions.content 는 longtext",
                        "LONGTEXT", content);
            }

            // 3. 데이터
            section("3. 데이터 보존과 data_type", "기동 전후 행 비교");
            Map<String, Map<Object, Map<String, Object>>> after = snapshot(j);
            for (String t : Arrays.asList("groups", "devices", "errors")) {
                check(before.get(t).equals(after.get(t)), t + " 의 모든 행이 그대로다", before.get(t).size() + "행 동일",
                        diff(before.get(t), after.get(t)));
            }
            check(before.get("simulations").keySet().equals(after.get("simulations").keySet()), "simulations 행 수·id 가 그대로다",
                    before.get("simulations").keySet(), after.get("simulations").keySet());
            List<String> otherChanges = new ArrayList<>();
            for (Object id : before.get("simulations").keySet()) {
                Map<String, Object> b = before.get("simulations").get(id), a = after.get("simulations").get(id);
                for (String col : Arrays.asList("name", "enabled", "interval", "device_id", "stop_on_control")) {
                    if (!Objects.equals(b.get(col), a.get(col))) otherChanges.add(id + "." + col + ": " + b.get(col) + " -> " + a.get(col));
                }
                if (!policiesWithoutType(b.get("policies")).equals(policiesWithoutType(a.get("policies")))) {
                    otherChanges.add(id + ".policies: " + b.get("policies") + " -> " + a.get("policies"));
                }
                String[] exp = EXPECTED.get(((Number) id).intValue());
                String target = String.valueOf(a.get("target")), type = dataTypeOf(a.get("policies"));
                check(exp[0].equals(target) && exp[1].equals(type),
                        "시뮬레이션 " + id + " (" + b.get("target") + ", " + dataTypeOf(b.get("policies")) + ")",
                        exp[0] + ", " + exp[1], target + ", " + type);
            }
            check(otherChanges.isEmpty(), "data_type·EtherNet/IP target 말고는 바뀐 값이 없다", "[]", otherChanges);

            JdbcTemplate app = ctx.getBean(JdbcTemplate.class);
            // 4. UNIQUE 제약
            section("4. UNIQUE 제약", "같은 이름을 JDBC 로 넣어 본다");
            check(fails(() -> app.update("INSERT INTO devices (name, protocol, transport, enabled) VALUES ('MB-1', 2, 1, 0)")),
                    "장비 이름 중복(MB-1)은 거절된다", "거절", "");
            check(fails(() -> app.update("INSERT INTO simulations (enabled, name, stop_on_control, device_id) VALUES (0, 's100', 0, 10)")),
                    "같은 장비의 시뮬레이션 이름 중복(s100)은 거절된다", "거절", "");

            // 5. 유니코드
            section("5. 유니코드 저장", "JPA 로 저장하고 JDBC·JPA 로 다시 읽는다: \"" + UNICODE + "\"");
            unicodeRoundTrip(ctx, app);

            // 6. 화면·API
            section("6. 상단 바와 API", "GET /devices, GET /api/system/startup");
            int port = Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port"));
            String page = get(port, "/devices"), api = get(port, "/api/system/startup");
            check(!page.contains("id=\"startupAlert\""), "문제가 없으면 상단 바에 알림이 없다", "없음",
                    page.contains("id=\"startupAlert\"") ? "있음" : "없음");
            check(api.contains("\"ok\":true") && api.contains("\"steps\""), "API 가 ok=true 와 단계 목록을 준다", "ok:true",
                    api.length() > 200 ? api.substring(0, 200) + "…" : api);

            // 7. 롤백
            section("7. 단계 롤백", "단계 안에서 바꾼 뒤 예외를 던진다");
            BootMigrations bm = ctx.getBean(BootMigrations.class);
            StartupReport.Step s = bm.run("rollback probe", Collections.emptyList(), () -> {
                app.update("UPDATE groups SET name = 'changed' WHERE id = 2");
                throw new IllegalStateException("probe");
            });
            String g2 = app.queryForObject("SELECT name FROM groups WHERE id = 2", String.class);
            check(s.getStatus() == StartupReport.Status.FAILED && "한글 그룹".equals(g2), "실패한 단계의 UPDATE 는 되돌아간다",
                    "FAILED, 한글 그룹", s.getStatus() + ", " + g2);
            s = bm.run("ddl probe", Collections.emptyList(), () -> {
                app.execute("ALTER TABLE groups ADD probe_col int NULL");
                throw new IllegalStateException("probe");
            });
            boolean probeCol = new SchemaInspector(app).hasColumn("groups", "probe_col");
            if (db == Db.SQLSERVER) {
                check(!probeCol, "SQL Server 는 실패한 단계의 DDL 도 되돌아간다", "열 없음", probeCol ? "열 있음" : "열 없음");
            } else {
                check(probeCol, "MariaDB 는 DDL 에서 커밋된다(그래서 DDL 단계는 다시 해도 같게 짠다)", "열 있음",
                        probeCol ? "열 있음" : "열 없음");
                app.execute("ALTER TABLE groups DROP COLUMN probe_col");
            }

            // 8. Test Plan 예전 열
            section("8. Test Plan 예전 열 정리", "개발 빌드(2026-10-23~28)가 남긴 interval_seconds·skip_on_failure");
            devTestPlanColumns(ctx, app, bm);

            ctx.close();
            ctx = null;

            // 9. 두 번째 기동
            section("9. 두 번째 기동", "같은 DB 로 다시 띄운다");
            ctx = boot(name);
            r = ctx.getBean(StartupReport.class);
            check(r.getProblems().isEmpty(), "문제가 없다", "[]", r.getProblems());
            List<String> changed = r.getSteps().stream().filter(x -> x.getChanged() != 0 || x.getStatus() != StartupReport.Status.OK)
                    .map(x -> x.getName() + " " + x.getStatus() + " " + x.getChanged()).collect(Collectors.toList());
            check(changed.isEmpty(), "모든 단계가 OK 이고 바꾼 것이 0건이다", "[]", changed);

            // 10. 스키마 누락 감지
            section("10. 스키마 누락 감지", "protocol_definitions.description 열을 지우고 확인을 다시 돌린다");
            // 첫 컨텍스트의 연결 풀은 닫혔다. 새 컨텍스트 것을 쓴다
            ctx.getBean(JdbcTemplate.class).execute("ALTER TABLE protocol_definitions DROP COLUMN description");
            r = ctx.getBean(BootMigrations.class).runAll();
            check(r.getProblems().contains("Column protocol_definitions.description is missing."), "빠진 열을 이름까지 알린다",
                    "Column protocol_definitions.description is missing.", r.getProblems());
            port = Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port"));
            page = get(port, "/devices");
            check(page.contains("id=\"startupAlert\"") && page.contains("protocol_definitions.description"),
                    "모든 화면 상단 바에 알림이 뜬다(/devices)", "startupAlert 와 열 이름",
                    page.contains("id=\"startupAlert\"") ? "있음" : "없음");
        } finally {
            if (ctx != null) ctx.close();
            if (!Boolean.getBoolean("cts.it.keep")) dropDb(admin, name);
            writeReport(name);
        }
        assertTrue(failures.isEmpty(), "실패 " + failures.size() + "건:\n" + String.join("\n", failures));
    }

    private void unicodeRoundTrip(ConfigurableApplicationContext ctx, JdbcTemplate app) {
        Group g = new Group();
        g.setName(UNICODE);
        g.setParentId(1);
        g = ctx.getBean(GroupRepository.class).save(g);
        roundTrip("groups.name", app.queryForObject("SELECT name FROM groups WHERE id = ?", String.class, g.getId()),
                ctx.getBean(GroupRepository.class).findById(g.getId()).map(Group::getName).orElse(null), UNICODE);

        Device d = new Device();
        d.setName("Thiết bị 😀");
        d.setProtocol(Protocol.SNMP);
        d.setTransport(Transport.UDP);
        d.setHost("0.0.0.0");
        d.setPort(15005);
        d.setEnabled(0);
        d.setLog(0);
        d.setGroup(g);
        Map<Object, Object> cs = new LinkedHashMap<>();
        cs.put("snmpVersion", "0");
        cs.put("getCommunity", "công 😀");
        cs.put("setCommunity", "private");
        d.setCommunicationSettings(cs);
        d = ctx.getBean(DeviceRepository.class).save(d);
        roundTrip("devices.name", app.queryForObject("SELECT name FROM devices WHERE id = ?", String.class, d.getId()),
                ctx.getBean(DeviceRepository.class).findById(d.getId()).map(Device::getName).orElse(null), "Thiết bị 😀");
        String csRaw = app.queryForObject("SELECT communication_settings FROM devices WHERE id = ?", String.class, d.getId());
        check(csRaw != null && csRaw.contains("công 😀"), "devices.communication_settings(@Lob) 에 \"công 😀\" 이 그대로 남는다",
                "công 😀 포함", csRaw);

        Simulation sim = new Simulation();
        sim.setDevice(d);
        sim.setName("Mô phỏng 😀");
        sim.setTarget(".1.3.6.1.4.1.9999.9 ℃");
        sim.setPolicies(new ArrayList<>());
        sim.setInterval(1);
        sim.setEnabled(0);
        sim = ctx.getBean(SimulationRepository.class).save(sim);
        roundTrip("simulations.name", app.queryForObject("SELECT name FROM simulations WHERE id = ?", String.class, sim.getId()),
                null, "Mô phỏng 😀");
        roundTrip("simulations.target(@Lob)", app.queryForObject("SELECT target FROM simulations WHERE id = ?", String.class, sim.getId()),
                null, ".1.3.6.1.4.1.9999.9 ℃");

        ProtocolDefinition pd = new ProtocolDefinition();
        pd.setName("Định nghĩa 😀");
        pd.setDescription("Mô tả 한글");
        pd.setContent("{\"doc\":\"Tiếng Việt 😀 한글\"}");
        pd.setCreatedAt(LocalDateTime.now());
        pd.setUpdatedAt(LocalDateTime.now());
        pd = ctx.getBean(ProtocolDefinitionRepository.class).save(pd);
        roundTrip("protocol_definitions.content(@Lob)",
                app.queryForObject("SELECT content FROM protocol_definitions WHERE id = ?", String.class, pd.getId()),
                ctx.getBean(ProtocolDefinitionRepository.class).findById(pd.getId()).map(ProtocolDefinition::getContent).orElse(null),
                "{\"doc\":\"Tiếng Việt 😀 한글\"}");
        roundTrip("protocol_definitions.description",
                app.queryForObject("SELECT description FROM protocol_definitions WHERE id = ?", String.class, pd.getId()),
                null, "Mô tả 한글");
    }

    private void devTestPlanColumns(ConfigurableApplicationContext ctx, JdbcTemplate app, BootMigrations bm) {
        TestPlanRepository plans = ctx.getBean(TestPlanRepository.class);
        // 8-1 interval_seconds 가 있으면 Plan 을 지우고 열도 지운다
        app.execute("ALTER TABLE test_plan_actions ADD interval_seconds int NULL");
        app.execute("ALTER TABLE test_plan_actions ADD skip_on_failure int NULL");
        plans.save(plan("it-plan-1", 0));
        app.update("UPDATE test_plan_actions SET interval_seconds = 5, skip_on_failure = 0");
        StartupReport r = bm.runAll();
        StartupReport.Step interval = step(r, "Interval-based test plans"), skip = step(r, "Stop on failure of test plan actions");
        SchemaInspector si = new SchemaInspector(app);
        Integer count = app.queryForObject("SELECT COUNT(*) FROM test_plans", Integer.class);
        check(interval.getStatus() == StartupReport.Status.OK && interval.getChanged() == 1 && count == 0
                        && !si.hasColumn("test_plan_actions", "interval_seconds"),
                "interval_seconds 가 있으면 Plan 1건을 지우고 열을 지운다", "OK 1건, Plan 0, 열 없음",
                interval.getStatus() + " " + interval.getChanged() + "건, Plan " + count + ", 열 "
                        + (si.hasColumn("test_plan_actions", "interval_seconds") ? "있음" : "없음"));
        check(skip.getStatus() == StartupReport.Status.OK && !si.hasColumn("test_plan_actions", "skip_on_failure"),
                "같은 기동에서 skip_on_failure 열도 지운다", "OK, 열 없음",
                skip.getStatus() + ", 열 " + (si.hasColumn("test_plan_actions", "skip_on_failure") ? "있음" : "없음"));

        // 8-2 skip_on_failure 만 있으면 stop_on_failure = 1 - skip_on_failure 로 옮긴다
        app.execute("ALTER TABLE test_plan_actions ADD skip_on_failure int NULL");
        plans.save(plan("it-plan-2", 0));
        app.update("UPDATE test_plan_actions SET skip_on_failure = 0");
        r = bm.runAll();
        skip = step(r, "Stop on failure of test plan actions");
        Integer stop = app.queryForObject("SELECT stop_on_failure FROM test_plan_actions", Integer.class);
        check(skip.getStatus() == StartupReport.Status.OK && skip.getChanged() == 1 && stop == 1
                        && !si.hasColumn("test_plan_actions", "skip_on_failure"),
                "skip_on_failure=0 인 Action 은 stop_on_failure=1 이 되고 열은 지워진다", "OK 1건, stop_on_failure=1, 열 없음",
                skip.getStatus() + " " + skip.getChanged() + "건, stop_on_failure=" + stop + ", 열 "
                        + (si.hasColumn("test_plan_actions", "skip_on_failure") ? "있음" : "없음"));
        check(r.getProblems().isEmpty(), "정리 뒤 문제가 없다", "[]", r.getProblems());
    }

    private static TestPlan plan(String name, int stopOnFailure) {
        TestPlan p = new TestPlan();
        p.setName(name);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        TestPlanAction a = new TestPlanAction();
        a.setPlan(p);
        a.setSeq(1);
        a.setDeviceId(10);
        a.setTarget("3_1");
        a.setValue(1);
        a.setDataType("FP32AD");
        a.setStopOnFailure(stopOnFailure);
        p.getActions().add(a);
        return p;
    }

    // ── 픽스처 ───────────────────────────────────────────────

    private void createDb(JdbcTemplate admin, String name) {
        if (db == Db.MARIADB) admin.execute("CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        // 172.17.74.4 의 cts 와 같은 collation. varchar 는 CP949 다
        else admin.execute("CREATE DATABASE " + name + " COLLATE Korean_Wansung_CI_AS");
    }

    private void dropDb(JdbcTemplate admin, String name) {
        try {
            if (db == Db.SQLSERVER) admin.execute("ALTER DATABASE " + name + " SET SINGLE_USER WITH ROLLBACK IMMEDIATE");
            admin.execute("DROP DATABASE " + name);
        } catch (RuntimeException e) {
            System.out.println("[" + db.key() + "] could not drop " + name + ": " + e.getMessage());
        }
    }

    private void loadFixture(String name) throws Exception {
        SingleConnectionDataSource one = new SingleConnectionDataSource(db.dbUrl(name), db.user(), db.password(), true);
        one.setDriverClassName(db.driver);
        try {
            JdbcTemplate j = new JdbcTemplate(one);
            String ddl;
            try (InputStream in = getClass().getResourceAsStream("/db-it/old-1x-" + db.key() + ".sql")) {
                ddl = readAll(Objects.requireNonNull(in));
            }
            StringBuilder sb = new StringBuilder();
            for (String line : ddl.split("\n")) {
                if (line.trim().startsWith("--")) continue;
                sb.append(line).append('\n');
                if (line.trim().endsWith(";")) {
                    j.execute(sb.toString().trim().replaceAll(";$", ""));
                    sb.setLength(0);
                }
            }
            insert(j, "groups", "id,name,parent_id", new Object[][]{{1, "ROOT", 0}, {2, "한글 그룹", 1}});
            insert(j, "devices", "id,name,protocol,transport,host,port,enabled,log,group_id,communication_settings", new Object[][]{
                    {10, "MB-1", 2, 1, "0.0.0.0", 15001, 0, 0, 2, "{\"unitId\":\"1\",\"packetType\":\"1\",\"registers\":\"100\"}"},
                    {11, "SNMP-장비", 5, 2, "0.0.0.0", 15002, 0, 0, 2, "{\"snmpVersion\":\"0\",\"getCommunity\":\"public\",\"setCommunity\":\"private\"}"},
                    {12, "BACNET-1", 6, 2, "0.0.0.0", 15003, 0, 0, 2, "{\"deviceInstanceId\":\"102\",\"broadcast\":\"255.255.255.255\"}"},
                    {13, "EIP-1", 9, 1, "0.0.0.0", 15004, 0, 0, 2, "{\"cpuSlot\":\"0\",\"syn_port\":\"2222\",\"implicit_t2o_addr\":\"0.0.0.0\","
                            + "\"implicit_t2o_assy_size\":\"208\",\"implicit_o2t_assy_size\":\"20\"}"}});
            insert(j, "simulations", "id,device_id,name,target,policies,interval,enabled,stop_on_control", new Object[][]{
                    {100, 10, "s100", "3_1", fixed("FP32AD", "12.5"), 1, 1, 0},
                    {101, 10, "s101", "1_1", fixed("UI16AB", "1.0"), 1, 1, 0},
                    {102, 11, "s102", ".1.3.6.1.4.1.9999.1", fixed("FP32AD", "1.0"), 1, 1, 0},
                    {103, 11, "s103", ".1.3.6.1.4.1.9999.2", fixed("UI16AB", "7.0"), 1, 1, 0},
                    {104, 11, "s104", ".1.3.6.1.4.1.9999.3", fixed("UI16AB", "2.5"), 1, 1, 0},
                    {105, 11, "s105", ".1.3.6.1.4.1.9999.4", fixed("BOOL", "1.0"), 1, 1, 0},
                    {106, 12, "s106", "binary_value 2", fixed("BOOL", "1.0"), 1, 1, 0},
                    {107, 12, "s107", "analog_input 13", "[{\"id\":\"0\",\"policy\":\"RANDOM\",\"data_type\":\"FP32AD\",\"range\":[-1.0,1.0]}]", 1, 1, 0},
                    {108, 13, "s108", "REAL@TAG1", fixed("FP32AD", "1.5"), 1, 1, 0},
                    {109, 13, "s109", "DINT@TAG2", fixed("SI32AD", "5.0"), 1, 1, 3}});
            insert(j, "errors", "id,device_id,simulation_id,mode,frequency,enabled,timeout,garbage_length,interval,error_duration,error_value,simulation_stop",
                    new Object[][]{{1, 10, 100, 1, 100, 0, 0, 0, 2, 3, 4242.0, 0}});
        } finally {
            one.destroy();
        }
    }

    private static String fixed(String type, String value) {
        return "[{\"id\":\"1\",\"policy\":\"STATIC\",\"data_type\":\"" + type + "\",\"value\":" + value + "}]";
    }

    /** id 를 직접 넣는다. SQL Server 는 같은 연결에서 IDENTITY_INSERT 를 켜야 한다 */
    private void insert(JdbcTemplate j, String table, String cols, Object[][] rows) {
        String list = Arrays.stream(cols.split(",")).map(c -> "interval".equals(c) ? db.q(c) : c).collect(Collectors.joining(", "));
        String marks = Arrays.stream(cols.split(",")).map(c -> "?").collect(Collectors.joining(", "));
        if (db == Db.SQLSERVER) j.execute("SET IDENTITY_INSERT " + table + " ON");
        for (Object[] row : rows) j.update("INSERT INTO " + table + " (" + list + ") VALUES (" + marks + ")", row);
        if (db == Db.SQLSERVER) j.execute("SET IDENTITY_INSERT " + table + " OFF");
    }

    private ConfigurableApplicationContext boot(String name) {
        String config = System.getProperty("cts.it.config", "application.properties");
        return new SpringApplicationBuilder(Mk119CommunicationTestSuiteApplication.class).run(
                "--spring.config.additional-location=file:" + config,
                "--server.port=0",
                "--spring.datasource.url=" + db.dbUrl(name),
                "--spring.datasource.driver-class-name=" + db.driver,
                "--spring.datasource.username=" + db.user(),
                "--spring.datasource.password=" + db.password(),
                "--debug.modbus-poll.enabled=false");
    }

    private DriverManagerDataSource ds(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource(url, db.user(), db.password());
        ds.setDriverClassName(db.driver);
        return ds;
    }

    // ── 비교 ─────────────────────────────────────────────────

    private Map<String, Map<Object, Map<String, Object>>> snapshot(JdbcTemplate j) {
        Map<String, Map<Object, Map<String, Object>>> out = new HashMap<>();
        for (String t : Arrays.asList("groups", "devices", "simulations", "errors")) {
            Map<Object, Map<String, Object>> rows = new TreeMap<>();
            for (Map<String, Object> r : j.queryForList("SELECT * FROM " + t)) {
                Map<String, Object> lower = new TreeMap<>();
                r.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
                rows.put(((Number) lower.get("id")).intValue(), lower);
            }
            out.put(t, rows);
        }
        return out;
    }

    /** 앱 테이블(엔티티 테이블 9개)의 varchar·char·text 열 */
    private static List<String> narrowColumns(Map<String, Map<String, SchemaInspector.Column>> cols) {
        Set<String> app = new HashSet<>(Arrays.asList("groups", "devices", "simulations", "errors", "protocol_definitions",
                "test_plans", "test_plan_actions", "test_plan_runs", "test_plan_run_actions"));
        List<String> out = new ArrayList<>();
        cols.forEach((t, cs) -> {
            if (!app.contains(t)) return;
            for (SchemaInspector.Column c : cs.values()) {
                String type = c.type.toLowerCase(Locale.ROOT);
                if (type.equals("varchar") || type.equals("char") || type.equals("text")) out.add(c.toString());
            }
        });
        return out;
    }

    private static String diff(Map<Object, Map<String, Object>> a, Map<Object, Map<String, Object>> b) {
        if (a.equals(b)) return a.size() + "행 동일";
        List<String> out = new ArrayList<>();
        for (Object id : a.keySet()) if (!Objects.equals(a.get(id), b.get(id))) out.add(id + ": " + a.get(id) + " -> " + b.get(id));
        for (Object id : b.keySet()) if (!a.containsKey(id)) out.add(id + ": (new) " + b.get(id));
        return out.toString();
    }

    private static JsonElement policiesWithoutType(Object raw) {
        JsonArray arr = JsonParser.parseString(String.valueOf(raw)).getAsJsonArray();
        for (JsonElement e : arr) if (e.isJsonObject()) e.getAsJsonObject().remove("data_type");
        return arr;
    }

    private static String dataTypeOf(Object raw) {
        JsonArray arr = JsonParser.parseString(String.valueOf(raw)).getAsJsonArray();
        List<String> types = new ArrayList<>();
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            types.add(o.has("data_type") && !o.get("data_type").isJsonNull() ? o.get("data_type").getAsString() : "null");
        }
        return String.join(",", types);
    }

    private static boolean fails(Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static String get(int port, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://localhost:" + port + path).openConnection();
        try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
            return in == null ? "" : readAll(in);
        }
    }

    /** Java 8 이라 InputStream.readAllBytes 가 없다 */
    private static String readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static StartupReport.Step step(StartupReport r, String name) {
        return r.getSteps().stream().filter(s -> s.getName().equals(name)).findFirst()
                .orElse(new StartupReport.Step(name, StartupReport.Status.FAILED, -1, "not run", 0));
    }

    private static List<String> names(StartupReport r) {
        return r.getSteps().stream().map(StartupReport.Step::getName).collect(Collectors.toList());
    }

    // ── 기록 ─────────────────────────────────────────────────

    private void section(String title, String input) {
        lines.add("");
        lines.add("### " + title);
        lines.add("입력: " + input);
        System.out.println("[" + db.key() + "] " + title + " — " + input);
    }

    private void checkChanged(StartupReport r, String name, int expected) {
        StartupReport.Step s = step(r, name);
        check(s.getStatus() == StartupReport.Status.OK && s.getChanged() == expected, "단계 \"" + name + "\" 가 " + expected + "건을 바꾼다",
                "OK " + expected, s.getStatus() + " " + s.getChanged() + " (" + s.getMessage() + ")");
    }

    private void roundTrip(String what, String viaJdbc, String viaJpa, String expected) {
        boolean ok = expected.equals(viaJdbc) && (viaJpa == null || expected.equals(viaJpa));
        check(ok, what + " 에 저장한 글자가 그대로 읽힌다", expected, "JDBC=" + viaJdbc + (viaJpa == null ? "" : ", JPA=" + viaJpa));
    }

    private void check(boolean ok, String what, Object expected, Object actual) {
        String line = (ok ? "ok   " : "FAIL ") + what + " | 기대: " + expected + " | 실제: " + actual;
        lines.add("- " + line);
        System.out.println("[" + db.key() + "] " + line);
        if (!ok) failures.add(line);
    }

    private void writeReport(String name) {
        try {
            java.nio.file.Path p = java.nio.file.Paths.get("report-" + db.key() + ".md");
            List<String> out = new ArrayList<>();
            out.add("# DB 이식성 시험 — " + db + " (" + db.adminUrl() + ", 임시 DB " + name + ")");
            out.addAll(lines);
            out.add("");
            out.add("실패 " + failures.size() + "건");
            java.nio.file.Files.write(p, out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("report: " + e);
        }
    }
}
