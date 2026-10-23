package com.onionsoftware.mk119.commtestsuite.web;

import com.onionsoftware.mk119.commtestsuite.db.bean.Device;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlan;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlanAction;
import com.onionsoftware.mk119.commtestsuite.db.bean.TestPlanRun;
import com.onionsoftware.mk119.commtestsuite.db.service.DeviceService;
import com.onionsoftware.mk119.commtestsuite.db.service.TestPlanService;
import com.onionsoftware.mk119.commtestsuite.db.service.TestPlanValidator;
import com.onionsoftware.mk119.commtestsuite.enums.ScheduleType;
import com.onionsoftware.mk119.commtestsuite.enums.TestPlanTrigger;
import com.onionsoftware.mk119.commtestsuite.testplan.TestPlanRunner;
import com.onionsoftware.mk119.commtestsuite.testplan.TestPlanScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Test Plan REST API. 화면(Web console)도 이 엔드포인트로 실행한다.
 * <p>
 * 실패 응답은 {@code {ok: false, error}} 또는 검증 실패면 {@code {ok: false, errors, warnings}} 이다.
 * 검증 오류 하나는 {@code {rule, field, action, row, message}} 이고 rule 은
 * docs/TEST_PLAN_VALIDATION.md 의 ID 다.
 */
@RestController
@RequestMapping(value = "/api/testplans")
@RequiredArgsConstructor
public class TestPlanController {
    private final TestPlanService testPlanService;
    private final TestPlanValidator testPlanValidator;
    private final TestPlanRunner testPlanRunner;
    private final TestPlanScheduler testPlanScheduler;
    private final DeviceService deviceService;

    /** 실행 이력 목록의 기본 개수. */
    private static final int DEFAULT_RUN_LIMIT = 50;

    // ── 조회 ─────────────────────────────────────────────────────────────

    /** 목록. Action 은 개수만 싣는다. */
    @GetMapping
    public List<Map<String, Object>> list() {
        LocalDateTime now = LocalDateTime.now();
        Map<Integer, TestPlanRunner.Status> running = runningByPlan();
        List<Map<String, Object>> out = new ArrayList<>();
        for (TestPlan p : testPlanService.selectTestPlans()) {
            Map<String, Object> m = planHeader(p, now, running.get(p.getId()));
            List<TestPlanRun> last = testPlanService.selectRuns(p.getId(), 1);
            m.put("lastRun", last.isEmpty() ? null : last.get(0));
            out.add(m);
        }
        return out;
    }

    /** Plan 하나와 Action. Action 에 장비 이름을 붙인다(지워진 장비면 null). */
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Integer id) {
        Optional<TestPlan> plan = testPlanService.selectTestPlan(id);
        if (!plan.isPresent()) return notFound(id);
        Map<String, Object> m = planHeader(plan.get(), LocalDateTime.now(), runningByPlan().get(id));
        m.put("actions", actionsWithDeviceNames(plan.get()));
        return ResponseEntity.ok(m);
    }

    /** 실행 중인 것 전부. 화면이 주기적으로 읽는다. */
    @GetMapping("/status")
    public List<TestPlanRunner.Status> status() {
        return testPlanRunner.statuses();
    }

    @GetMapping("/{id}/runs")
    public ResponseEntity<?> runs(@PathVariable Integer id, @RequestParam(required = false) Integer limit) {
        if (!testPlanService.selectTestPlan(id).isPresent()) return notFound(id);
        int n = limit == null ? DEFAULT_RUN_LIMIT : Math.min(Math.max(limit, 1), TestPlanService.KEEP_RUNS);
        return ResponseEntity.ok(testPlanService.selectRuns(id, n));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> run(@PathVariable Long runId) {
        Optional<TestPlanRun> run = testPlanService.selectRun(runId);
        if (!run.isPresent()) return fail(HttpStatus.NOT_FOUND, "Test plan run " + runId + " does not exist.");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run", run.get());
        m.put("actions", testPlanService.selectRunActions(runId));
        return ResponseEntity.ok(m);
    }

    // ── 생성 / 수정 / 삭제 ───────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        TestPlanValidator.Outcome out = testPlanValidator.validatePlan(body, null);
        if (!out.isOk()) return invalid(out);
        TestPlan saved = testPlanService.insertTestPlan(out.getPlan());
        testPlanScheduler.register(saved);
        return saved(saved);
    }

    /** P4: 실행 중이면 409. */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        if (!testPlanService.selectTestPlan(id).isPresent()) return notFound(id);
        if (testPlanRunner.isRunning(id)) return running(id);
        TestPlanValidator.Outcome out = testPlanValidator.validatePlan(body, id);
        if (!out.isOk()) return invalid(out);
        TestPlan saved = testPlanService.updateTestPlan(out.getPlan());
        testPlanScheduler.register(saved);
        return saved(saved);
    }

    /** P4: 실행 중이면 409. Action 과 실행 이력은 함께 지워진다. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        if (!testPlanService.selectTestPlan(id).isPresent()) return notFound(id);
        if (testPlanRunner.isRunning(id)) return running(id);
        testPlanScheduler.unregister(id);
        testPlanService.deleteTestPlan(id);
        return ok(Collections.singletonMap("id", id));
    }

    /** 스케줄 켜기/끄기. 실행 중에도 된다(진행 중인 실행에는 영향이 없다). */
    @PostMapping("/{id}/schedule/enabled/{enabled}")
    public ResponseEntity<?> scheduleEnabled(@PathVariable Integer id, @PathVariable Integer enabled) {
        Optional<TestPlan> plan = testPlanService.selectTestPlan(id);
        if (!plan.isPresent()) return notFound(id);
        boolean on = enabled != null && enabled == TestPlan.SCHEDULE_ENABLED;
        if (on && plan.get().getScheduleType() == ScheduleType.NONE) {
            return fail(HttpStatus.BAD_REQUEST, "This test plan has no schedule. Set one in the editor first.");
        }
        TestPlan saved = testPlanService.updateScheduleEnabled(id, on ? TestPlan.SCHEDULE_ENABLED : TestPlan.SCHEDULE_DISABLED);
        testPlanScheduler.register(saved);
        return saved(saved);
    }

    // ── 실행 ─────────────────────────────────────────────────────────────

    /** E1: 실행 중이면 409. */
    @PostMapping("/{id}/run")
    public ResponseEntity<?> start(@PathVariable Integer id) {
        if (!testPlanService.selectTestPlan(id).isPresent()) return notFound(id);
        try {
            TestPlanRun run = testPlanRunner.start(id, TestPlanTrigger.MANUAL);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("runId", run.getId());
            return ok(m);
        } catch (TestPlanRunner.AlreadyRunningException e) {
            return running(id);
        }
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<?> stop(@PathVariable Integer id) {
        if (!testPlanService.selectTestPlan(id).isPresent()) return notFound(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("stopped", testPlanRunner.stop(id));
        return ok(m);
    }

    // ── 엑셀 ─────────────────────────────────────────────────────────────

    /**
     * 엑셀 검증. body: {@code {sheets: [{name, startRow, rows: [[헤더...], [셀...]]}]}}.
     * 저장하지 않는다. 화면은 이 결과로 오류 행을 표시하고 Register 를 잠근다(X7).
     */
    @PostMapping("/validate")
    public Map<String, Object> validateWorkbook(@RequestBody Map<String, Object> body) {
        return workbookResult(testPlanValidator.validateWorkbook(sheetsOf(body)));
    }

    /** 엑셀 등록. 다시 검증하고, 전부 통과하면 한 트랜잭션으로 저장한다(X7). 스케줄은 없다(X8). */
    @PostMapping("/import")
    public ResponseEntity<?> importWorkbook(@RequestBody Map<String, Object> body) {
        TestPlanValidator.WorkbookOutcome wb = testPlanValidator.validateWorkbook(sheetsOf(body));
        if (!wb.isOk()) return ResponseEntity.badRequest().body(workbookResult(wb));
        List<Map<String, Object>> created = new ArrayList<>();
        for (TestPlan p : testPlanService.insertTestPlans(wb.plans())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("actionCount", p.getActions().size());
            created.add(m);
        }
        return ok(Collections.singletonMap("plans", created));
    }

    /**
     * 엑셀 내보내기. import 와 같은 시트 형식을 돌려주므로 화면은 그대로 파일로 만들면 되고,
     * 그 파일은 다시 import 할 수 있다. 지워진 장비는 {@code #<id>} 로 내보낸다 — 다시 올리면 A1 에 걸린다.
     *
     * @param ids 쉼표로 구분한 Plan id. 없으면 전부
     */
    @GetMapping("/export")
    public List<Map<String, Object>> exportWorkbook(@RequestParam(required = false) String ids) {
        Set<Integer> wanted = new HashSet<>();
        if (ids != null) {
            for (String s : ids.split(",")) {
                try {
                    wanted.add(Integer.valueOf(s.trim()));
                } catch (NumberFormatException ignored) {
                    // 숫자가 아닌 조각은 무시한다
                }
            }
        }
        Map<Integer, String> names = deviceNames();
        List<Map<String, Object>> sheets = new ArrayList<>();
        for (TestPlan p : testPlanService.selectTestPlans()) {
            if (!wanted.isEmpty() && !wanted.contains(p.getId())) continue;
            List<List<Object>> rows = new ArrayList<>();
            rows.add(new ArrayList<>(TestPlanValidator.COLUMNS));
            for (TestPlanAction a : p.getActions()) {
                String device = names.get(a.getDeviceId());
                rows.add(Arrays.asList(
                        device != null ? device : "#" + a.getDeviceId(),
                        a.getTarget(),
                        a.getValue(),
                        a.getDataType(),
                        a.getLockSeconds(),
                        a.getIntervalSeconds(),
                        a.getSkipOnFailure() == 1 ? "TRUE" : "FALSE"));
            }
            Map<String, Object> sheet = new LinkedHashMap<>();
            sheet.put("name", p.getName());
            sheet.put("rows", rows);
            sheets.add(sheet);
        }
        return sheets;
    }

    // ── 응답 구성 ────────────────────────────────────────────────────────

    private Map<String, Object> planHeader(TestPlan p, LocalDateTime now, TestPlanRunner.Status status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("scheduleType", p.getScheduleType());
        m.put("scheduleHour", p.getScheduleHour());
        m.put("scheduleMinute", p.getScheduleMinute());
        m.put("scheduleSecond", p.getScheduleSecond());
        m.put("scheduleEnabled", p.getScheduleEnabled() == TestPlan.SCHEDULE_ENABLED);
        m.put("nextRunAt", TestPlanScheduler.nextRunOf(p, now));
        m.put("actionCount", p.getActions().size());
        m.put("totalIntervalSeconds", p.totalIntervalSeconds());
        m.put("running", status != null);
        m.put("status", status);
        m.put("createdAt", p.getCreatedAt());
        m.put("updatedAt", p.getUpdatedAt());
        return m;
    }

    private List<Map<String, Object>> actionsWithDeviceNames(TestPlan p) {
        Map<Integer, String> names = deviceNames();
        List<Map<String, Object>> out = new ArrayList<>();
        for (TestPlanAction a : p.getActions()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", a.getSeq());
            m.put("deviceId", a.getDeviceId());
            m.put("deviceName", names.get(a.getDeviceId()));
            m.put("target", a.getTarget());
            m.put("value", a.getValue());
            m.put("dataType", a.getDataType());
            m.put("lockSeconds", a.getLockSeconds());
            m.put("intervalSeconds", a.getIntervalSeconds());
            m.put("skipOnFailure", a.getSkipOnFailure() == 1);
            out.add(m);
        }
        return out;
    }

    private Map<Integer, String> deviceNames() {
        Map<Integer, String> names = new HashMap<>();
        for (Device d : deviceService.selectDevices()) names.put(d.getId(), d.getName());
        return names;
    }

    private Map<Integer, TestPlanRunner.Status> runningByPlan() {
        Map<Integer, TestPlanRunner.Status> m = new HashMap<>();
        for (TestPlanRunner.Status s : testPlanRunner.statuses()) m.put(s.getPlanId(), s);
        return m;
    }

    private Map<String, Object> workbookResult(TestPlanValidator.WorkbookOutcome wb) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", wb.isOk());
        m.put("errors", wb.getErrors());
        List<Map<String, Object>> sheets = new ArrayList<>();
        for (TestPlanValidator.SheetOutcome s : wb.getSheets()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("sheet", s.getSheet());
            sm.put("ok", s.getOutcome().isOk());
            sm.put("actionCount", s.getOutcome().getPlan() == null ? null : s.getOutcome().getPlan().getActions().size());
            sm.put("errors", s.getOutcome().getErrors());
            sm.put("warnings", s.getOutcome().getWarnings());
            sheets.add(sm);
        }
        m.put("sheets", sheets);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sheetsOf(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("sheets");
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List)) return out;
        for (Object o : (List<Object>) raw) {
            out.add(o instanceof Map ? (Map<String, Object>) o : Collections.emptyMap());
        }
        return out;
    }

    private ResponseEntity<?> saved(TestPlan p) {
        Map<String, Object> m = planHeader(p, LocalDateTime.now(), runningByPlan().get(p.getId()));
        m.put("actions", actionsWithDeviceNames(p));
        return ok(Collections.singletonMap("plan", m));
    }

    private static ResponseEntity<?> ok(Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.putAll(extra);
        return ResponseEntity.ok(m);
    }

    private static ResponseEntity<?> invalid(TestPlanValidator.Outcome out) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("errors", out.getErrors());
        m.put("warnings", out.getWarnings());
        return ResponseEntity.badRequest().body(m);
    }

    private static ResponseEntity<?> fail(HttpStatus status, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error);
        return ResponseEntity.status(status).body(m);
    }

    private static ResponseEntity<?> notFound(Integer id) {
        return fail(HttpStatus.NOT_FOUND, "Test plan " + id + " does not exist.");
    }

    private static ResponseEntity<?> running(Integer id) {
        return fail(HttpStatus.CONFLICT, "Test plan " + id + " is running. Stop it first.");
    }
}
